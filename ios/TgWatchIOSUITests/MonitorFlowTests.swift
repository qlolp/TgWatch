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
        for _ in 0..<8 {
            if app.descendants(matching: .any).matching(identifier: "historyEntry").firstMatch.exists { break }
            app.swipeUp()
        }
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "historyEntry").firstMatch.waitForExistence(timeout: 3))
        let historyScreenshot = XCTAttachment(screenshot: app.screenshot())
        historyScreenshot.name = "History fixture"; historyScreenshot.lifetime = .keepAlways; add(historyScreenshot)
        app.buttons["clearHistory"].tap()
        app.alerts.buttons["Cancel"].tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "historyEntry").firstMatch.exists)
        app.buttons["clearHistory"].tap()
        let confirmScreenshot = XCTAttachment(screenshot: app.screenshot())
        confirmScreenshot.name = "Explicit delete confirmation"; confirmScreenshot.lifetime = .keepAlways; add(confirmScreenshot)
        app.alerts.buttons["Delete history"].tap()
        XCTAssertTrue(app.staticTexts["historyEmpty"].waitForExistence(timeout: 5))
    }
    func testServicePreferencesSurviveRelaunch() {
        let app=XCUIApplication(), id=UUID().uuidString
        app.launchArguments=["--fixture","ok","--fixture-store",id,"-AppleLanguages","(en)"]
        app.launch(); openServices(app)
        app.segmentedControls["serviceTarget"].buttons["WhatsApp Web"].tap()
        for _ in 0..<8 { if app.buttons["serviceInterval"].isHittable { break }; app.swipeUp() }
        app.buttons["serviceInterval"].tap(); app.buttons["300 seconds"].tap()
        for _ in 0..<8 { if app.switches["serviceAutomatic"].isHittable { break }; app.swipeUp() }
        app.switches["serviceAutomatic"].tap()
        app.terminate(); app.launch(); openServices(app)
        XCTAssertTrue(app.segmentedControls["serviceTarget"].buttons["WhatsApp Web"].isSelected)
        for _ in 0..<8 { if app.switches["serviceAutomatic"].isHittable { break }; app.swipeUp() }
        XCTAssertEqual(app.switches["serviceAutomatic"].value as? String,"1")
        let interval=app.buttons["serviceInterval"]
        XCTAssertTrue((interval.label + " " + String(describing:interval.value)).contains("300"))
        let shot=XCTAttachment(screenshot:app.screenshot()); shot.name="Restored service preferences"; shot.lifetime = .keepAlways; add(shot)
    }
    func testCorruptServicePreferencesUseDefaultsAndExplicitReset() {
        let app=XCUIApplication(); app.launchArguments=["--fixture","settings-corrupt","--fixture-store",UUID().uuidString,"-AppleLanguages","(en)"]
        app.launch(); openServices(app)
        XCTAssertTrue(app.staticTexts["serviceSettingsFailure"].waitForExistence(timeout:3))
        app.buttons["resetServiceSettings"].tap()
        app.alerts.buttons["Reset service settings"].tap()
        XCTAssertFalse(app.staticTexts["serviceSettingsFailure"].exists)
        app.terminate(); app.launch(); openServices(app)
        XCTAssertFalse(app.staticTexts["serviceSettingsFailure"].exists)
        XCTAssertTrue(app.segmentedControls["serviceTarget"].buttons["Matrix"].isSelected)
        for _ in 0..<8 { if app.switches["serviceAutomatic"].isHittable { break }; app.swipeUp() }
        XCTAssertEqual(app.switches["serviceAutomatic"].value as? String,"0")
    }
    private func openServices(_ app: XCUIApplication) {
        for _ in 0..<8 { if app.buttons["serviceChecks"].isHittable { break }; app.swipeUp() }
        app.buttons["serviceChecks"].tap()
    }
    func testAdditionalServiceCheckHasOwnHistory() {
        let app=XCUIApplication(); app.launchArguments=["--fixture","ok","-AppleLanguages","(en)"]
        app.launch()
        for _ in 0..<6 { if app.buttons["serviceChecks"].isHittable { break }; app.swipeUp() }
        app.buttons["serviceChecks"].tap(); app.buttons["checkService"].tap()
        XCTAssertTrue(app.staticTexts["Matrix versions response confirmed"].waitForExistence(timeout:5))
        app.buttons["serviceHistory"].tap()
        XCTAssertTrue(app.buttons["exportHistory"].isEnabled)
        let shot=XCTAttachment(screenshot:app.screenshot()); shot.name="Matrix selected history"; shot.lifetime = .keepAlways; add(shot)
    }
    func testSwitchingServiceCancelsOldRequest() {
        let app=XCUIApplication(); app.launchArguments=["--fixture","services-slow","-AppleLanguages","(en)"]
        app.launch()
        for _ in 0..<6 { if app.buttons["serviceChecks"].isHittable { break }; app.swipeUp() }
        app.buttons["serviceChecks"].tap(); app.buttons["checkService"].tap()
        XCTAssertTrue(app.buttons["cancelService"].waitForExistence(timeout:3))
        app.segmentedControls["serviceTarget"].buttons["WhatsApp Web"].tap()
        XCTAssertTrue(app.buttons["checkService"].waitForExistence(timeout:3)); app.buttons["checkService"].tap()
        XCTAssertTrue(app.staticTexts["HTTP 401"].waitForExistence(timeout:5))
        XCTAssertFalse(app.staticTexts["Matrix versions response confirmed"].exists)
        let shot=XCTAttachment(screenshot:app.screenshot()); shot.name="WhatsApp response is not account access"; shot.lifetime = .keepAlways; add(shot)
    }
    func testQuietDayIncidentCenterStaysReachable() {
        let app = XCUIApplication(); app.launchArguments = ["--fixture", "ok", "-AppleLanguages", "(en)"]
        app.launch(); app.buttons["history"].tap(); app.buttons["incidents"].tap()
        XCTAssertTrue(app.staticTexts["noIncidents"].waitForExistence(timeout: 3))
    }
    func testIncidentHistoryAndWindowControls() {
        let app = XCUIApplication(); app.launchArguments = ["--fixture", "history", "-AppleLanguages", "(en)"]
        app.launch(); app.buttons["history"].tap()
        XCTAssertTrue(app.buttons["exportHistory"].exists)
        XCTAssertTrue(app.segmentedControls["historyWindow"].exists)
        app.segmentedControls["historyWindow"].buttons["7 days"].tap()
        app.buttons["incidents"].tap()
        XCTAssertTrue(app.staticTexts["Recovery confirmed"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Single observation — unconfirmed"].exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Incident recovery and unconfirmed network loss"; screenshot.lifetime = .keepAlways; add(screenshot)
    }
    func testCountryAndPermissionFixture() {
        let app = XCUIApplication(); app.launchArguments = ["--fixture", "ok", "-AppleLanguages", "(en)"]
        app.launch()
        for _ in 0..<6 {
            if app.staticTexts["countryName"].isHittable { break }
            app.swipeUp()
        }
        XCTAssertEqual(app.staticTexts["countryName"].label, "Germany")
        for _ in 0..<6 {
            if app.staticTexts["notificationPermission"].isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(app.staticTexts["notificationPermission"].label.contains("not sent"))
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Country and alert controls"; screenshot.lifetime = .keepAlways; add(screenshot)
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
        for label in ["Telegram Web", "Контрольный адрес"] {
            for _ in 0..<8 {
                if app.staticTexts[label].exists { break }
                app.swipeUp()
            }
            XCTAssertTrue(app.staticTexts[label].exists, label)
        }
    }
}
