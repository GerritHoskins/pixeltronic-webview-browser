import XCTest
import WebKit
@testable import Plugin

final class InAppBrowserTests: XCTestCase {
    func testBrowserLoadsWebView() {
        let browser = InAppBrowser(InAppBrowserPlugin(), configuration: WKWebViewConfiguration())
        browser.loadViewIfNeeded()
        XCTAssertNotNil(browser.webview)
    }
}
