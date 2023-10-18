import Foundation
import Capacitor

typealias JSObject = [String: Any]
typealias JSArray = [JSObject]
private extension BrowserEvent {
    var listenerEvent: String {
        switch self {
        case .loaded:
            return "browserPageLoaded"
        case .finished:
            return "browserFinished"
        }
    }
}

@objc(InAppBrowserPlugin)
public class InAppBrowserPlugin: CAPPlugin {
    var headers: [String: String]?
    var width: CGFloat!
    var height: CGFloat!
    var x: CGFloat!
    var y: CGFloat!
    var hidden: Bool = false
    var inAppBrowser: InAppBrowser?
    var savedCall: CAPPluginCall?
    var observers: [NSObjectProtocol] = []
    let LOG_TAG = "bitburst.inAppBrowser "
    let NO_WEBVIEW_ERROR = "No valid InAppBrowser instance found"
    let INVALID_MISSING_URL_ERROR = "must provide a valid URL to open"
    private let safariBrowser = SafariBrowser()

    @objc func openWebView(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url"), !urlString.isEmpty else {
            call.reject(LOG_TAG, INVALID_MISSING_URL_ERROR)
            return
        }
        let url = URL(string: urlString)
        let webConfiguration = WKWebViewConfiguration()
        self.headers = call.getObject("headers", [:]).mapValues { String(describing: $0 as Any) }
        self.hidden = false
        self.width = CGFloat(call.getFloat("width") ?? 1)
        self.height = CGFloat(call.getFloat("height") ?? 1)
        self.x = CGFloat(call.getFloat("x") ?? 0)
        self.y = CGFloat(call.getFloat("y") ?? 0)
        let rect = CGRect(x: self.x, y: self.y, width: self.width, height: self.height)

        DispatchQueue.main.async {
            self.savedCall = call
            self.inAppBrowser = InAppBrowser(self, configuration: webConfiguration)
            self.inAppBrowser?.view.frame = rect
            self.inAppBrowser?.modalPresentationStyle = .fullScreen
            self.bridge?.viewController?.addChild(self.inAppBrowser!)
            self.bridge?.viewController?.view.addSubview((self.inAppBrowser?.view)!)
            self.inAppBrowser?.view.frame = CGRect(x: self.x, y: self.y, width: self.width, height: self.height)
            self.inAppBrowser?.didMove(toParent: self.bridge?.viewController)

            if self.inAppBrowser != nil {
                self.inAppBrowser?.loadUrl(url!)
            } else {
                self.inAppBrowser?.sendLoadingEvent(false)
                call.reject(self.LOG_TAG, self.NO_WEBVIEW_ERROR)
            }
        }
    }

    @objc func closeWebView(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if self.inAppBrowser != nil {
                self.inAppBrowser?.view.removeFromSuperview()
                self.inAppBrowser?.removeFromParent()
                self.inAppBrowser = nil
                self.hidden = false
            }
            call.resolve()
        }
    }

    @objc func showWebView(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.hidden = false
            if self.inAppBrowser != nil {
                self.inAppBrowser?.view.isHidden = false
            }
            call.resolve()
        }
    }

    @objc func hideWebView(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.hidden = true
            if self.inAppBrowser != nil {
                self.inAppBrowser?.view.isHidden = true
            }
            call.resolve()
        }
    }

    @objc func openBrowser(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url"), let url = URL(string: urlString) else {
            call.reject(LOG_TAG, INVALID_MISSING_URL_ERROR)
            return
        }
        let colorScheme = call.getObject("colorScheme")
        var toolBarColor: String = ""
        if colorScheme != nil {
            toolBarColor = colorScheme?["toolBarColor"] as? String ?? toolBarColor
        }
        let color = UIColor.capacitor.color(fromHex: toolBarColor)
        guard safariBrowser.prepare(for: url, withTint: color, modalPresentation: .fullScreen),
              let viewController = safariBrowser.viewController else {
            call.reject(LOG_TAG, "SafariBrowser failed to prepare.")
            return
        }
        safariBrowser.browserEventDidOccur = { [weak self] (event) in
            self?.notifyListeners("browserVisible", data: ["visible": event != BrowserEvent.finished])
        }
        DispatchQueue.main.async { [weak self] in
            self?.bridge?.presentVC(viewController, animated: true, completion: {
                call.resolve()
            })
        }
    }

    @objc func openSystemBrowser(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url") else {
            call.reject(LOG_TAG, INVALID_MISSING_URL_ERROR)
            return
        }

        guard let url = URL.init(string: urlString) else {
            call.reject(LOG_TAG, INVALID_MISSING_URL_ERROR)
            return
        }

        self.notifyListeners("browserPageLoadStarted", data: [:])

        let activeNotification = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: OperationQueue.main) { [weak self] (_) in
            self?.notifyListeners("browserVisible", data: [
                "visible": false
            ])
        }

        let resignNotification = NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: OperationQueue.main) { [weak self] (_) in
            self?.notifyListeners("browserVisible", data: [
                "visible": true
            ])
        }

        observers.append(activeNotification)
        observers.append(resignNotification)

        DispatchQueue.main.async {
            UIApplication.shared.open(url, options: [:]) { success in
                if success {
                    self.notifyListeners("browserPageLoaded", data: [:])
                } else {
                    self.notifyListeners("browserPageNavigationFailed", data: [:])
                }
                call.resolve()
                self.observers.removeAll()
            }
        }
    }

    @objc func navigateBack(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if self.inAppBrowser != nil {
                self.inAppBrowser?.webview?.goBack()
                call.resolve()
            }
        }
    }

    @objc func navigateForward(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if self.inAppBrowser != nil {
                self.inAppBrowser?.webview?.goForward()
                call.resolve()
            }
        }
    }

    @objc func refresh(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if self.inAppBrowser != nil {
                self.inAppBrowser?.webview?.reload()
                call.resolve()
            }
        }
    }

    @objc func loadUrl(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if self.inAppBrowser != nil {
                let url = call.getString("url") ?? ""
                self.savedCall = call
                self.inAppBrowser?.loadUrl(URL(string: url)!)
            }
        }
    }

    @objc func onNavigation(_ call: CAPPluginCall) {
        if self.inAppBrowser != nil && self.inAppBrowser?.currentDecisionHandler != nil {
            if call.getBool("allow") ?? true {
                self.inAppBrowser?.currentDecisionHandler!(.allow)
            } else {
                self.inAppBrowser?.currentDecisionHandler!(.cancel)
                self.inAppBrowser?.sendLoadingEvent(false)

            }
            self.inAppBrowser?.currentDecisionHandler = nil
            call.resolve()
        }
    }

    @objc func updateDimensions(_ call: CAPPluginCall) {
        if let inAppBrowser = self.inAppBrowser {
            DispatchQueue.main.async {
                self.width = CGFloat(call.getFloat("width") ?? 0)
                self.height = CGFloat(call.getFloat("height") ?? 0)
                self.x = CGFloat(call.getFloat("x") ?? 0)
                self.y = CGFloat(call.getFloat("y") ?? 0)

                let rect = CGRect(x: self.x, y: self.y, width: self.width, height: self.height)
                inAppBrowser.view.frame = rect
                if self.hidden {
                   // self.notifyListeners("captureScreen", data: [:])
                }
                call.resolve()
            }
        }
    }

    @objc func captureScreen(_ call: CAPPluginCall) {
        guard let inAppBrowser = self.inAppBrowser,
              let webview = inAppBrowser.webview else {
            call.resolve(["src": ""])
            return
        }

        DispatchQueue.main.async {
            let offset: CGPoint = webview.scrollView.contentOffset
            webview.scrollView.setContentOffset(offset, animated: false)

            webview.takeSnapshot(with: nil) {image, _ in
                if let image = image {
                    guard let jpeg = image.jpegData(compressionQuality: 1) else {
                        return
                    }
                    let base64String = jpeg.base64EncodedString()
                    call.resolve(["src": base64String])
                } else {
                    call.resolve(["src": ""])
                }
            }
        }
    }

    @objc func hasEventListeners(eventName: String) -> Bool {
        hasListeners(eventName)
    }

    @objc func notifyEventListeners(eventName: String, eventValue: JSObject) {
        notifyListeners(eventName, data: eventValue, retainUntilConsumed: eventName == "pageLoaded" ? true : false)
    }
}
