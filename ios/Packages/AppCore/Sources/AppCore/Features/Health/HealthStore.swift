import Foundation

/// Everything the Health screens derive their state from, at one moment.
public struct HealthSnapshot: Sendable {
    public enum Phase: Sendable { case loading, loaded, failed }

    public let phase: Phase
    public let data: HealthData
    /// The last save failed (the change is kept in memory); cleared by the next successful save.
    public let saveError: Bool
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
/// - Loads through `HealthRepository` (again on `retry()` after a failure); mutations apply synchronously on the
///   main actor, then the whole value is saved asynchronously. Saves are serialized and coalesced: one at a time,
///   and a save that starts takes the latest data, so requests made meanwhile collapse into it. A failed load is
///   never overwritten: every mutation is then a no-op.
/// - After the load, every data change, permission change and clock change it reconciles the reminders: re-read
///   `permission()` and `mayBeLate()`, hand the planned set to `replaceAll` (only when the data loaded), read
///   `pending()` back. Every scheduler call goes through one serial queue; a reconcile that is still queued is
///   not queued twice (it reads the latest data when it runs).
/// - Subscribers (the ViewModels, AppRoot) get a `HealthSnapshot` after every change, synchronously, and turn it
///   into a Message, so each ViewModel's `update` stays the only writer of its state.
@MainActor
final class HealthStore {
    private let repository: any HealthRepository
    private let scheduler: any ReminderScheduler
    private let clock: any Clock

    private var phase = HealthSnapshot.Phase.loading
    private var data = HealthData()
    private var saveError = false
    private var permission = NotificationPermission.notDetermined
    private var mayBeLate = false
    private var pending: [Reminder] = []

    private var subscribers: [Subscriber] = []
    /// Notification responses that arrived before the first load finished; applied once it succeeds.
    private var deferredResponses: [(ReminderResponse, String)] = []
    /// Work waiting for the first successful load (deep links); dropped when the load fails.
    private var loadObservers: [@MainActor (HealthData) -> Void] = []

    private var loadTask: Task<Void, Never>?
    private var saveTask: Task<Void, Never>?
    private var isSaveRequested = false
    private var schedulerTask: Task<Void, Never>?
    private var schedulerJobs: [SchedulerJob] = []

    private enum SchedulerJob: Equatable {
        case reconcile, requestPermission, openSettings
    }

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
        HealthSnapshot(phase: phase, data: data, saveError: saveError, now: clock.now, zone: clock.timeZone,
                       permission: permission, mayBeLate: mayBeLate, pending: pending)
    }

    private var now: HealthNow { HealthNow(now: clock.now, zone: clock.timeZone) }

    /// `deliver` runs after every change until `owner` is gone.
    func subscribe(_ owner: AnyObject, _ deliver: @escaping @MainActor (HealthSnapshot) -> Void) {
        subscribers.append(Subscriber(owner: owner, deliver: deliver))
    }

    func start() {
        load()
        // Permission and the pending list are known before the data is (nothing is handed over yet).
        enqueue(.reconcile)
        let changes = clock.changes
        Task { [weak self] in
            for await _ in changes { self?.clockChanged() }
        }
    }

    /// Returns once the load and every save and scheduler call requested so far (and any requested meanwhile)
    /// have finished. Never hangs on a failed load: there is simply nothing left to wait for.
    func settle() async {
        while true {
            if let task = loadTask {
                await task.value
            } else if let task = saveTask {
                await task.value
            } else if let task = schedulerTask {
                await task.value
            } else {
                return
            }
        }
    }

    /// Runs `action` with the data once it has loaded (right away if it has); never if the load fails.
    func whenLoaded(_ action: @escaping @MainActor (HealthData) -> Void) {
        switch phase {
        case .loaded: action(data)
        case .loading: loadObservers.append(action)
        case .failed: break
        }
    }

    // MARK: Loading

    private func load() {
        guard loadTask == nil else { return }
        let repository = self.repository
        loadTask = Task {
            let loaded: HealthData?
            do { loaded = try await repository.load() } catch { loaded = nil }
            self.finishLoading(loaded)
            self.loadTask = nil
        }
    }

    private func finishLoading(_ loaded: HealthData?) {
        let observers = loadObservers
        let responses = deferredResponses
        loadObservers = []
        deferredResponses = []
        guard let loaded else {
            phase = .failed
            notify()
            enqueue(.reconcile)
            return
        }
        phase = .loaded
        var copy = loaded
        let clock = now
        for (response, slot) in responses { _ = copy.respond(response, slot: slot, clock: clock) }
        data = copy
        if copy != loaded { requestSave() }
        notify()
        for observer in observers { observer(data) }
        enqueue(.reconcile)
    }

    private func clockChanged() {
        notify()
        enqueue(.reconcile)
    }

    /// Today's `Retry`: load again after a load error, save again after a save error, otherwise nothing.
    func retry() {
        switch phase {
        case .failed: load()
        case .loaded where saveError: requestSave()
        case .loaded, .loading: break
        }
    }

    // MARK: Mutations (no-ops unless the data loaded)

    private func mutate<Result>(_ fallback: Result, _ change: (inout HealthData) -> Result) -> Result {
        guard phase == .loaded else { return fallback }
        var copy = data
        let result = change(&copy)
        if copy != data {
            data = copy
            requestSave()
            notify()
            enqueue(.reconcile)
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

    /// Returns the saved medication's id, or nil when nothing can be saved (the data didn't load, or the edited
    /// medication no longer exists).
    func save(_ draft: MedicationDraft) -> String? {
        let clock = now
        return mutate(nil) { $0.save(draft, clock: clock) }
    }

    func archive(medicationId: String) {
        let today = now.today
        _ = mutate(false) { $0.archive(medicationId: medicationId, today: today) }
    }

    func restore(medicationId: String) {
        let clock = now
        _ = mutate(false) { $0.restore(medicationId: medicationId, clock: clock) }
    }

    func delete(medicationId: String) {
        _ = mutate(false) { $0.delete(medicationId: medicationId) }
    }

    /// A notification response. Before the first load finishes it is queued and applied once the data is there
    /// (dropped if the load fails). Returns when it has been applied, saved and the reminders reconciled.
    func respond(_ response: ReminderResponse, slot: String) async {
        switch phase {
        case .loading:
            deferredResponses.append((response, slot))
        case .loaded:
            let clock = now
            _ = mutate(false) { $0.respond(response, slot: slot, clock: clock) }
        case .failed:
            break
        }
        await settle()
    }

    // MARK: Reminders

    /// Asks for notification permission (the scheduler only asks while it's `notDetermined`), then reconciles.
    func requestPermission() { enqueue(.requestPermission) }

    func openSettings() { enqueue(.openSettings) }

    private func enqueue(_ job: SchedulerJob) {
        if job == .reconcile, schedulerJobs.contains(.reconcile) { return }   // the queued one reads the latest data
        schedulerJobs.append(job)
        guard schedulerTask == nil else { return }
        schedulerTask = Task {
            while !self.schedulerJobs.isEmpty {
                await self.run(self.schedulerJobs.removeFirst())
            }
            self.schedulerTask = nil
        }
    }

    private func run(_ job: SchedulerJob) async {
        let scheduler = self.scheduler
        switch job {
        case .reconcile:
            let permission = await scheduler.permission()
            let mayBeLate = await scheduler.mayBeLate()
            // Without readable data Core hands nothing over (it would cancel reminders it can't see).
            if phase == .loaded {
                let plan = ReminderPlanner.plan(data, clock: now, permission: permission)
                await scheduler.replaceAll(plan.reminders, openSlots: plan.openSlots)
            }
            let pending = await scheduler.pending()
            self.permission = permission
            self.mayBeLate = mayBeLate
            self.pending = pending
            notify()
        case .requestPermission:
            permission = await scheduler.requestPermission()
            notify()
            enqueue(.reconcile)
        case .openSettings:
            await scheduler.openSettings()
        }
    }

    // MARK: Persistence and notification

    private func requestSave() {
        isSaveRequested = true
        guard saveTask == nil else { return }
        let repository = self.repository
        saveTask = Task {
            while self.isSaveRequested {
                self.isSaveRequested = false
                let failed: Bool
                do {
                    try await repository.save(self.data)
                    failed = false
                } catch {
                    failed = true
                }
                if self.saveError != failed {
                    self.saveError = failed
                    self.notify()
                }
            }
            self.saveTask = nil
        }
    }

    private func notify() {
        subscribers.removeAll { $0.owner == nil }
        let snapshot = self.snapshot
        for subscriber in subscribers { subscriber.deliver(snapshot) }
    }
}
