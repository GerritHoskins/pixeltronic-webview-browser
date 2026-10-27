import Foundation
import UIKit
import WebKit

@objc public class InAppBrowser: UIViewController, WKUIDelegate, WKNavigationDelegate {
    private(set) var webview: WKWebView?
    weak var plugin: InAppBrowserPlugin?
    private let configuration: WKWebViewConfiguration
    private let headers: [String: String]
    let navigationPolicy: BrowserNavigationPolicy
    private var disposed = false
    private var loading = false
    var hidden = false

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
        self.view = view
    }

    @discardableResult func loadUrl(_ url: URL) -> WKNavigation? {
        guard !disposed, navigationPolicy.evaluate(url).allowed else { return nil }
        loadViewIfNeeded()
        return webview?.load(URLRequest(url: url))
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
        webview.takeSnapshot(with: nil, completionHandler: completion)
    }

    func sendLoadingEvent(_ isLoading: Bool) {
        guard !disposed else { return }
        loading = isLoading
        updateVisibility()
        plugin?.notifyEventListeners(eventName: "pageLoaded", eventValue: ["isLoading": isLoading])
    }

    func updateVisibility() { if isViewLoaded { view.isHidden = hidden || loading } }

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
