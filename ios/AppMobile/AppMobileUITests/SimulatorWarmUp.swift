import XCTest

/// Not a journey: tools/e2e-ios.sh runs this once on a freshly erased simulator, then uninstalls the app.
///
/// On a freshly erased iOS 27 simulator the notification daemon reports the first app it authorizes as authorized
/// (`getNotificationSettings`) but rejects every request it adds ("Repository could not save notification. Source
/// is not authorized.") for the rest of that session. After one full grant cycle and a reinstall it works, so the
/// journeys run after this warm-up, each still from a fresh install. A real device doesn't need this.
@MainActor
final class SimulatorWarmUp: XCTestCase {
    func testGrantNotificationsOnce() throws {
        continueAfterFailure = false
        let d = HealthDriver()
        d.app.launch()
        d.tap("today.addMedication")
        d.fillAndSave(name: "Warm-up", dose: "1 tablet", time: HealthDriver.timeString(HealthDriver.minuteBoundary(plus: 30)))
        d.answerPermission(allow: true)
        d.wait(d.element("today.progress"))
    }
}
