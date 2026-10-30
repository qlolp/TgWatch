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
        let monitorScreenshot = XCTAttachment(screenshot: app.screenshot())
        monitorScreenshot.name = "Monitor fixture"; monitorScreenshot.lifetime = .keepAlways; add(monitorScreenshot)
        app.buttons["history"].tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "historyEntry").firstMatch.waitForExistence(timeout: 3))
        let historyScreenshot = XCTAttachment(screenshot: app.screenshot())
        historyScreenshot.name = "History fixture"; historyScreenshot.lifetime = .keepAlways; add(historyScreenshot)
        app.buttons["clearHistory"].tap()
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "historyEntry").firstMatch.exists)
    }
    func testCancelDoesNotProduceHealthyStatus() {
        let app = XCUIApplication(); app.launchArguments = ["--fixture", "slow"]
        app.launch(); app.buttons["checkNow"].tap(); app.buttons["cancelCheck"].tap()
        XCTAssertFalse(app.staticTexts["status"].label.contains("Available"))
    }
    func testLargestTextKeepsHistoryAndCheckReachable() {
        let app = XCUIApplication()
        app.launchArguments = ["--fixture", "ok", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        XCTAssertTrue(app.buttons["history"].isHittable)
        for _ in 0..<5 {
            if app.buttons["checkNow"].isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(app.buttons["checkNow"].isHittable)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Largest Dynamic Type"; screenshot.lifetime = .keepAlways; add(screenshot)
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
