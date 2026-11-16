import Foundation

/// Everything the Health screens derive their state from, at one moment.
public struct HealthSnapshot: Sendable {
    public enum Phase: Sendable { case loading, loaded, failed }

    public let phase: Phase
    public let data: HealthData
    public let now: Date
    public let zone: TimeZone
    public let permission: NotificationPermission
    public let mayBeLate: Bool
    /// The scheduler's pending list, read back after every reconciliation.
    public let pending: [Reminder]

    var clock: HealthNow { HealthNow(now: now, zone: zone) }
    var today: LocalDate { clock.today }
}

/// The single owner of the Health data (docs/health/CONTRACT.md §6).
///
/// - Loads once through `HealthRepository`; mutations apply synchronously on the main actor, then the whole value
///   is saved asynchronously, one save at a time and in order. A failed load is never overwritten: every mutation
///   is then a no-op.
/// - After the load, every data change, permission change and clock change it recomputes the reminder set and
///   hands it to the `ReminderScheduler` (`replaceAll`, then `pending`), all scheduler calls serialized.
/// - Subscribers (the ViewModels) get a `HealthSnapshot` after every change, synchronously, and turn it into a
///   Message, so each ViewModel's `update` stays the only writer of its state.
@MainActor
final class HealthStore {
    private let repository: any HealthRepository
    private let scheduler: any ReminderScheduler
    private let clock: any Clock

    private var phase = HealthSnapshot.Phase.loading
    private var data = HealthData()
    private var permission = NotificationPermission.notDetermined
    private var mayBeLate = false
    private var pending: [Reminder] = []

    private var subscribers: [Subscriber] = []
    /// Notification responses that arrived (as deep links) before the data was loaded.
    private var deferredResponses: [(ReminderResponse, String)] = []
    private var lastSave: Task<Void, Never>?
    private var lastSchedulerWork: Task<Void, Never>?
    private var isRescheduleQueued = false

    private struct Subscriber {
        weak var owner: AnyObject?
        let deliver: @MainActor (HealthSnapshot) -> Void
    }

    init(repository: any HealthRepository, scheduler: any ReminderScheduler, clock: any Clock) {
        self.repository = repository
        self.scheduler = scheduler
        self.clock = clock
    }

    var snapshot: HealthSnapshot {
        HealthSnapshot(phase: phase, data: data, now: clock.now, zone: clock.timeZone, permission: permission,
                       mayBeLate: mayBeLate, pending: pending)
    }

    private var now: HealthNow { HealthNow(now: clock.now, zone: clock.timeZone) }

    /// `deliver` runs after every change until `owner` is gone.
    func subscribe(_ owner: AnyObject, _ deliver: @escaping @MainActor (HealthSnapshot) -> Void) {
        subscribers.append(Subscriber(owner: owner, deliver: deliver))
    }

    func start() {
        let (repository, scheduler) = (self.repository, self.scheduler)
        Task {
            async let permission = scheduler.permission()
            async let mayBeLate = scheduler.mayBeLate()
            let loaded: HealthData?
            do { loaded = try await repository.load() } catch { loaded = nil }
            self.finishLoading(loaded, permission: await permission, mayBeLate: await mayBeLate)
        }
        let changes = clock.changes
        Task { [weak self] in
            for await _ in changes { self?.clockChanged() }
        }
    }

    private func finishLoading(_ loaded: HealthData?, permission: NotificationPermission, mayBeLate: Bool) {
        self.permission = permission
        self.mayBeLate = mayBeLate
        guard let loaded else {
            phase = .failed
            deferredResponses = []
            notify()
            refreshPending()
            return
        }
        phase = .loaded
        data = loaded
        var changed = false
        for (response, slot) in deferredResponses { changed = data.respond(response, slot: slot, clock: now) || changed }
        deferredResponses = []
        if changed { persist() }
        notify()
        reschedule()
    }

    private func clockChanged() {
        notify()
        reschedule()
    }

    // MARK: Mutations (no-ops unless the data loaded)

    private func mutate<Result>(_ fallback: Result, _ change: (inout HealthData) -> Result) -> Result {
        guard phase == .loaded else { return fallback }
        var copy = data
        let result = change(&copy)
        if copy != data {
            data = copy
            persist()
            notify()
            reschedule()
        }
        return result
    }

    func take(doseId: String) -> DoseOutcome {
        let clock = now
        return mutate(.ignored) { $0.take(doseId: doseId, at: clock) }
    }

    func take(doseId: String, takenAt: String) -> DoseOutcome {
        let clock = now
        return mutate(.ignored) { $0.take(doseId: doseId, takenAt: takenAt, clock: clock) }
    }

    func skip(doseId: String) -> DoseOutcome {
        let clock = now
        return mutate(.ignored) { $0.skip(doseId: doseId, clock: clock) }
    }

    func undo(doseId: String) -> DoseOutcome {
        mutate(.ignored) { $0.undo(doseId: doseId) }
    }

    func logAsNeeded(medicationId: String) {
        let clock = now
        _ = mutate(false) { $0.logAsNeeded(medicationId: medicationId, clock: clock) }
    }

    func undoAsNeeded(medicationId: String) {
        let today = now.today
        _ = mutate(false) { $0.undoAsNeeded(medicationId: medicationId, today: today) }
    }

    /// Returns the saved medication's id, or nil when nothing can be saved (the data didn't load).
    func save(_ draft: MedicationDraft) -> String? {
        let today = now.today
        return mutate(nil) { $0.save(draft, today: today) }
    }

    func archive(medicationId: String) {
        let today = now.today
        _ = mutate(false) { $0.archive(medicationId: medicationId, today: today) }
    }

    func restore(medicationId: String) {
        let today = now.today
        _ = mutate(false) { $0.restore(medicationId: medicationId, today: today) }
    }

    func delete(medicationId: String) {
        _ = mutate(false) { $0.delete(medicationId: medicationId) }
    }

    /// A notification action; applied once the data has loaded if it arrives earlier.
    func respond(_ response: ReminderResponse, slot: String) {
        guard phase != .loading else {
            deferredResponses.append((response, slot))
            return
        }
        let clock = now
        _ = mutate(false) { $0.respond(response, slot: slot, clock: clock) }
    }

    // MARK: Reminders

    /// Asks for notification permission (the scheduler only asks while it's `notDetermined`), then reschedules.
    func requestPermission() {
        enqueueSchedulerWork { store, scheduler in
            store.permission = await scheduler.requestPermission()
            store.notify()
            store.reschedule()
        }
    }

    func openSettings() {
        enqueueSchedulerWork { _, scheduler in await scheduler.openSettings() }
    }

    private func reschedule() {
        guard phase == .loaded, !isRescheduleQueued else { return }   // a queued run picks up the latest data
        isRescheduleQueued = true
        enqueueSchedulerWork { store, scheduler in
            store.isRescheduleQueued = false
            let permission = await scheduler.permission()
            let mayBeLate = await scheduler.mayBeLate()
            await scheduler.replaceAll(ReminderPlanner.plan(store.data, clock: store.now, permission: permission))
            let pending = await scheduler.pending()
            store.permission = permission
            store.mayBeLate = mayBeLate
            store.pending = pending
            store.notify()
        }
    }

    /// Without readable data Core hands nothing over (it would cancel reminders it can't see); it only reads back.
    private func refreshPending() {
        enqueueSchedulerWork { store, scheduler in
            store.pending = await scheduler.pending()
            store.notify()
        }
    }

    private func enqueueSchedulerWork(_ work: @escaping @MainActor (HealthStore, any ReminderScheduler) async -> Void) {
        let previous = lastSchedulerWork
        let scheduler = self.scheduler
        lastSchedulerWork = Task {
            await previous?.value
            await work(self, scheduler)
        }
    }

    // MARK: Persistence and notification

    private func persist() {
        let (snapshot, previous, repository) = (data, lastSave, self.repository)
        lastSave = Task {
            await previous?.value
            try? await repository.save(snapshot)
        }
    }

    private func notify() {
        subscribers.removeAll { $0.owner == nil }
        let snapshot = self.snapshot
        for subscriber in subscribers { subscriber.deliver(snapshot) }
    }
}
