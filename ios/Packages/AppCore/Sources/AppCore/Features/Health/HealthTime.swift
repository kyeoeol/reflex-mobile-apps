import Foundation

// Local dates, wall-clock times and instants (docs/health/CONTRACT.md §1 Formats).
//
// Wall-clock arithmetic (adding days, weekdays, formatting) runs on a Gregorian calendar fixed to UTC, which has no
// daylight saving, so "a local date-time" is just a number of wall seconds. A time zone only comes in when a wall
// time is resolved to an instant (`HealthTime.resolve`) or an instant is formatted (`HealthTime.format`).

/// A local calendar date, `yyyy-MM-dd`.
public struct LocalDate: Hashable, Comparable, Sendable, CustomStringConvertible {
    public let year: Int
    public let month: Int
    public let day: Int

    /// Strict `yyyy-MM-dd` (a real calendar date); nil for anything else.
    public init?(_ text: String) {
        let chars = Array(text.utf8)
        guard chars.count == 10, chars[4] == UInt8(ascii: "-"), chars[7] == UInt8(ascii: "-"),
              let year = HealthTime.digits(chars, 0, 4), let month = HealthTime.digits(chars, 5, 2),
              let day = HealthTime.digits(chars, 8, 2),
              (1...12).contains(month), day >= 1,
              day <= HealthTime.wallCalendar.range(of: .day, in: .month,
                                                   for: HealthTime.wall(year: year, month: month, day: 1))?.count ?? 0
        else { return nil }
        self.init(year: year, month: month, day: day)
    }

    init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// The date of a wall-clock moment (wall seconds as a `Date` in UTC).
    init(wall: Date) {
        let parts = HealthTime.wallCalendar.dateComponents([.year, .month, .day], from: wall)
        self.init(year: parts.year!, month: parts.month!, day: parts.day!)
    }

    public var text: String { String(format: "%04d-%02d-%02d", year, month, day) }
    public var description: String { text }

    /// Midnight of this date as wall seconds.
    var wallMidnight: Date { HealthTime.wall(year: year, month: month, day: day) }

    public func adding(days: Int) -> LocalDate { LocalDate(wall: wallMidnight.addingTimeInterval(Double(days) * 86_400)) }

    /// ISO weekday: 1 = Monday … 7 = Sunday.
    public var isoWeekday: Int {
        let weekday = HealthTime.wallCalendar.component(.weekday, from: wallMidnight)   // 1 = Sunday … 7 = Saturday
        return (weekday + 5) % 7 + 1
    }

    public static func < (lhs: LocalDate, rhs: LocalDate) -> Bool {
        (lhs.year, lhs.month, lhs.day) < (rhs.year, rhs.month, rhs.day)
    }
}

public enum HealthTime {
    static let wallCalendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }()

    static func wall(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0) -> Date {
        wallCalendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute, second: second))!
    }

    /// Parses `count` ASCII digits starting at `start`.
    static func digits(_ chars: [UInt8], _ start: Int, _ count: Int) -> Int? {
        guard start + count <= chars.count else { return nil }
        var value = 0
        for c in chars[start..<start + count] {
            guard c >= UInt8(ascii: "0"), c <= UInt8(ascii: "9") else { return nil }
            value = value * 10 + Int(c - UInt8(ascii: "0"))
        }
        return value
    }

    // MARK: Wall-clock times

    /// Minutes since midnight of a strict `HH:mm` (`^([01]\d|2[0-3]):[0-5]\d$`), else nil.
    public static func minutes(ofTime text: String) -> Int? {
        let chars = Array(text.utf8)
        guard chars.count == 5, chars[2] == UInt8(ascii: ":"),
              let hour = digits(chars, 0, 2), let minute = digits(chars, 3, 2), hour < 24, minute < 60 else { return nil }
        return hour * 60 + minute
    }

    public static func isTime(_ text: String) -> Bool { minutes(ofTime: text) != nil }

    /// A local date-time `yyyy-MM-ddTHH:mm`, or `yyyy-MM-ddTHH:mm:ss` when `withSeconds`, as (date, seconds of day).
    static func parseLocalDateTime(_ text: String, withSeconds: Bool) -> (date: LocalDate, seconds: Int)? {
        let length = withSeconds ? 19 : 16
        guard text.utf8.count == length else { return nil }
        let chars = Array(text.utf8)
        guard chars[10] == UInt8(ascii: "T"), let date = LocalDate(String(decoding: chars[0..<10], as: UTF8.self)),
              let minutes = minutes(ofTime: String(decoding: chars[11..<16], as: UTF8.self)) else { return nil }
        guard withSeconds else { return (date, minutes * 60) }
        guard chars[16] == UInt8(ascii: ":"), let seconds = digits(chars, 17, 2), seconds < 60 else { return nil }
        return (date, minutes * 60 + seconds)
    }

    // MARK: Resolving wall time to an instant (the contract's only allowed rule)

    /// Resolves a local date + time of day in `zone`:
    /// - a time that exists once → that instant;
    /// - a time that exists twice (fall-back overlap) → the earlier instant (the offset from before the transition);
    /// - a time that doesn't exist (spring-forward gap) → shifted forward by the gap, i.e. read with the offset from
    ///   before the gap (02:30 → 03:30 when clocks jump 02:00 → 03:00), like java.time's `ZonedDateTime.of`.
    public static func resolve(_ date: LocalDate, secondsOfDay: Int, in zone: TimeZone) -> Date {
        let wall = date.wallMidnight.timeIntervalSince1970 + Double(secondsOfDay)
        // Walk the zone's offset segments around the wall time. Any instant whose local time is `wall` lies within
        // ±14 h of it, so the segments between wall − 2 days and wall + 2 days cover every candidate.
        let windowEnd = Date(timeIntervalSince1970: wall + 2 * 86_400)
        var segmentStart = Date(timeIntervalSince1970: wall - 2 * 86_400)
        var offset = Double(zone.secondsFromGMT(for: segmentStart))
        var candidates: [Date] = []
        var gapOffset: Double?
        while true {
            let next = zone.nextDaylightSavingTimeTransition(after: segmentStart).flatMap { $0 <= windowEnd ? $0 : nil }
            let instant = Date(timeIntervalSince1970: wall - offset)
            if instant >= segmentStart, next.map({ instant < $0 }) ?? true { candidates.append(instant) }
            guard let transition = next else { break }
            let after = Double(zone.secondsFromGMT(for: transition))
            let transitionWall = transition.timeIntervalSince1970 + offset
            if after > offset, wall >= transitionWall, wall < transitionWall + (after - offset) { gapOffset = offset }
            offset = after
            segmentStart = transition
        }
        return candidates.min() ?? Date(timeIntervalSince1970: wall - (gapOffset ?? offset))
    }

    /// Resolves `yyyy-MM-ddTHH:mm` (user input) in `zone`; nil when malformed.
    public static func resolve(localDateTime text: String, in zone: TimeZone) -> Date? {
        parseLocalDateTime(text, withSeconds: false).map { resolve($0.date, secondsOfDay: $0.seconds, in: zone) }
    }

    /// Resolves `yyyy-MM-ddTHH:mm:ss` (the fixture clock's `device.now`) in `zone`; nil when malformed.
    public static func resolve(localDateTimeWithSeconds text: String, in zone: TimeZone) -> Date? {
        parseLocalDateTime(text, withSeconds: true).map { resolve($0.date, secondsOfDay: $0.seconds, in: zone) }
    }

    // MARK: Instants

    static func wallDate(of instant: Date, offset: Int) -> Date {
        Date(timeIntervalSince1970: (instant.timeIntervalSince1970 + Double(offset)).rounded(.down))
    }

    /// The local date of an instant in `zone`.
    public static func localDate(of instant: Date, in zone: TimeZone) -> LocalDate {
        LocalDate(wall: wallDate(of: instant, offset: zone.secondsFromGMT(for: instant)))
    }

    /// `HH:mm` of an instant at a fixed UTC offset (seconds).
    static func localTime(of instant: Date, offset: Int) -> String {
        let parts = wallCalendar.dateComponents([.hour, .minute], from: wallDate(of: instant, offset: offset))
        return String(format: "%02d:%02d", parts.hour!, parts.minute!)
    }

    /// `yyyy-MM-ddTHH:mm` of an instant in `zone` (the wall time a floating OS trigger needs).
    public static func localDateTime(of instant: Date, in zone: TimeZone) -> String {
        let offset = zone.secondsFromGMT(for: instant)
        return "\(LocalDate(wall: wallDate(of: instant, offset: offset)).text)T\(localTime(of: instant, offset: offset))"
    }

    /// ISO 8601 with seconds and the numeric offset `zone` has at that instant, never `Z`: `2026-11-16T08:00:00+09:00`.
    public static func format(_ instant: Date, in zone: TimeZone) -> String {
        format(instant, offset: zone.secondsFromGMT(for: instant))
    }

    static func format(_ instant: Date, offset: Int) -> String {
        let wall = wallDate(of: instant, offset: offset)
        let parts = wallCalendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: wall)
        let sign = offset < 0 ? "-" : "+"
        let minutes = abs(offset) / 60
        return String(format: "%04d-%02d-%02dT%02d:%02d:%02d%@%02d:%02d", parts.year!, parts.month!, parts.day!,
                      parts.hour!, parts.minute!, parts.second!, sign, minutes / 60, minutes % 60)
    }

    /// Parses a stored instant (`yyyy-MM-ddTHH:mm:ss±HH:mm`; `Z` is accepted when reading) into the instant and
    /// the offset it was written with.
    public static func parseInstant(_ text: String) -> (instant: Date, offset: Int)? {
        let chars = Array(text.utf8)
        guard chars.count >= 20, let local = parseLocalDateTime(String(decoding: chars[0..<19], as: UTF8.self), withSeconds: true)
        else { return nil }
        let offset: Int
        if chars.count == 20, chars[19] == UInt8(ascii: "Z") {
            offset = 0
        } else {
            guard chars.count == 25, chars[19] == UInt8(ascii: "+") || chars[19] == UInt8(ascii: "-"), chars[22] == UInt8(ascii: ":"),
                  let hours = digits(chars, 20, 2), let minutes = digits(chars, 23, 2), minutes < 60 else { return nil }
            offset = (chars[19] == UInt8(ascii: "-") ? -1 : 1) * (hours * 3600 + minutes * 60)
        }
        let wall = local.date.wallMidnight.timeIntervalSince1970 + Double(local.seconds)
        return (Date(timeIntervalSince1970: wall - Double(offset)), offset)
    }

    /// `HH:mm` of a stored instant in the offset it was stored with; nil when unparseable.
    static func storedTime(_ text: String) -> String? {
        parseInstant(text).map { localTime(of: $0.instant, offset: $0.offset) }
    }
}
