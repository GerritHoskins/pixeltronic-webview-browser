import Foundation
import UIKit
import WebKit

// The clipping surface must not consume touches outside the embedded browser.
private final class BrowserClipView: UIView {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        return hit === self ? nil : hit
    }
}

@objc public class InAppBrowser: UIViewController, WKUIDelegate, WKNavigationDelegate {
    private(set) var webview: WKWebView?
    weak var plugin: InAppBrowserPlugin?
    private let configuration: WKWebViewConfiguration
    private let headers: [String: String]
    let navigationPolicy: BrowserNavigationPolicy
    private var disposed = false
    private(set) var loading = false
    var hidden = false
    var layoutVisible = true
    var snapshotMode = false
    var initialURL: URL?
    var layout: BrowserLayout?
    private weak var host: WKWebView?
    private var viewportHeight: NSLayoutConstraint?
    private var geometryReady = true
    var captureVisible: Bool { !hidden && layoutVisible && !snapshotMode && geometryReady }

    init(_ plugin: InAppBrowserPlugin, configuration: WKWebViewConfiguration,
         headers: [String: String] = [:], policy: BrowserNavigationPolicy) {
        self.plugin = plugin
        self.configuration = configuration
        self.headers = headers
        self.navigationPolicy = policy
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { return nil }

    override public func loadView() {
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.uiDelegate = self
        view.navigationDelegate = self
        view.backgroundColor = .white
        view.scrollView.bounces = false
        view.allowsBackForwardNavigationGestures = true
        view.isOpaque = false
        view.customUserAgent = headers.first { $0.key.lowercased() == "user-agent" }?.value
        self.webview = view
        let clip = BrowserClipView(frame: .zero)
        clip.clipsToBounds = true
        clip.addSubview(view)
        self.view = clip
    }

    func trackHost(_ host: WKWebView) {
        self.host = host
        view.translatesAutoresizingMaskIntoConstraints = false
        let preferredHeight = view.heightAnchor.constraint(equalTo: host.heightAnchor)
        preferredHeight.priority = .defaultHigh
        let limit = view.heightAnchor.constraint(lessThanOrEqualToConstant: max(1, host.bounds.height))
        viewportHeight = limit
        NSLayoutConstraint.activate([
            view.leadingAnchor.constraint(equalTo: host.leadingAnchor),
            view.topAnchor.constraint(equalTo: host.topAnchor),
            view.widthAnchor.constraint(equalTo: host.widthAnchor),
            view.heightAnchor.constraint(lessThanOrEqualTo: host.heightAnchor), preferredHeight, limit
        ])
        try? applyLayout()
    }

    override public func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        do { try applyLayout() }
        catch { geometryReady = false; updateVisibility() }
    }

    func applyLayout() throws {
        guard isViewLoaded, let layout = layout else { return }
        let oldFrame = webview?.frame
        let oldContainerFrame = view.frame
        let oldClipHeight = viewportHeight?.constant
        if let host = host, host.bounds.width > 0 {
            let projected = try layout.project(host: host.bounds.size)
            if viewportHeight?.constant != projected.clip.height { viewportHeight?.constant = projected.clip.height }
            webview?.frame = projected.frame
        } else {
            _ = try layout.project(host: layout.rect.size)
            view.frame = layout.rect
            webview?.frame = view.bounds
        }
        geometryReady = true
        updateVisibility()
        if oldFrame != webview?.frame || oldContainerFrame != view.frame || oldClipHeight != viewportHeight?.constant {
            plugin?.notifyEventListeners(eventName: "updateDimensions", eventValue: [
                "width": layout.rect.width, "height": layout.rect.height,
                "x": layout.rect.origin.x, "y": layout.rect.origin.y
            ])
        }
    }

    @discardableResult func loadUrl(_ url: URL) -> WKNavigation? {
        guard !disposed, navigationPolicy.evaluate(url).allowed else { return nil }
        loadViewIfNeeded()
        loading = true
        snapshotMode = false
        updateVisibility()
        return webview?.load(BrowserRequest.make(url, initialURL: initialURL ?? url, headers: headers))
    }

    public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard !disposed, checkCommittedURL(webView) else { return }
        plugin?.navigationFinished(self, navigation: navigation)
        sendLoadingEvent(false)
    }

    public func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        guard !disposed else { return }
        plugin?.navigationFailed(self, navigation: navigation, error: error)
        sendLoadingEvent(false)
    }

    public func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        self.webView(webView, didFail: navigation, withError: error)
    }

    public func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        guard !disposed else { return }
        plugin?.contentProcessTerminated(self)
    }

    public func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        guard !disposed else { return }
        sendLoadingEvent(true)
    }

    public func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        guard !disposed else { return }
        _ = checkCommittedURL(webView)
    }

    private func checkCommittedURL(_ webView: WKWebView) -> Bool {
        guard let url = webView.url else { return true }
        let decision = navigationPolicy.evaluate(url)
        if !decision.allowed {
            plugin?.recordNavigation(self, url: url, popup: false, mainFrame: true, decision: decision, late: true)
        }
        return decision.allowed
    }

    public func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                        for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard !disposed else { return nil }
        plugin?.recordNavigation(self, url: navigationAction.request.url, popup: true, mainFrame: true,
                                 decision: BrowserNavigationPolicy.popupBlocked)
        return nil
    }

    public func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard !disposed else { decisionHandler(.cancel); return }
        let popup = navigationAction.targetFrame == nil
        let decision = popup ? BrowserNavigationPolicy.popupBlocked : navigationPolicy.evaluate(navigationAction.request.url)
        // Allow WebKit's original request, including POST bodies, rather than replaying it.
        decisionHandler(decision.allowed ? .allow : .cancel)
        plugin?.recordNavigation(self, url: navigationAction.request.url, popup: popup,
                                 mainFrame: navigationAction.targetFrame?.isMainFrame ?? true, decision: decision)
    }

    public func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                        decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        guard !disposed else { decisionHandler(.cancel); return }
        let decision = navigationPolicy.evaluate(navigationResponse.response.url)
        decisionHandler(decision.allowed ? .allow : .cancel)
        if !decision.allowed {
            plugin?.recordNavigation(self, url: navigationResponse.response.url, popup: false,
                                     mainFrame: navigationResponse.isForMainFrame, decision: decision, late: true)
        }
    }

    func snapshot(_ completion: @escaping (UIImage?, Error?) -> Void) {
        guard !disposed, let webview = webview else {
            completion(nil, BrowserInputError(message: "No WebView is open.")); return
        }
        let config = WKSnapshotConfiguration()
        let size = webview.bounds.size
        let screenScale = webview.window?.screen.scale ?? UIScreen.main.scale
        let scale = min(screenScale, 2048 / max(size.width, size.height))
        // WebKit accepts points and emits pixels at the screen scale.
        config.snapshotWidth = NSNumber(value: Double(size.width * scale / screenScale))
        webview.takeSnapshot(with: config, completionHandler: completion)
    }

    func sendLoadingEvent(_ isLoading: Bool) {
        guard !disposed else { return }
        loading = isLoading
        if isLoading { snapshotMode = false }
        updateVisibility()
        plugin?.notifyEventListeners(eventName: "pageLoaded", eventValue: ["isLoading": isLoading])
    }

    func updateVisibility() { if isViewLoaded { view.isHidden = hidden || loading || !layoutVisible || snapshotMode || !geometryReady } }

    func dispose() {
        guard !disposed else { return }
        disposed = true
        webview?.stopLoading()
        webview?.navigationDelegate = nil
        webview?.uiDelegate = nil
        willMove(toParent: nil)
        if isViewLoaded { view.removeFromSuperview() }
        removeFromParent()
        plugin = nil
    }
}
