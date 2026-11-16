import Foundation

/// Presentation formatting only. State carries dates as `yyyy-MM-dd` and times as 24-hour `HH:mm` strings; views
/// show times verbatim and format only dates with the device locale. Pickers work on a fixed UTC Gregorian
/// calendar, so a `yyyy-MM-dd` value is a calendar date (never shifted by the device zone) and payloads are written
/// back with `en_US_POSIX` digits. Views have no clock: every picker starts from a state value or a fixed default.
enum HealthFormat {
    static let pickerTimeZone = TimeZone(identifier: "UTC")!

    /// The calendar pickers and payloads use.
    static let pickerCalendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = pickerTimeZone
        calendar.locale = Locale(identifier: "en_US_POSIX")
        return calendar
    }()

    /// A 24-hour locale for time pickers (times are always `HH:mm`, never AM/PM).
    static let pickerLocale = Locale(identifier: "en_GB")

    /// The Add time picker's starting value (`08:00`).
    static let defaultTime = pickerCalendar.date(from: DateComponents(year: 2000, month: 1, day: 1, hour: 8, minute: 0))!

    // MARK: Parsing state values

    static func date(_ text: String) -> Date? {
        let parts = text.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return pickerCalendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    static func dateTime(date: String, time: String) -> Date? {
        let dateParts = date.split(separator: "-").compactMap { Int($0) }
        let timeParts = time.split(separator: ":").compactMap { Int($0) }
        guard dateParts.count == 3, timeParts.count == 2 else { return nil }
        return pickerCalendar.date(from: DateComponents(year: dateParts[0], month: dateParts[1], day: dateParts[2],
                                                        hour: timeParts[0], minute: timeParts[1]))
    }

    // MARK: Payloads

    static func payloadDate(_ date: Date) -> String {
        let c = pickerCalendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    static func payloadTime(_ date: Date) -> String {
        let c = pickerCalendar.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    static func payloadDateTime(_ date: Date) -> String {
        "\(payloadDate(date))T\(payloadTime(date))"
    }

    // MARK: Display

    /// The device's own calendar and locale, pinned to the picker zone so the day never shifts.
    private static var displayStyle: Date.FormatStyle {
        var calendar = Calendar.current
        calendar.timeZone = pickerTimeZone
        return Date.FormatStyle(locale: .current, calendar: calendar, timeZone: pickerTimeZone)
    }

    /// "Monday, November 16"
    static func longDate(_ text: String) -> String {
        guard let date = date(text) else { return text }
        return date.formatted(displayStyle.weekday(.wide).month(.wide).day())
    }

    /// "Nov 16, 2026"
    static func mediumDate(_ text: String) -> String {
        guard let date = date(text) else { return text }
        return date.formatted(displayStyle.year().month(.abbreviated).day())
    }

    /// A pending reminder's wall time, from the `fireAt` string's own fields (no device-zone conversion):
    /// `2026-11-16T20:00:00+09:00` → "Nov 16, 2026 · 20:00".
    static func fireAtLabel(_ fireAt: String) -> String {
        let date = String(fireAt.prefix(10))
        let time = fireAt.count >= 16 ? String(fireAt.dropFirst(11).prefix(5)) : ""
        return "\(mediumDate(date)) · \(time)"
    }

    static func kindLabel(_ kind: String) -> String {
        kind == "supplement" ? "Supplement" : "Medication"
    }

    static func medicationStatusLabel(_ status: String) -> String {
        switch status {
        case "ended": "Ended"
        case "archived": "Archived"
        default: "Active"
        }
    }

    static func timingLabel(_ timing: String?) -> String? {
        switch timing {
        case "early": "Early"
        case "late": "Late"
        case "onTime": "On time"
        default: nil
        }
    }
}

extension DoseStatusKind {
    /// A visual mapping of the supplied status string.
    init(status: String) {
        switch status {
        case "due": self = .due
        case "taken": self = .taken
        case "skipped": self = .skipped
        case "missed": self = .missed
        default: self = .upcoming
        }
    }
}
