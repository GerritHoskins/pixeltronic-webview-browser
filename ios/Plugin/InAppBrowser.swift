import Foundation
import Capacitor
import UIKit
import WebKit

@objc public class InAppBrowser: UIViewController, WKUIDelegate, WKNavigationDelegate {
    var webview: WKWebView?
    var plugin: InAppBrowserPlugin!
    var configuration: WKWebViewConfiguration!
    var currentDecisionHandler: ((WKNavigationResponsePolicy) -> Void)?
    var openNewWindow: Bool = false
    var currentUrl: URL?
    var originalUserAgent: String?

    init(_ plugin: InAppBrowserPlugin, configuration: WKWebViewConfiguration) {
        super.init(nibName: "InAppBrowser", bundle: nil)
        self.plugin = plugin
        self.configuration = configuration
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override public func loadView() {
        self.webview = WKWebView(frame: .zero, configuration: self.configuration)
        self.webview?.uiDelegate = self
        self.webview?.navigationDelegate = self

        view = self.webview
        view.backgroundColor = UIColor.white
        view.isHidden = true
        self.webview?.scrollView.bounces = false
        self.webview?.allowsBackForwardNavigationGestures = true

        self.webview?.isOpaque = false

        self.setHeaders(headers: self.plugin.headers!)
        self.webview?.customUserAgent = self.customUserAgent ?? self.userAgent ?? self.originalUserAgent

        let blur = UIVisualEffectView(effect: UIBlurEffect(style: UIBlurEffect.Style.regular))
        blur.clipsToBounds = true
        blur.isUserInteractionEnabled = false
    }

    public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        currentUrl = webView.url
        if self.plugin?.savedCall != nil {
            self.plugin?.savedCall?.resolve()
            self.plugin?.savedCall = nil
        }
        self.sendLoadingEvent(false)
    }

    public func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        self.handleError(errorCode: error._code as NSNumber)
    }

    public func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        self.handleError(errorCode: error._code as NSNumber)
    }

    public func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
            if plugin.hasEventListeners(eventName: "navigationHandler") {
                self.openNewWindow = true
            }
            self.loadUrl(url)
        }
        return nil
    }

    public func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        self.sendLoadingEvent(true)
        self.clearDecisionHandler()
    }

    public func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        if self.currentDecisionHandler != nil {
            self.clearDecisionHandler()
        }
        let hasHandler = self.plugin.hasEventListeners(eventName: "navigationHandler")
        if hasHandler {
            self.currentDecisionHandler = decisionHandler
            self.plugin.notifyEventListeners(eventName: "navigationHandler", eventValue: [
                "url": navigationResponse.response.url?.absoluteString ?? "",
                "newWindowRequest": self.openNewWindow,
                "isSameHost": currentUrl?.host == navigationResponse.response.url?.host
            ])
            self.openNewWindow = false
        } else {
            decisionHandler(.allow)
            return
        }
    }

    func clearDecisionHandler() {
        if self.currentDecisionHandler != nil {
            self.currentDecisionHandler!(.allow)
            self.currentDecisionHandler = nil
        }
    }

    public func loadUrl(_ url: URL) {
        self.setHeaders(headers: self.plugin.headers!)
        self.webview?.customUserAgent = self.customUserAgent ?? self.userAgent ?? self.originalUserAgent
        self.webview?.load(URLRequest(url: url))
    }

    override public func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey: Any]?, context: UnsafeMutableRawPointer?) {
        super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
    }

    func setHeaders(headers: [String: String]) {
        let userAgent = self.plugin.headers?["User-Agent"]
        self.plugin.headers?.removeValue(forKey: "User-Agent")
        if userAgent != nil {
            self.customUserAgent = userAgent
        }
    }

    func handleError(errorCode: NSNumber?) {
        if plugin.hasEventListeners(eventName: "pageLoadError") {
            plugin.notifyEventListeners(eventName: "pageLoadError", eventValue: [
                "errorCode": errorCode!
            ])
        }

        self.sendLoadingEvent(false)
    }

    func sendLoadingEvent(_ isLoading: Bool) {
        view.isHidden = isLoading
        plugin.notifyEventListeners(eventName: "pageLoaded", eventValue: [
            "isLoading": isLoading
        ])
    }

    internal var customUserAgent: String? {
        didSet {
            guard let agent = userAgent else {
                return
            }
            self.webview?.customUserAgent = agent
        }
    }

    var userAgent: String? {
        didSet {
            guard let originalUserAgent = originalUserAgent, let userAgent = userAgent else {
                return
            }
            self.webview?.customUserAgent = [originalUserAgent, userAgent].joined(separator: " ")
        }
    }

    var pureUserAgent: String? {
        didSet {
            guard let agent = pureUserAgent else {
                return
            }
            self.webview?.customUserAgent = agent
        }
    }
}
