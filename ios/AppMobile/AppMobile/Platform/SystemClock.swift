import AppCore
import Foundation
import UIKit

/// The real clock. `changes` yields on every minute boundary, on significant time changes (midnight, the user
/// setting the clock), on system time zone changes and whenever the app becomes active. The minute timer is
/// re-armed after each of those, so it stays on the boundary after the clock jumps.
@MainActor
final class SystemClock: Clock {
    let changes: AsyncStream<Void>
    private let continuation: AsyncStream<Void>.Continuation
    private var minuteTask: Task<Void, Never>?
    private var observers: [Task<Void, Never>] = []

    init() {
        // Only "something changed" matters; a burst while Core is busy collapses into one.
        (changes, continuation) = AsyncStream<Void>.makeStream(bufferingPolicy: .bufferingNewest(1))
        observe(UIApplication.significantTimeChangeNotification)
        observe(.NSSystemTimeZoneDidChange, resetsTimeZone: true)
        observe(UIApplication.didBecomeActiveNotification)
        armMinuteTimer()
    }

    var now: Date { Date() }

    /// Read fresh on every access: schedule times follow the device's current zone.
    var timeZone: TimeZone { TimeZone.current }

    private func observe(_ name: Notification.Name, resetsTimeZone: Bool = false) {
        let events = NotificationCenter.default.notifications(named: name).map { _ in () }
        observers.append(Task { [weak self] in
            for await _ in events {
                guard let self else { return }
                if resetsTimeZone { NSTimeZone.resetSystemTimeZone() }
                self.armMinuteTimer()
                self.continuation.yield()
            }
        })
    }

    private func armMinuteTimer() {
        minuteTask?.cancel()
        minuteTask = Task { [weak self] in
            while !Task.isCancelled {
                let now = Date()
                let next = (now.timeIntervalSinceReferenceDate / 60).rounded(.down) * 60 + 60
                let wait = next - now.timeIntervalSinceReferenceDate
                do {
                    try await Task.sleep(until: .now + .milliseconds(Int(wait * 1000) + 5), clock: ContinuousClock())
                } catch { return }
                guard let self else { return }
                self.continuation.yield()
            }
        }
    }
}
