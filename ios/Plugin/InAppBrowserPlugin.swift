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
    @MainActor func makeBrowser(headers: [String: String]) -> InAppBrowser {
        InAppBrowser(self, configuration: WKWebViewConfiguration(), headers: headers)
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
            guard self.inAppBrowser == nil else { response.reject("BUSY", "Close the existing WebView before opening another."); return }
            let browser = self.makeBrowser(headers: headers)
            self.inAppBrowser = browser
            do { try self.attachBrowser(browser, bounds: bounds) }
            catch {
                self.closeInternal("NATIVE_ERROR", "Could not attach the WebView.")
                response.reject("NATIVE_ERROR", "Could not attach the WebView.")
                return
            }
            self.startLoad(response, browser: browser, url: url)
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
            self.startLoad(response, browser: browser, url: try BrowserInput.url(call))
        }
    }

    @objc func updateDimensions(_ call: CAPPluginCall) {
        withBrowser(call) { browser, response in browser.view.frame = try BrowserInput.bounds(call); response.resolve() }
    }

    @objc func onNavigation(_ call: CAPPluginCall) {
        withBrowser(call) { browser, response in
            guard let allow = call.getBool("allow") else { response.reject("INVALID_ARGUMENT", "A boolean allow value is required."); return }
            guard browser.currentDecisionHandler != nil else { response.reject("NO_PENDING_NAVIGATION", "No navigation decision is pending."); return }
            browser.completeDecision(allow ? .allow : .cancel)
            response.resolve()
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

    @MainActor func hasEventListeners(eventName: String) -> Bool { hasListeners(eventName) }
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
