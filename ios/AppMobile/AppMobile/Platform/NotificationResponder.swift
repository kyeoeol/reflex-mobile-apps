import AppCore
import Foundation
import UserNotifications

/// Routes notification responses into Core (`AppRoot.handleReminderResponse`). Assigned as the center's delegate in
/// `App.init`, so responses that launch the app (cold start, or a background launch for Taken / Skip / Snooze)
/// are not lost. `didReceive` returns (telling the OS it's done) only when Core has loaded, applied, saved and
/// reconciled.
///
/// The methods are main-actor isolated on purpose: the OS's completion handler behind an `async` delegate method is
/// called wherever the method finishes, and UIKit asserts it runs on the main thread (a `nonisolated async` version
/// finishes on the cooperative pool and aborts the app in `-[UIApplication _performBlockAfterCATransactionCommit…]`
/// after a background Taken). The conformance is `@preconcurrency` because the SDK's parameters (center, response)
/// aren't Sendable; the center delivers them on the main thread, and only Sendable strings are read from the response
/// before Core is called.
@MainActor
final class NotificationResponder: NSObject, @preconcurrency UNUserNotificationCenterDelegate {
    private let app: AppRoot

    init(app: AppRoot) {
        self.app = app
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let actionIdentifier = response.actionIdentifier
        guard let slot = response.notification.request.content.userInfo[ReminderNotification.Key.slot] as? String,
              let action = Self.action(for: actionIdentifier) else { return }   // `refresh`, or dismissed
        await app.handleReminderResponse(slot: slot, action: action)
    }

    /// In the foreground the reminder still shows as a banner (and stays in Notification Center).
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated private static func action(for identifier: String) -> ReminderResponse? {
        switch identifier {
        case UNNotificationDefaultActionIdentifier: .open
        case ReminderNotification.takenAction: .taken
        case ReminderNotification.skippedAction: .skipped
        case ReminderNotification.snoozeAction: .snooze
        default: nil
        }
    }
}
