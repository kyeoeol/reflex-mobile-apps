import AppCore
import Foundation
import UserNotifications

/// Routes notification responses into Core (`AppRoot.handleReminderResponse`). Assigned as the center's delegate in
/// `App.init`, so responses that launch the app (cold start, or a background launch for Taken / Skip / Snooze)
/// are not lost. The OS calls it on arbitrary threads: the methods are `nonisolated`, pull the Sendable bits out of
/// the response, and only then hop to the main actor. `didReceive` returns (telling the OS it's done) only when
/// Core has loaded, applied, saved and reconciled.
final class NotificationResponder: NSObject, UNUserNotificationCenterDelegate, Sendable {
    private let app: AppRoot

    init(app: AppRoot) {
        self.app = app
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse) async {
        let actionIdentifier = response.actionIdentifier
        guard let slot = response.notification.request.content.userInfo[ReminderNotification.Key.slot] as? String,
              let action = Self.action(for: actionIdentifier) else { return }   // `refresh`, or dismissed
        await app.handleReminderResponse(slot: slot, action: action)
    }

    /// In the foreground the reminder still shows as a banner (and stays in Notification Center).
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    private static func action(for identifier: String) -> ReminderResponse? {
        switch identifier {
        case UNNotificationDefaultActionIdentifier: .open
        case ReminderNotification.takenAction: .taken
        case ReminderNotification.skippedAction: .skipped
        case ReminderNotification.snoozeAction: .snooze
        default: nil
        }
    }
}
