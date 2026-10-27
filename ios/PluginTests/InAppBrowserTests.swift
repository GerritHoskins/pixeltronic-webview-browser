import XCTest
import UIKit
import WebKit
import Capacitor
#if SWIFT_PACKAGE
@testable import PixeltronicWebviewBrowser
#else
@testable import Plugin
#endif

@MainActor
final class InAppBrowserTests: XCTestCase {
    private final class Record {
        var successes = 0
        var errors: [String] = []
        var settled: (() -> Void)?

        func call(_ options: [String: Any] = [:]) -> CAPPluginCall {
            CAPPluginCall(callbackId: UUID().uuidString, methodName: "test", options: options, success: { _, _ in
                XCTAssertTrue(Thread.isMainThread)
                self.successes += 1
                self.settled?()
            }, error: { error in
                XCTAssertTrue(Thread.isMainThread)
                self.errors.append(error?.code ?? "missing-code")
                self.settled?()
            })
        }
    }

    private final class Browser: InAppBrowser {
        var navigation: WKNavigation?
        var snapshotCallback: ((UIImage?, Error?) -> Void)?
        var disposed = false

        override func loadUrl(_ url: URL) -> WKNavigation? {
            XCTAssertTrue(Thread.isMainThread)
            loadViewIfNeeded()
            // Real WebKit navigation identity, without network or automatic delegate callbacks.
            webview?.navigationDelegate = nil
            navigation = webview?.loadHTMLString("<html><body>fixture</body></html>", baseURL: nil)
            return navigation
        }
        override func snapshot(_ completion: @escaping (UIImage?, Error?) -> Void) {
            XCTAssertTrue(Thread.isMainThread)
            snapshotCallback = completion
        }
        override func dispose() { disposed = true; super.dispose() }
    }

    private final class PluginHarness: InAppBrowserPlugin {
        var browser: Browser?
        override func makeBrowser(headers: [String: String]) -> InAppBrowser {
            XCTAssertTrue(Thread.isMainThread)
            let browser = Browser(self, configuration: WKWebViewConfiguration(), headers: headers)
            self.browser = browser
            return browser
        }
        override func attachBrowser(_ browser: InAppBrowser, bounds: CGRect) throws {
            XCTAssertTrue(Thread.isMainThread)
            browser.view.frame = bounds
        }
    }

    private var options: [String: Any] {
        ["url": "https://example.com", "width": 300, "height": 400, "x": -5, "y": 10]
    }

    private func drain() async {
        await withCheckedContinuation { continuation in
            DispatchQueue.main.async { continuation.resume() }
        }
    }

    private func open(_ plugin: PluginHarness) async -> Record {
        let record = Record()
        plugin.openWebView(record.call(options))
        await drain()
        XCTAssertNotNil(plugin.browser?.navigation)
        return record
    }

    private func finish(_ plugin: PluginHarness) {
        guard let browser = plugin.browser else { XCTFail("No browser"); return }
        plugin.navigationFinished(browser, navigation: browser.navigation)
    }

    func testCommandsBeforeOpenRejectAndRepeatedCloseSucceeds() async {
        let plugin = PluginHarness()
        let commands: [(CAPPluginCall) -> Void] = [plugin.navigateBack, plugin.navigateForward, plugin.refresh,
            plugin.showWebView, plugin.hideWebView, plugin.loadUrl, plugin.updateDimensions,
            plugin.captureScreen, plugin.onNavigation]
        for command in commands {
            let record = Record(); command(record.call(options)); await drain()
            XCTAssertEqual(record.errors, ["NOT_OPEN"]); XCTAssertEqual(record.successes, 0)
        }
        let close = Record()
        plugin.closeWebView(close.call()); plugin.closeWebView(close.call()); await drain()
        XCTAssertEqual(close.successes, 2); XCTAssertTrue(close.errors.isEmpty)
    }

    func testMalformedInputsDoNotCreateAView() async {
        let plugin = PluginHarness()
        let invalidInputs: [[String: Any]] = [["url": "relative"], ["url": "https://"], ["width": 0],
            ["width": true], ["height": Double.infinity], ["x": "12"], ["headers": ["X-Test": 1]],
            ["headers": ["X-Test": "x\r\ny"]]]
        for replacement in invalidInputs {
            let record = Record()
            var invalid = options
            invalid.merge(replacement) { _, new in new }
            plugin.openWebView(record.call(invalid)); await drain()
            XCTAssertEqual(record.errors, ["INVALID_ARGUMENT"])
            XCTAssertNil(plugin.browser)
        }
    }

    func testOpenCompletesOnceAndRejectsOverlappingLoads() async {
        let plugin = PluginHarness()
        let initial = await open(plugin)
        XCTAssertEqual(initial.successes, 0)
        let duplicate = Record(), load = Record()
        plugin.openWebView(duplicate.call(options)); plugin.loadUrl(load.call(options)); await drain()
        XCTAssertEqual(duplicate.errors, ["BUSY"]); XCTAssertEqual(load.errors, ["BUSY"])
        finish(plugin); finish(plugin)
        XCTAssertEqual(initial.successes, 1); XCTAssertTrue(initial.errors.isEmpty)
        plugin.tearDownBrowser()
    }

    func testCloseDuringOpenCancelsAndLateCallbacksAreIgnored() async {
        let plugin = PluginHarness()
        let initial = await open(plugin)
        let browser = plugin.browser
        let close = Record()
        plugin.closeWebView(close.call()); await drain()
        XCTAssertEqual(initial.errors, ["CANCELLED"]); XCTAssertEqual(close.successes, 1)
        XCTAssertTrue(browser?.disposed == true); XCTAssertNil(browser?.plugin)
        if let browser = browser { plugin.navigationFinished(browser, navigation: browser.navigation) }
        XCTAssertEqual(initial.successes, 0); XCTAssertEqual(initial.errors.count, 1)
    }

    func testFailedLoadRejectsAndAllowsFreshOpening() async {
        let plugin = PluginHarness()
        let initial = await open(plugin)
        let browser = plugin.browser!
        plugin.navigationFailed(browser, navigation: browser.navigation, error: NSError(domain: NSURLErrorDomain, code: -1009))
        plugin.navigationFinished(browser, navigation: browser.navigation)
        XCTAssertEqual(initial.errors, ["LOAD_FAILED"]); XCTAssertEqual(initial.successes, 0)
        XCTAssertNil(plugin.inAppBrowser)
        let retry = await open(plugin); finish(plugin)
        XCTAssertEqual(retry.successes, 1)
        plugin.tearDownBrowser()
    }

    func testInvalidLoadAndResizeDoNotDestroyAnExistingView() async {
        let plugin = PluginHarness()
        _ = await open(plugin); finish(plugin)
        let invalid = Record(), resize = Record()
        plugin.loadUrl(invalid.call(["url": "relative"]))
        plugin.updateDimensions(resize.call(["width": 1]))
        await drain()
        XCTAssertEqual(invalid.errors, ["INVALID_ARGUMENT"]); XCTAssertEqual(resize.errors, ["INVALID_ARGUMENT"])
        XCTAssertNotNil(plugin.inAppBrowser); XCTAssertFalse(plugin.browser!.disposed)
        plugin.tearDownBrowser()
    }

    func testContentProcessExitCancelsPendingLoadButKeepsAnIdleViewAvailableForReload() async {
        let plugin = PluginHarness()
        let initial = await open(plugin)
        plugin.contentProcessTerminated(plugin.browser!)
        XCTAssertEqual(initial.errors, ["LOAD_FAILED"])
        XCTAssertNil(plugin.inAppBrowser)
        _ = await open(plugin); finish(plugin)
        plugin.contentProcessTerminated(plugin.browser!)
        XCTAssertNotNil(plugin.inAppBrowser)
        plugin.tearDownBrowser()
    }

    func testLoadTimeoutRejectsAndDisposesTheView() async {
        let plugin = PluginHarness()
        plugin.timeoutInterval = 0.01
        let record = Record(), finished = expectation(description: "load timeout")
        record.settled = { finished.fulfill() }
        plugin.openWebView(record.call(options))
        await fulfillment(of: [finished], timeout: 2)
        XCTAssertEqual(record.errors, ["LOAD_TIMEOUT"]); XCTAssertTrue(plugin.browser!.disposed)
        plugin.tearDownBrowser()
    }

    func testTeardownRejectsPendingLoadAndFutureOperations() async {
        let plugin = PluginHarness()
        let initial = await open(plugin)
        plugin.tearDownBrowser()
        XCTAssertEqual(initial.errors, ["CANCELLED"])
        let after = Record(); plugin.openWebView(after.call(options)); await drain()
        XCTAssertEqual(after.errors, ["DESTROYED"])
        let close = Record(); plugin.closeWebView(close.call()); await drain()
        XCTAssertEqual(close.successes, 1)
    }

    func testScreenshotFailureSettlesOnceEvenWithDuplicateCompletion() async {
        let plugin = PluginHarness()
        _ = await open(plugin); finish(plugin)
        let snapshot = Record(); plugin.captureScreen(snapshot.call()); await drain()
        let callback = plugin.browser!.snapshotCallback
        callback?(nil, NSError(domain: "test", code: 1)); callback?(nil, nil); await drain()
        XCTAssertEqual(snapshot.errors, ["SNAPSHOT_FAILED"]); XCTAssertEqual(snapshot.successes, 0)
        plugin.tearDownBrowser()
    }

    func testCloseCancelsScreenshotAndIgnoresLateCapture() async {
        let plugin = PluginHarness()
        _ = await open(plugin); finish(plugin)
        let snapshot = Record(); plugin.captureScreen(snapshot.call()); await drain()
        let callback = plugin.browser!.snapshotCallback
        let close = Record(); plugin.closeWebView(close.call()); await drain()
        callback?(nil, nil); await drain()
        XCTAssertEqual(snapshot.errors, ["CANCELLED"]); XCTAssertEqual(close.successes, 1)
    }

    func testBackgroundBridgeInvocationSettlesOnMainThread() async {
        let plugin = PluginHarness(), record = Record()
        let finished = expectation(description: "main response")
        record.settled = { finished.fulfill() }
        let call = record.call()
        DispatchQueue.global().async { plugin.navigateBack(call) }
        await fulfillment(of: [finished], timeout: 2)
        XCTAssertEqual(record.errors, ["NOT_OPEN"])
    }

    func testResponseCannotResolveAfterCancellation() {
        let record = Record()
        let owned = BrowserResponse(record.call())
        owned.reject("CANCELLED", "closed"); owned.resolve(); owned.reject("LOAD_FAILED", "late")
        XCTAssertEqual(record.errors, ["CANCELLED"]); XCTAssertEqual(record.successes, 0)
    }
}
