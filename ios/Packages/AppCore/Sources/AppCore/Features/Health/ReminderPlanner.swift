import Foundation

/// The complete desired reminder set (docs/health/CONTRACT.md §1 Reminders). Pure: (data, now, zone, permission).
enum ReminderPlanner {
    static let window: TimeInterval = 7 * 24 * 60 * 60
    static let limit = 60

    static func plan(_ data: HealthData, clock: HealthNow, permission: NotificationPermission) -> [Reminder] {
        guard permission == .authorized else { return [] }
        let (now, zone) = (clock.now, clock.zone)
        let snoozes = Dictionary(data.snoozes.map { ($0.slot, $0.until) }, uniquingKeysWith: { _, last in last })
        var items: [Reminder] = []
        // Yesterday (a late slot can still be snoozed after midnight) through the last day the window can reach.
        for offset in -1...8 {
            let date = clock.today.adding(days: offset)
            let occurrences = data.occurrences(on: date, zone: zone).filter { $0.log == nil && $0.medication != nil }
            for time in Set(occurrences.map(\.time)).sorted() {
                let doses = data.unloggedDoses(slot: date, time: time, zone: zone)
                guard let scheduledAt = doses.first?.scheduledAt else { continue }
                let slot = "\(date.text)T\(time)"
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
        let end = now.addingTimeInterval(window)
        var reminders = items.filter { $0.fireAt > now && $0.fireAt <= end }
            .sorted { $0.fireAt != $1.fireAt ? $0.fireAt < $1.fireAt : $0.id < $1.id }
        if reminders.count > limit {
            reminders = Array(reminders.prefix(limit - 1))
            let fireAt = reminders[limit - 2].fireAt.addingTimeInterval(60)
            reminders.append(Reminder(id: "refresh", kind: "refresh", fireAt: fireAt,
                                      localFireTime: HealthTime.localDateTime(of: fireAt, in: zone),
                                      title: "Open Health Beta", body: "Open the app to keep your reminders coming.", slot: nil))
        }
        return reminders
    }
}
