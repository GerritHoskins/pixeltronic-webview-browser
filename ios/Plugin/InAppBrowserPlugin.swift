import Foundation
import CoreFoundation
import WebKit
import Capacitor
import ImageIO
import UniformTypeIdentifiers

// Every response is owned by one operation and consumed on the main queue.
final class BrowserResponse {
    private var call: CAPPluginCall?

    init(_ call: CAPPluginCall) { self.call = call }

    func resolve(_ data: PluginCallResultData = [:]) {
        precondition(Thread.isMainThread)
        let pending = call
        call = nil
        pending?.resolve(data)
    }

    func reject(_ code: String, _ message: String) {
        precondition(Thread.isMainThread)
        let pending = call
        call = nil
        pending?.reject(message, code)
    }
}

struct BrowserInputError: Error {
    let message: String
}

enum BrowserInput {
    static func url(_ call: CAPPluginCall) throws -> URL {
        guard let value = call.getString("url"), !value.isEmpty,
              value.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
              let components = URLComponents(string: value), let scheme = components.scheme,
              let url = components.url,
              !["http", "https"].contains(scheme.lowercased()) || !(components.host ?? "").isEmpty else {
            throw BrowserInputError(message: "An absolute URL without whitespace is required.")
        }
        return url
    }

    static func bounds(_ call: CAPPluginCall) throws -> CGRect {
        func number(_ key: String) throws -> Double {
            guard let value = call.options[key] as? NSNumber,
                  CFGetTypeID(value) != CFBooleanGetTypeID(), value.doubleValue.isFinite else {
                throw BrowserInputError(message: "Finite x/y and positive width/height are required.")
            }
            return value.doubleValue
        }
        let width = try number("width"), height = try number("height")
        let x = try number("x"), y = try number("y")
        guard width > 0, height > 0 else {
            throw BrowserInputError(message: "Positive width/height are required.")
        }
        return CGRect(x: x, y: y, width: width, height: height)
    }

    static func headers(_ call: CAPPluginCall) throws -> [String: String] {
        guard let raw = call.options["headers"] else { return [:] }
        guard let headers = raw as? [String: String], headers.allSatisfy({ name, value in
            name.range(of: "^[!#$%&'*+.^_`|~0-9A-Za-z-]+$", options: .regularExpression) != nil &&
                !value.contains("\r") && !value.contains("\n")
        }) else { throw BrowserInputError(message: "Invalid header names or values.") }
        return headers
    }
}

/// Immutable document-navigation rules; not a firewall for page subresources or network traffic.
struct BrowserNavigationPolicy {
    struct Decision {
        let allowed: Bool
        let reason: String
    }
    private let origins: Set<String>
    private let allowHttp: Bool

    init(initialURL: URL, allowedOrigins: [String]? = nil, allowHttp: Bool = false) throws {
        self.allowHttp = allowHttp
        if let values = allowedOrigins {
            guard !values.isEmpty else { throw BrowserInputError(message: "allowedOrigins must not be empty.") }
            var normalized = Set<String>()
            for value in values {
                guard value.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
                      let components = URLComponents(string: value), let url = components.url,
                      let origin = Self.origin(url), components.query == nil, components.fragment == nil,
                      components.path.isEmpty || components.path == "/" else {
                    throw BrowserInputError(message: "allowedOrigins must contain exact HTTP(S) origins without paths, credentials, queries or fragments.")
                }
                normalized.insert(origin)
            }
            origins = normalized
        } else {
            origins = Self.origin(initialURL).map { Set([$0]) } ?? []
        }
    }

    static func from(_ call: CAPPluginCall, initialURL: URL) throws -> BrowserNavigationPolicy {
        guard let raw = call.options["navigationPolicy"] else { return try BrowserNavigationPolicy(initialURL: initialURL) }
        guard let policy = raw as? [String: Any] else { throw BrowserInputError(message: "navigationPolicy must be an object.") }
        var http = false
        if let rawHttp = policy["allowHttp"] {
            guard let value = rawHttp as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else {
                throw BrowserInputError(message: "allowHttp must be a boolean.")
            }
            http = value.boolValue
        }
        var origins: [String]?
        if let rawOrigins = policy["allowedOrigins"] {
            guard let values = rawOrigins as? [String] else { throw BrowserInputError(message: "allowedOrigins must be an array of strings.") }
            origins = values
        }
        return try BrowserNavigationPolicy(initialURL: initialURL, allowedOrigins: origins, allowHttp: http)
    }

    func evaluate(_ url: URL?) -> Decision {
        guard let url = url, let scheme = url.scheme?.lowercased() else { return Decision(allowed: false, reason: "INVALID_URL") }
        guard scheme == "https" || scheme == "http" else { return Decision(allowed: false, reason: "SCHEME_BLOCKED") }
        guard url.user == nil && url.password == nil else { return Decision(allowed: false, reason: "CREDENTIALS_BLOCKED") }
        guard let origin = Self.origin(url) else { return Decision(allowed: false, reason: "INVALID_URL") }
        guard scheme != "http" || allowHttp else { return Decision(allowed: false, reason: "HTTPS_REQUIRED") }
        return origins.contains(origin) ? Decision(allowed: true, reason: "ALLOWED") : Decision(allowed: false, reason: "ORIGIN_BLOCKED")
    }

    static let popupBlocked = Decision(allowed: false, reason: "POPUP_BLOCKED")

    static func origin(_ url: URL) -> String? {
        guard let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
              url.user == nil, url.password == nil, var host = url.host?.lowercased(),
              !host.isEmpty, !host.contains("*"), host.unicodeScalars.allSatisfy({ $0.isASCII }) else { return nil }
        if let port = url.port, port < 1 || port > 65535 { return nil }
        if host.contains(":") && !host.hasPrefix("[") { host = "[\(host)]" }
        if let port = url.port, !((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) {
            return "\(scheme)://\(host):\(port)"
        }
        return "\(scheme)://\(host)"
    }
}


@objc(InAppBrowserPlugin)
public class InAppBrowserPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "InAppBrowserPlugin"
    public let jsName = "InAppBrowserPlugin"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "openWebView", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "closeWebView", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "showWebView", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "hideWebView", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "navigateBack", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "navigateForward", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "refresh", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "loadUrl", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "onNavigation", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "updateDimensions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "captureScreen", returnType: CAPPluginReturnPromise)
    ]

    private(set) var inAppBrowser: InAppBrowser?
    private var destroyed = false
    private var pendingLoad: BrowserResponse?
    private var pendingNavigation: WKNavigation?
    private var loadTimeout: DispatchWorkItem?
    private var captures: [UUID: BrowserResponse] = [:]
    private var captureTimeouts: [UUID: DispatchWorkItem] = [:]
    var timeoutInterval: TimeInterval = 30

    // These hooks keep native integration testable without network traffic.
    @MainActor func makeBrowser(headers: [String: String], policy: BrowserNavigationPolicy) -> InAppBrowser {
        InAppBrowser(self, configuration: WKWebViewConfiguration(), headers: headers, policy: policy)
    }

    @MainActor func attachBrowser(_ browser: InAppBrowser, bounds: CGRect) throws {
        guard let parent = bridge?.viewController else {
            throw BrowserInputError(message: "The host view controller is unavailable.")
        }
        parent.addChild(browser)
        browser.view.frame = bounds
        parent.view.addSubview(browser.view)
        browser.didMove(toParent: parent)
    }

    private func onMain(_ action: @escaping @MainActor () -> Void) {
        DispatchQueue.main.async { action() }
    }

    private func execute(_ call: CAPPluginCall, _ action: @escaping @MainActor (BrowserResponse) throws -> Void) {
        onMain {
            let response = BrowserResponse(call)
            guard !self.destroyed else { response.reject("DESTROYED", "The plugin was destroyed."); return }
            do { try action(response) }
            catch let error as BrowserInputError { response.reject("INVALID_ARGUMENT", error.message) }
            catch { response.reject("NATIVE_ERROR", "The native operation failed.") }
        }
    }

    private func withBrowser(_ call: CAPPluginCall, idle: Bool = false,
                             _ action: @escaping @MainActor (InAppBrowser, BrowserResponse) throws -> Void) {
        execute(call) { response in
            guard let browser = self.inAppBrowser else { response.reject("NOT_OPEN", "No WebView is open."); return }
            guard !idle || (self.pendingLoad == nil && self.captures.isEmpty) else { response.reject("BUSY", "A load or screenshot is in progress."); return }
            try action(browser, response)
        }
    }

    @objc func openWebView(_ call: CAPPluginCall) {
        execute(call) { response in
            let url = try BrowserInput.url(call)
            let bounds = try BrowserInput.bounds(call)
            let headers = try BrowserInput.headers(call)
            let policy = try BrowserNavigationPolicy.from(call, initialURL: url)
            let decision = policy.evaluate(url)
            guard decision.allowed else { response.reject("POLICY_BLOCKED", decision.reason); return }
            guard self.inAppBrowser == nil else { response.reject("BUSY", "Close the existing WebView before opening another."); return }
            let browser = self.makeBrowser(headers: headers, policy: policy)
            self.inAppBrowser = browser
            do { try self.attachBrowser(browser, bounds: bounds) }
            catch {
                self.closeInternal("NATIVE_ERROR", "Could not attach the WebView.")
                response.reject("NATIVE_ERROR", "Could not attach the WebView.")
                return
            }
            self.startLoad(response, browser: browser, url: url)
            self.recordNavigation(browser, url: url, popup: false, mainFrame: true, decision: decision)
        }
    }

    @objc func closeWebView(_ call: CAPPluginCall) {
        onMain {
            self.closeInternal("CANCELLED", "The WebView was closed.")
            BrowserResponse(call).resolve()
        }
    }

    @objc func showWebView(_ call: CAPPluginCall) {
        withBrowser(call) { browser, response in browser.hidden = false; browser.updateVisibility(); response.resolve() }
    }

    @objc func hideWebView(_ call: CAPPluginCall) {
        withBrowser(call) { browser, response in browser.hidden = true; browser.updateVisibility(); response.resolve() }
    }

    @objc func navigateBack(_ call: CAPPluginCall) {
        withBrowser(call, idle: true) { browser, response in
            if browser.webview?.canGoBack == true { browser.webview?.goBack() }
            response.resolve()
        }
    }

    @objc func navigateForward(_ call: CAPPluginCall) {
        withBrowser(call, idle: true) { browser, response in
            if browser.webview?.canGoForward == true { browser.webview?.goForward() }
            response.resolve()
        }
    }

    @objc func refresh(_ call: CAPPluginCall) {
        withBrowser(call, idle: true) { browser, response in browser.webview?.reload(); response.resolve() }
    }

    @objc func loadUrl(_ call: CAPPluginCall) {
        withBrowser(call, idle: true) { browser, response in
            let url = try BrowserInput.url(call)
            let decision = browser.navigationPolicy.evaluate(url)
            self.recordNavigation(browser, url: url, popup: false, mainFrame: true, decision: decision)
            guard decision.allowed else { response.reject("POLICY_BLOCKED", decision.reason); return }
            self.startLoad(response, browser: browser, url: url)
        }
    }

    @objc func updateDimensions(_ call: CAPPluginCall) {
        withBrowser(call) { browser, response in browser.view.frame = try BrowserInput.bounds(call); response.resolve() }
    }

    @objc func onNavigation(_ call: CAPPluginCall) {
        withBrowser(call) { _, response in
            response.reject("UNIMPLEMENTED", "Navigation decisions are applied natively. Configure navigationPolicy before opening the WebView.")
        }
    }

    @MainActor func recordNavigation(_ browser: InAppBrowser, url: URL?, popup: Bool, mainFrame: Bool,
                                    decision: BrowserNavigationPolicy.Decision, late: Bool = false) {
        guard browser === inAppBrowser else { return }
        let sourceHost = browser.webview?.url?.host?.lowercased()
        notifyEventListeners(eventName: "navigationHandler", eventValue: [
            "url": url?.absoluteString ?? "", "newWindowRequest": popup, "isMainFrame": mainFrame,
            "isSameHost": sourceHost != nil && sourceHost == url?.host?.lowercased(),
            "allowed": decision.allowed, "reason": decision.reason
        ])
        if !decision.allowed && !popup && mainFrame && (pendingLoad != nil || late) {
            closeInternal("NAVIGATION_BLOCKED", "Navigation was blocked by policy: \(decision.reason)")
            notifyEventListeners(eventName: "browserClosed", eventValue: ["reason": "NAVIGATION_BLOCKED"])
        }
    }

    @MainActor private func startLoad(_ response: BrowserResponse, browser: InAppBrowser, url: URL) {
        pendingLoad = response
        guard let navigation = browser.loadUrl(url) else {
            closeInternal("LOAD_FAILED", "WebKit could not start the navigation.")
            return
        }
        pendingNavigation = navigation
        let work = DispatchWorkItem { [weak self, weak browser] in
            DispatchQueue.main.async {
                guard let self = self, self.inAppBrowser === browser, self.pendingLoad === response else { return }
                self.closeInternal("LOAD_TIMEOUT", "Page loading timed out.")
            }
        }
        loadTimeout = work
        DispatchQueue.main.asyncAfter(deadline: .now() + timeoutInterval, execute: work)
    }

    @MainActor func navigationFinished(_ browser: InAppBrowser, navigation: WKNavigation?) {
        guard browser === inAppBrowser, let navigation = navigation, navigation === pendingNavigation else { return }
        let response = pendingLoad
        clearPendingLoad()
        response?.resolve()
    }

    @MainActor func navigationFailed(_ browser: InAppBrowser, navigation: WKNavigation?, error: Error) {
        guard browser === inAppBrowser else { return }
        if pendingLoad != nil && (navigation == nil || navigation === pendingNavigation) {
            closeInternal("LOAD_FAILED", "The page failed to load.")
        }
        notifyEventListeners(eventName: "pageLoadError", eventValue: ["errorCode": (error as NSError).code])
    }

    @MainActor func contentProcessTerminated(_ browser: InAppBrowser) {
        guard browser === inAppBrowser else { return }
        if pendingLoad != nil {
            closeInternal("LOAD_FAILED", "The WebView content process terminated.")
        } else {
            browser.sendLoadingEvent(false)
        }
        notifyEventListeners(eventName: "pageLoadError", eventValue: ["errorCode": -1])
    }

    @objc func captureScreen(_ call: CAPPluginCall) {
        withBrowser(call, idle: true) { browser, response in
            guard self.captures.isEmpty else { response.reject("BUSY", "A screenshot is in progress."); return }
            guard browser.view.bounds.width > 0, browser.view.bounds.height > 0 else {
                response.reject("INVALID_ARGUMENT", "The WebView has no drawable bounds."); return
            }
            let id = UUID()
            self.captures[id] = response
            let timeout = DispatchWorkItem { [weak self] in
                DispatchQueue.main.async { self?.finishCapture(id, data: nil, error: "Screenshot timed out.") }
            }
            self.captureTimeouts[id] = timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + self.timeoutInterval, execute: timeout)
            browser.snapshot { [weak self] image, error in
                guard error == nil, let image = image?.cgImage else {
                    self?.onMain { self?.finishCapture(id, data: nil, error: "WebKit could not capture the view.") }
                    return
                }
                DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                    var encoded: String?
                    if let data = CFDataCreateMutable(nil, 0),
                       let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) {
                        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 1] as CFDictionary)
                        if CGImageDestinationFinalize(destination) { encoded = (data as Data).base64EncodedString() }
                    }
                    let result = encoded
                    self?.onMain { self?.finishCapture(id, data: result, error: result == nil ? "JPEG encoding failed." : nil) }
                }
            }
        }
    }

    @MainActor private func finishCapture(_ id: UUID, data: String?, error: String?) {
        captureTimeouts.removeValue(forKey: id)?.cancel()
        guard let response = captures.removeValue(forKey: id) else { return }
        if let data = data { response.resolve(["src": data]) }
        else { response.reject("SNAPSHOT_FAILED", error ?? "Screenshot failed.") }
    }

    @MainActor private func clearPendingLoad() {
        loadTimeout?.cancel()
        loadTimeout = nil
        pendingLoad = nil
        pendingNavigation = nil
    }

    @MainActor private func closeInternal(_ code: String, _ message: String) {
        let browser = inAppBrowser
        inAppBrowser = nil
        let pending = pendingLoad
        clearPendingLoad()
        pending?.reject(code, message)
        for work in captureTimeouts.values { work.cancel() }
        captureTimeouts.removeAll()
        for response in captures.values { response.reject(code, message) }
        captures.removeAll()
        browser?.dispose()
    }

    @MainActor func tearDownBrowser() {
        destroyed = true
        closeInternal("CANCELLED", "The plugin was destroyed.")
    }

    @MainActor func notifyEventListeners(eventName: String, eventValue: [String: Any]) {
        notifyListeners(eventName, data: eventValue, retainUntilConsumed: eventName == "pageLoaded")
    }

    deinit {
        loadTimeout?.cancel()
        for work in captureTimeouts.values { work.cancel() }
        let browser = inAppBrowser
        let responses = Array(captures.values) + (pendingLoad.map { [$0] } ?? [])
        DispatchQueue.main.async {
            for response in responses { response.reject("CANCELLED", "The plugin was destroyed.") }
            browser?.dispose()
        }
    }
}
