import XCTest

final class ReaderUITests: XCTestCase {
    private func launch() -> XCUIApplication {
        let app = XCUIApplication(); app.launchArguments = ["--ui-testing"]; app.launch(); return app
    }
    func testSettingsAndAccentPalette() {
        let app = launch()
        XCTAssertTrue(app.buttons["settings-button"].waitForExistence(timeout: 10))
        app.buttons["settings-button"].tap()
        XCTAssertTrue(app.switches["keep-current"].waitForExistence(timeout: 5))
        let color = app.buttons["accent-lavender"]
        if !color.isHittable { app.swipeUp() }
        XCTAssertTrue(color.waitForExistence(timeout: 5)); color.tap()
        app.buttons["Schließen"].tap()
        XCTAssertTrue(app.buttons["settings-button"].waitForExistence(timeout: 5))
    }
    func testLongPressSelectionCanBeExited() {
        let app = launch()
        let cell = app.cells.element(boundBy: 0)
        XCTAssertTrue(cell.waitForExistence(timeout: 10)); cell.press(forDuration: 0.8)
        XCTAssertTrue(app.buttons["Auswahl verlassen"].waitForExistence(timeout: 5))
        app.buttons["Alle auswählen"].tap()
        XCTAssertTrue(app.staticTexts["2 ausgewählt"].waitForExistence(timeout: 5))
        app.buttons["Auswahl verlassen"].tap()
        XCTAssertTrue(app.buttons["settings-button"].waitForExistence(timeout: 5))
    }
    func testRightwardSwipeAndNextChapter() {
        let app = launch()
        let cell = app.cells.element(boundBy: 0)
        XCTAssertTrue(cell.waitForExistence(timeout: 10)); cell.tap()
        let window = app.windows.firstMatch
        let center = window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let close = app.buttons["reader-close"]
        // Wait until the reader has loaded before revealing its controls.
        XCTAssertTrue(app.otherElements["manga-pager"].waitForExistence(timeout: 10))
        app.swipeRight(); center.tap()
        XCTAssertTrue(app.buttons["2 / 2"].waitForExistence(timeout: 5))
        app.buttons["Nächste Seite"].tap()
        XCTAssertTrue(app.staticTexts["Kapitel 10"].waitForExistence(timeout: 5))
        XCTAssertTrue(close.exists); close.tap()
        XCTAssertTrue(app.buttons["settings-button"].waitForExistence(timeout: 5))
    }
}
