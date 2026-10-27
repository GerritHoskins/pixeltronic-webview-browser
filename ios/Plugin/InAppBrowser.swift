import Foundation
import UIKit
import WebKit

@objc public class InAppBrowser: UIViewController, WKUIDelegate, WKNavigationDelegate {
    private(set) var webview: WKWebView?
    weak var plugin: InAppBrowserPlugin?
    private let configuration: WKWebViewConfiguration
    private let headers: [String: String]
    private(set) var currentDecisionHandler: ((WKNavigationResponsePolicy) -> Void)?
    private var decisionTimeout: DispatchWorkItem?
    private var decisionGeneration = 0
    private var openNewWindow = false
    private var currentUrl: URL?
    private var disposed = false
    private var loading = false
    var hidden = false

    init(_ plugin: InAppBrowserPlugin, configuration: WKWebViewConfiguration, headers: [String: String] = [:]) {
        self.plugin = plugin
        self.configuration = configuration
        self.headers = headers
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { return nil }

    override public func loadView() {
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
        guard !disposed else { return nil }
        loadViewIfNeeded()
        return webview?.load(URLRequest(url: url))
    }

    public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard !disposed else { return }
        currentUrl = webView.url
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

    public func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                        for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard !disposed else { return nil }
        if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
            openNewWindow = true
            loadUrl(url)
        }
        return nil
    }

    public func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                        decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        guard !disposed else { decisionHandler(.cancel); return }
        completeDecision(.cancel)
        guard plugin?.hasEventListeners(eventName: "navigationHandler") == true else { decisionHandler(.allow); return }
        currentDecisionHandler = decisionHandler
        let generation = decisionGeneration
        let timeout = DispatchWorkItem { [weak self] in
            DispatchQueue.main.async {
                guard let self = self, self.decisionGeneration == generation else { return }
                self.completeDecision(.cancel)
            }
        }
        decisionTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: timeout)
        plugin?.notifyEventListeners(eventName: "navigationHandler", eventValue: [
            "url": navigationResponse.response.url?.absoluteString ?? "",
            "newWindowRequest": openNewWindow,
            "isSameHost": currentUrl?.host != nil && currentUrl?.host == navigationResponse.response.url?.host
        ])
        openNewWindow = false
    }

    func completeDecision(_ policy: WKNavigationResponsePolicy) {
        decisionGeneration += 1
        decisionTimeout?.cancel()
        decisionTimeout = nil
        let handler = currentDecisionHandler
        currentDecisionHandler = nil
        handler?(policy)
        if handler != nil && policy == .cancel { sendLoadingEvent(false) }
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
        completeDecision(.cancel)
        webview?.stopLoading()
        webview?.navigationDelegate = nil
        webview?.uiDelegate = nil
        willMove(toParent: nil)
        if isViewLoaded { view.removeFromSuperview() }
        removeFromParent()
        plugin = nil
    }
}
