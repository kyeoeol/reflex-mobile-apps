import Foundation

/// What the scheduler is handed: the complete desired set, and the slots whose delivered notifications stay.
struct ReminderPlan: Sendable, Equatable {
    let reminders: [Reminder]
    /// Slots with at least one unlogged dose and `now < T + 120 min`.
    let openSlots: Set<String>
}

/// The complete desired reminder set (docs/health/CONTRACT.md §1 Reminders). Pure: (data, now, zone, permission).
enum ReminderPlanner {
    static let window: TimeInterval = 7 * 24 * 60 * 60
    static let limit = 60

    static func plan(_ data: HealthData, clock: HealthNow, permission: NotificationPermission) -> ReminderPlan {
        let (now, zone) = (clock.now, clock.zone)
        let snoozes = Dictionary(data.snoozes.map { ($0.slot, $0.until) }, uniquingKeysWith: { _, last in last })
        var items: [Reminder] = []
        var openSlots = Set<String>()
        // Yesterday (a late slot can still be snoozed after midnight) through the last day the window can reach.
        for offset in -1...8 {
            let date = clock.today.adding(days: offset)
            let occurrences = data.occurrences(on: date, zone: zone).filter { $0.log == nil && $0.medication != nil }
            for time in Set(occurrences.map(\.time)).sorted() {
                let doses = data.unloggedDoses(slot: date, time: time, zone: zone)
                guard let scheduledAt = doses.first?.scheduledAt else { continue }
                let slot = "\(date.text)T\(time)"
                if now < scheduledAt.addingTimeInterval(HealthRules.missedAfter) { openSlots.insert(slot) }
                let names = doses.map { $0.medication!.name }
                let body = doses.map { dose in
                    let medication = dose.medication!
                    return medication.dose.isEmpty ? medication.name : "\(medication.name) (\(medication.dose))"
                }.joined(separator: ", ")
                let due = names.count == 1 ? "Time for \(names[0])" : "Time for \(names.count) medications"
                let stillDue = names.count == 1 ? "Still due: \(names[0])" : "Still due: \(names.count) medications"
                func reminder(_ prefix: String, _ kind: String, _ fireAt: Date, _ title: String) -> Reminder {
                    Reminder(id: "\(prefix):\(slot)", kind: kind, fireAt: fireAt,
                             localFireTime: HealthTime.localDateTime(of: fireAt, in: zone), title: title, body: body, slot: slot)
                }
                if let until = snoozes[slot].flatMap(HealthTime.parseInstant)?.instant, until > now {
                    items.append(reminder("snooze", "snooze", until, due))
                } else {
                    items.append(reminder("dose", "dose", scheduledAt, due))
                    items.append(reminder("followup", "followUp", scheduledAt.addingTimeInterval(HealthRules.followUpAfter), stillDue))
                }
            }
        }
        guard permission == .authorized else { return ReminderPlan(reminders: [], openSlots: openSlots) }

        let end = now.addingTimeInterval(window)
        let future = items.filter { $0.fireAt > now }
        var reminders = future.filter { $0.fireAt <= end }
            .sorted { $0.fireAt != $1.fireAt ? $0.fireAt < $1.fireAt : HealthRules.precedes($0.id, $1.id) }
        let refreshAt: Date?
        if reminders.count > limit {
            reminders = Array(reminders.prefix(limit - 1))
            refreshAt = reminders[limit - 2].fireAt.addingTimeInterval(60)
        } else {
            // Something lies beyond the window: ask the user to open the app instead of going silent.
            refreshAt = future.count > reminders.count ? end : nil
        }
        if let refreshAt {
            reminders.append(Reminder(id: "refresh", kind: "refresh", fireAt: refreshAt,
                                      localFireTime: HealthTime.localDateTime(of: refreshAt, in: zone),
                                      title: "Open Health Beta", body: "Open the app to keep your reminders coming.", slot: nil))
        }
        return ReminderPlan(reminders: reminders, openSlots: openSlots)
    }
}
