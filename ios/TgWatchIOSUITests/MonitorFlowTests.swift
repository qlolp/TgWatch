import XCTest
final class MonitorFlowTests: XCTestCase {
    func testManualCheckHistoryAndDeleteConfirmation() {
        let app = XCUIApplication()
        app.launchArguments = ["--fixture", "ok", "-AppleLanguages", "(en)"]
        app.launch()
        XCTAssertTrue(app.staticTexts["foregroundExplanation"].exists)
        app.buttons["checkNow"].tap()
        XCTAssertTrue(app.staticTexts["status"].waitForExistence(timeout: 3))
        expectation(for: NSPredicate(format: "label CONTAINS %@", "Available"), evaluatedWith: app.staticTexts["status"])
        waitForExpectations(timeout: 3)
        app.buttons["history"].tap()
        XCTAssertTrue(app.staticTexts["historyEntry"].waitForExistence(timeout: 3))
        app.buttons["clearHistory"].tap()
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.staticTexts["historyEntry"].exists)
    }
    func testCancelDoesNotProduceHealthyStatus() {
        let app = XCUIApplication(); app.launchArguments = ["--fixture", "slow"]
        app.launch(); app.buttons["checkNow"].tap(); app.buttons["cancelCheck"].tap()
        XCTAssertFalse(app.staticTexts["status"].label.contains("Available"))
    }
    func testRussianOfflineFixture() {
        let app = XCUIApplication(); app.launchArguments = ["--fixture", "offline", "-AppleLanguages", "(ru)"]
        app.launch(); app.buttons["checkNow"].tap()
        XCTAssertTrue(app.staticTexts["status"].waitForExistence(timeout: 3))
        let predicate = NSPredicate(format: "label CONTAINS %@", "Нет подключения")
        expectation(for: predicate, evaluatedWith: app.staticTexts["status"])
        waitForExpectations(timeout: 3)
    }
}
