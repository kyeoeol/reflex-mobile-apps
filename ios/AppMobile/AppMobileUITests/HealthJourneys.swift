import XCTest

/// The E2E journeys of docs/health/E2E.md. Each test is one fresh-install group; tools/e2e-ios.sh uninstalls the
/// app before each one (`-only-testing:AppMobileUITests/HealthJourneys/<test>`), so they never share data or a
/// permission answer. The clock is real: dose times are picked relative to now (the script refuses to start close
/// to midnight).
@MainActor
final class HealthJourneys: XCTestCase {
    private func start() -> HealthDriver {
        continueAfterFailure = false
        return HealthDriver()
    }

    // E1 + E2 + E3 + E5
    func testAddTakeRemindersPersist() throws {
        let d = start()
        let fireAt = HealthDriver.minuteBoundary(plus: 10)
        let today = HealthDriver.dateString(Date()), time = HealthDriver.timeString(fireAt)
        d.app.launch()

        // E1: fresh install → add a medication → allow notifications → the dose is upcoming, no banner.
        d.expectText("today.message", "No medications yet")
        d.tap("today.addMedication")
        d.fillAndSave(name: "Vitamin D", dose: "1 capsule", time: time)
        d.answerPermission(allow: true)
        let row = d.doseRow("today", date: today, time: time)
        d.wait(row)
        d.expectLabel(d.element(row.identifier + ".status"), "Upcoming")
        d.expectText("today.progress", "0 of 1 taken")
        XCTAssertFalse(d.element("today.banner").exists, "no reminder banner once notifications are allowed")

        // E2: Take now from the row's menu → all done; Undo → back to 0 of 1.
        d.doseMenu(row, "doseMenu.take")
        d.expectText("today.progress", "1 of 1 taken")
        d.expectText("today.message", "All done for today")
        d.expectLabel(d.element(row.identifier + ".status"), "Taken")
        d.doseMenu(row, "doseMenu.undo")
        d.expectText("today.progress", "0 of 1 taken")
        d.waitGone(d.element("today.message"))

        // E3: the Reminders screen reads the pending reminder back from the OS scheduler.
        d.tap("today.openReminders")
        d.expectText("reminders.status", "Reminders are on")
        let reminder = d.wait(d.element("reminders.item.dose:\(today)T\(time)"))
        XCTAssertTrue(reminder.label.contains("Time for Vitamin D"), reminder.label)
        XCTAssertTrue(reminder.label.contains(time), reminder.label)
        d.goBack()
        d.wait(d.element("today.progress"))

        // E5: terminate and relaunch: the medication and its schedule are still there.
        d.app.terminate()
        d.app.launch()
        d.wait(d.doseRow("today", date: today, time: time))
        d.expectText("today.progress", "0 of 1 taken")
        d.selectTab("medications")
        let item = d.wait(d.first(prefix: "medications.item."))
        XCTAssertTrue(item.label.contains("Vitamin D"), item.label)
    }

    // E4: a reminder arrives while the app is not running and is answered with Taken from Notification Center.
    func testNotificationTaken() throws {
        let d = start()
        let fireAt = HealthDriver.minuteBoundary(plus: 2)
        let today = HealthDriver.dateString(Date()), time = HealthDriver.timeString(fireAt)
        d.app.launch()
        d.tap("today.addMedication")
        d.fillAndSave(name: "Vitamin D", dose: "1 capsule", time: time)
        d.answerPermission(allow: true)
        d.wait(d.doseRow("today", date: today, time: time))
        d.expectText("today.progress", "0 of 1 taken")
        d.app.terminate()

        // Notification Center keeps the notification (a banner disappears after a few seconds).
        let springboard = d.springboard
        springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.002))
            .press(forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.75)))
        let notification = springboard.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'NotificationShortLookView' AND label CONTAINS %@", "Time for Vitamin D"))
            .firstMatch
        let remaining = fireAt.timeIntervalSinceNow
        XCTAssertTrue(notification.waitForExistence(timeout: max(remaining, 0) + 120),
                      "No notification\n\(springboard.debugDescription)")
        // Swipe left → View expands it with its actions (a long press doesn't on the simulator).
        notification.descendants(matching: .button).matching(identifier: "ShortLook.Platter.Content.Seamless").firstMatch.swipeLeft()
        let view = springboard.buttons.matching(identifier: "swipe-action-button-identifier").matching(NSPredicate(format: "label == 'View'")).firstMatch
        XCTAssertTrue(view.waitForExistence(timeout: 10), "No View action\n\(springboard.debugDescription)")
        view.tap()
        let taken = springboard.buttons["Taken"]
        XCTAssertTrue(taken.waitForExistence(timeout: 10), "No Taken action\n\(springboard.debugDescription)")
        taken.tap()

        // Taken is answered in the background; the app then shows the dose taken.
        d.app.activate()
        d.expectText("today.progress", "1 of 1 taken", timeout: 30)
        d.expectLabel(d.element(d.doseRow("today", date: today, time: time).identifier + ".status"), "Taken")
    }

    // E6: notifications denied: the banner offers Settings, and taking a dose still works.
    func testNotificationsDenied() throws {
        let d = start()
        let fireAt = HealthDriver.minuteBoundary(plus: 10)
        let today = HealthDriver.dateString(Date()), time = HealthDriver.timeString(fireAt)
        d.app.launch()
        d.tap("today.addMedication")
        d.fillAndSave(name: "Vitamin D", dose: "1 capsule", time: time)
        d.answerPermission(allow: false)
        d.expectText("today.banner", "Reminders are off")
        d.expectText("today.banner.action", "Open Settings")
        let row = d.doseRow("today", date: today, time: time)
        d.doseMenu(row, "doseMenu.take")
        d.expectText("today.progress", "1 of 1 taken")
    }

    // E8 then E7 (E8 needs the medication E7 deletes).
    func testHistoryAndLifecycle() throws {
        let d = start()
        let fireAt = HealthDriver.minuteBoundary(plus: 10)
        let today = HealthDriver.dateString(Date()), time = HealthDriver.timeString(fireAt)
        d.app.launch()
        d.tap("today.addMedication")
        d.fillAndSave(name: "Vitamin D", dose: "1 capsule", time: time)
        d.answerPermission(allow: true)
        d.wait(d.doseRow("today", date: today, time: time))

        // E8: today's entry is listed; the day before is empty; Next is disabled on today.
        d.selectTab("history")
        d.wait(d.doseRow("history", date: today, time: time))
        d.expectEnabled("history.next", false)
        d.tap("history.previous")
        d.expectText("history.message", "Nothing recorded on this day")
        d.expectEnabled("history.next", true)
        d.tap("history.next")
        d.wait(d.doseRow("history", date: today, time: time))
        d.expectEnabled("history.next", false)

        // E7: archive → restore → delete with confirmation → gone from Medications and Today.
        d.selectTab("medications")
        d.tap(d.first(prefix: "medications.item."))
        d.expectText("detail.name", "Vitamin D")
        d.expectText("detail.status", "Active")
        d.tap("detail.archive")
        d.expectText("detail.status", "Archived")
        d.goBack()
        let archived = d.wait(d.element("medications.section.archived"))
        XCTAssertTrue(archived.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'medications.item.'")).firstMatch.exists)
        d.tap(d.first(prefix: "medications.item."))
        d.tap("detail.restore")
        d.expectText("detail.status", "Active")
        d.tap("detail.delete")
        d.tap(d.app.alerts.buttons.matching(identifier: "deleteDialog.confirm").firstMatch)
        d.wait(d.element("medications.empty"))
        d.waitGone(d.first(prefix: "medications.item."))
        d.selectTab("today")
        d.expectText("today.message", "No medications yet")
    }

    // E9: dark appearance and the largest accessibility text size (set by the script): the main screens render and
    // their main buttons can be reached and tapped.
    func testLargeTextDark() throws {
        let d = start()
        let time = HealthDriver.timeString(HealthDriver.minuteBoundary(plus: 10))
        d.app.launch()
        d.reveal(d.element("today.addMedication"))
        d.reveal(d.element("today.openReminders"))
        d.screenshot("ax-today")
        d.tap("today.addMedication")
        for id in ["editor.name", "editor.addTime", "editor.weekday.7", "editor.startDate", "editor.notes", "editor.save"] {
            d.reveal(d.element(id))
        }
        d.reveal(d.element("editor.name"))
        d.screenshot("ax-editor")
        d.fillAndSave(name: "Vitamin D", dose: "1 capsule", time: time)
        d.answerPermission(allow: true)
        d.wait(d.first(prefix: "today.dose."))
        d.screenshot("ax-today-dose")
        d.selectTab("medications")
        d.tap(d.first(prefix: "medications.item."))
        for id in ["detail.edit", "detail.archive", "detail.delete"] {
            d.reveal(d.element(id))
        }
        d.reveal(d.element("detail.name"))
        d.screenshot("ax-detail")
    }
}
