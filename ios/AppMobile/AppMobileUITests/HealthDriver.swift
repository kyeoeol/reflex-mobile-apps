import Foundation
import XCTest

/// Drives Health Beta like a user: elements by the accessibility identifiers of docs/health/E2E.md, text by the
/// contract's literals, the permission alert and notifications through Springboard. No sleeps: every wait is a
/// condition with a timeout.
@MainActor
struct HealthDriver {
    let app = XCUIApplication(bundleIdentifier: "com.beta.health")
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    static let timeout: TimeInterval = 15

    // MARK: Lookup

    func element(_ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// A dose row on `screen` (`today`, `history`) for today's dose at `time`; the medication id is random.
    func doseRow(_ screen: String, date: String, time: String) -> XCUIElement {
        let predicate = NSPredicate(format: "identifier BEGINSWITH %@ AND identifier ENDSWITH %@", "\(screen).dose.", "@\(date)T\(time)")
        return app.descendants(matching: .any).matching(predicate).firstMatch
    }

    func first(prefix: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch
    }

    func tab(_ id: String) -> XCUIElement {
        let byId = app.tabBars.buttons.matching(identifier: "tab.\(id)").firstMatch
        return byId
    }

    // MARK: Waiting

    @discardableResult
    func wait(_ element: XCUIElement, timeout: TimeInterval = timeout, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        if !element.waitForExistence(timeout: timeout) {
            XCTFail("Not found: \(element)\n\(app.debugDescription)", file: file, line: line)
        }
        return element
    }

    func waitGone(_ element: XCUIElement, timeout: TimeInterval = timeout, file: StaticString = #filePath, line: UInt = #line) {
        if !element.waitForNonExistence(timeout: timeout) {
            XCTFail("Still present: \(element)", file: file, line: line)
        }
    }

    /// Waits until the element's text (its label, or its value for fields) equals `text`.
    func expectText(_ id: String, _ text: String, timeout: TimeInterval = timeout, file: StaticString = #filePath, line: UInt = #line) {
        expectLabel(element(id), text, timeout: timeout, file: file, line: line)
    }

    func expectLabel(_ element: XCUIElement, _ text: String, timeout: TimeInterval = timeout, file: StaticString = #filePath, line: UInt = #line) {
        let predicate = NSPredicate(format: "label == %@", text)
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        if XCTWaiter.wait(for: [expectation], timeout: timeout) != .completed {
            let actual = element.exists ? "'\(element.label)'" : "missing"
            XCTFail("Expected '\(text)' on \(element), found \(actual)\n\(app.debugDescription)", file: file, line: line)
        }
    }

    func expectEnabled(_ id: String, _ enabled: Bool, file: StaticString = #filePath, line: UInt = #line) {
        let element = wait(element(id), file: file, line: line)
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == %@", NSNumber(value: enabled)), object: element)
        if XCTWaiter.wait(for: [expectation], timeout: Self.timeout) != .completed {
            XCTFail("\(id) enabled should be \(enabled)", file: file, line: line)
        }
    }

    // MARK: Acting

    /// Scrolls the screen until the element can be tapped (content may be long at large text sizes).
    func reveal(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        wait(element, file: file, line: line)
        var attempts = 0
        while !element.isHittable && attempts < 12 {
            let scroll = app.scrollViews.firstMatch
            let frame = element.frame
            let window = app.windows.firstMatch.frame
            if frame.minY < window.midY { (scroll.exists ? scroll : app).swipeDown() } else { (scroll.exists ? scroll : app).swipeUp() }
            attempts += 1
        }
        XCTAssertTrue(element.isHittable, "Not hittable: \(element)\n\(app.debugDescription)", file: file, line: line)
    }

    func tap(_ id: String, file: StaticString = #filePath, line: UInt = #line) {
        tap(element(id), file: file, line: line)
    }

    func tap(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        reveal(element, file: file, line: line)
        element.tap()
    }

    /// Types into a field. Single-line fields are submitted with Return, which also hides the keyboard.
    func type(_ id: String, _ text: String, submit: Bool = true) {
        let field = element(id)
        tap(field)
        field.typeText(submit ? text + "\n" : text)
        if submit { waitGone(app.keyboards.firstMatch) }
    }

    func selectTab(_ id: String) {
        tap(tab(id))
    }

    /// The pushed screen's system back button.
    func goBack() {
        tap(app.navigationBars.buttons.element(boundBy: 0))
    }

    /// Opens a dose row's menu and picks one entry (`doseMenu.take`, `.takeAt`, `.skip`, `.undo`).
    func doseMenu(_ row: XCUIElement, _ entry: String) {
        tap(row)
        tap(element(entry))
    }

    // MARK: Editor

    /// Picks `HH:mm` on the 24-hour wheel and adds it.
    func addTime(_ time: String) {
        tap("editor.addTime")
        let parts = time.split(separator: ":").map(String.init)
        let wheels = app.pickerWheels
        wait(wheels.element(boundBy: 1))
        wheels.element(boundBy: 0).adjust(toPickerWheelValue: parts[0])
        wheels.element(boundBy: 1).adjust(toPickerWheelValue: parts[1])
        tap("editor.timePicker.confirm")
        wait(element("editor.time.\(time)"))
    }

    /// Adds a scheduled medication from the open editor and saves it.
    func fillAndSave(name: String, dose: String, time: String) {
        type("editor.name", name)
        type("editor.dose", dose)
        addTime(time)
        tap("editor.save")
    }

    // MARK: System UI

    /// Answers the notification permission alert (`Allow` / `Don’t Allow`; the apostrophe is curly).
    func answerPermission(allow: Bool, file: StaticString = #filePath, line: UInt = #line) {
        let predicate = NSPredicate(format: allow ? "label BEGINSWITH 'Allow'" : "label BEGINSWITH 'Don'")
        let button = springboard.alerts.buttons.matching(predicate).firstMatch
        // The system shows the alert when it gets to it; on a busy simulator that can take well over 20 s.
        if !button.waitForExistence(timeout: 60) {
            XCTFail("No permission alert\n\(springboard.debugDescription)", file: file, line: line)
            return
        }
        button.tap()
        waitGone(springboard.alerts.firstMatch)
    }

    // MARK: Clock (the real one: the simulator shares the host's)

    static func dateString(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
    }

    static func timeString(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", c.hour!, c.minute!)
    }

    /// The next minute boundary plus `minutes`.
    static func minuteBoundary(after date: Date = Date(), plus minutes: Int) -> Date {
        let start = Calendar.current.dateInterval(of: .minute, for: date)!.end
        return start.addingTimeInterval(TimeInterval(minutes * 60))
    }

    // MARK: Screenshots (review only: written when the script passes SCREENSHOT_DIR, never into the repo)

    func screenshot(_ name: String) {
        let environment = ProcessInfo.processInfo.environment
        guard let directory = environment["SCREENSHOT_DIR"], !directory.isEmpty else { return }
        let suffix = environment["SCREENSHOT_SUFFIX"].map { "-\($0)" } ?? ""
        let url = URL(fileURLWithPath: directory).appendingPathComponent("\(name)\(suffix).png")
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: url)
    }
}
