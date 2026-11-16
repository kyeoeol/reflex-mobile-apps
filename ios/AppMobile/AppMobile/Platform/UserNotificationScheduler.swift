import AppCore
import Foundation
import UIKit
import UserNotifications

/// Identifiers shared by the scheduler (categories, content) and the delegate (responses).
enum ReminderNotification {
    static let doseCategory = "dose"
    static let takenAction = "taken"
    static let skippedAction = "skipped"
    static let snoozeAction = "snooze"

    enum Key {
        static let id = "id", kind = "kind", fireAt = "fireAt", localFireTime = "localFireTime", slot = "slot",
                   trigger = "trigger"
    }

    /// Taken and Skip are answered in the background (no app launch to the foreground); so is Snooze.
    static var categories: Set<UNNotificationCategory> {
        let actions = [
            UNNotificationAction(identifier: takenAction, title: "Taken", options: []),
            UNNotificationAction(identifier: skippedAction, title: "Skip", options: []),
            UNNotificationAction(identifier: snoozeAction, title: "Snooze 10 min", options: []),
        ]
        return [UNNotificationCategory(identifier: doseCategory, actions: actions, intentIdentifiers: [], options: [])]
    }
}

/// `ReminderScheduler` over `UNUserNotificationCenter`: reconciles the pending requests with Core's desired set
/// (diff by id and content), and reads them back from the OS for the Reminders screen.
actor UserNotificationScheduler: ReminderScheduler {
    private var center: UNUserNotificationCenter { .current() }

    init() {
        UNUserNotificationCenter.current().setNotificationCategories(ReminderNotification.categories)
    }

    func permission() async -> NotificationPermission {
        switch await center.notificationSettings().authorizationStatus {
        case .authorized: .authorized
        case .notDetermined: .notDetermined
        // Provisional (quiet) and ephemeral delivery are never requested and count as off.
        case .denied, .provisional, .ephemeral: .denied
        @unknown default: .denied
        }
    }

    func requestPermission() async -> NotificationPermission {
        _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
        return await permission()
    }

    func mayBeLate() async -> Bool { false }

    func replaceAll(_ reminders: [Reminder], openSlots: Set<String>) async {
        let pending = await center.pendingNotificationRequests()
        var scheduled: [String: [String: String]] = [:]
        for request in pending {
            scheduled[request.identifier] = Self.fingerprint(of: request.content)
        }
        let now = Date()
        let desired = reminders.compactMap { reminder in Self.request(for: reminder, now: now).map { (reminder.id, $0) } }
        let desiredIds = Set(desired.map(\.0))
        let obsolete = scheduled.keys.filter { !desiredIds.contains($0) }
        if !obsolete.isEmpty { center.removePendingNotificationRequests(withIdentifiers: obsolete) }
        for (id, request) in desired where scheduled[id] != Self.fingerprint(of: request.content) {
            try? await center.add(request)   // same identifier: replaces the pending one
        }

        let delivered = await center.deliveredNotifications()
        let closed = delivered.compactMap { notification -> String? in
            guard let slot = notification.request.content.userInfo[ReminderNotification.Key.slot] as? String,
                  !openSlots.contains(slot) else { return nil }
            return notification.request.identifier
        }
        if !closed.isEmpty { center.removeDeliveredNotifications(withIdentifiers: closed) }
    }

    /// Rebuilt from what the scheduler stored in `userInfo` (never from the trigger's next date), by fireAt then id.
    func pending() async -> [Reminder] {
        let requests = await center.pendingNotificationRequests()
        let reminders = requests.compactMap { request -> Reminder? in
            let info = request.content.userInfo
            guard let id = info[ReminderNotification.Key.id] as? String,
                  let kind = info[ReminderNotification.Key.kind] as? String,
                  let fireAtText = info[ReminderNotification.Key.fireAt] as? String,
                  let fireAt = try? Date(fireAtText, strategy: .iso8601),
                  let localFireTime = info[ReminderNotification.Key.localFireTime] as? String else { return nil }
            return Reminder(id: id, kind: kind, fireAt: fireAt, localFireTime: localFireTime,
                            title: request.content.title, body: request.content.body,
                            slot: info[ReminderNotification.Key.slot] as? String)
        }
        return reminders.sorted { ($0.fireAt, $0.id) < ($1.fireAt, $1.id) }
    }

    func openSettings() async {
        await Self.openNotificationSettings()
    }

    @MainActor
    private static func openNotificationSettings() async {
        guard let url = URL(string: UIApplication.openNotificationSettingsURLString) else { return }
        await UIApplication.shared.open(url)
    }

    // MARK: - Requests

    private static func request(for reminder: Reminder, now: Date) -> UNNotificationRequest? {
        let floating = floatingComponents(reminder)
        let trigger: UNNotificationTrigger
        if let floating {
            trigger = UNCalendarNotificationTrigger(dateMatching: floating, repeats: false)
        } else {
            let interval = reminder.fireAt.timeIntervalSince(now)
            guard interval > 0 else { return nil }
            trigger = UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
        }
        let content = UNMutableNotificationContent()
        content.title = reminder.title
        content.body = reminder.body
        content.sound = .default
        // `refresh` has no category: tapping it only opens the app.
        if reminder.slot != nil { content.categoryIdentifier = ReminderNotification.doseCategory }
        var info: [String: String] = [
            ReminderNotification.Key.id: reminder.id,
            ReminderNotification.Key.kind: reminder.kind,
            ReminderNotification.Key.fireAt: reminder.fireAt.formatted(.iso8601),
            ReminderNotification.Key.localFireTime: reminder.localFireTime,
            ReminderNotification.Key.trigger: floating == nil ? "absolute" : "floating",
        ]
        if let slot = reminder.slot { info[ReminderNotification.Key.slot] = slot }
        content.userInfo = info
        return UNNotificationRequest(identifier: reminder.id, content: content, trigger: trigger)
    }

    /// What decides whether a pending request must be replaced: fire time, trigger kind, title and body.
    private static func fingerprint(of content: UNNotificationContent) -> [String: String] {
        var print: [String: String] = ["title": content.title, "body": content.body]
        for key in [ReminderNotification.Key.fireAt, ReminderNotification.Key.trigger, ReminderNotification.Key.slot,
                    ReminderNotification.Key.kind] {
            if let value = content.userInfo[key] as? String { print[key] = value }
        }
        return print
    }

    /// The floating wall time (Gregorian year…minute, no time zone, so it follows zone changes) when it resolves back
    /// to `fireAt` in the current zone; nil inside DST gaps and overlaps, where the absolute instant is used instead.
    private static func floatingComponents(_ reminder: Reminder) -> DateComponents? {
        let parts = reminder.localFireTime.split(whereSeparator: { $0 == "-" || $0 == "T" || $0 == ":" }).compactMap { Int($0) }
        guard parts.count == 5 else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let components = DateComponents(calendar: Calendar(identifier: .gregorian), year: parts[0], month: parts[1],
                                        day: parts[2], hour: parts[3], minute: parts[4])
        var resolve = components
        resolve.calendar = calendar
        resolve.timeZone = calendar.timeZone
        guard let resolved = calendar.date(from: resolve), resolved == reminder.fireAt else { return nil }
        return components
    }
}
