import Foundation
import Observation

/// Reminders (pushed from Today): notification permission and the reminders actually pending in the scheduler.
@MainActor
public final class RemindersViewModel: ViewModel {
    public struct Item: Sendable, Equatable {
        public let id: String
        public let kind: String
        /// `2026-11-16T08:00:00+09:00`, in the offset the current time zone has at that instant.
        public let fireAt: String
        public let title: String
        public let body: String
    }

    @MainActor @Observable public final class State {
        public fileprivate(set) var permission = NotificationPermission.notDetermined.rawValue
        public fileprivate(set) var mayBeLate = false
        public fileprivate(set) var statusLabel = ""
        public fileprivate(set) var reminders: [Item] = []
    }

    public enum Action {
        case turnOnReminders
    }

    public enum Message {
        case viewAction(Action)
        case storeChanged(HealthSnapshot)
    }

    public let state = State()
    private let store: HealthStore
    private var snapshot: HealthSnapshot

    init(store: HealthStore) {
        self.store = store
        self.snapshot = store.snapshot
        store.subscribe(self) { [weak self] in self?.send(message: .storeChanged($0)) }
        send(message: .storeChanged(store.snapshot))
    }

    public func send(action: Action) { send(message: .viewAction(action)) }

    public func update(state: State, message: Message) {
        switch message {
        case .viewAction(.turnOnReminders):
            switch ReminderSetupStep.turnOn(permission: snapshot.permission, mayBeLate: snapshot.mayBeLate) {
            case .requestPermission: store.requestPermission()
            case .openSettings: store.openSettings()
            case .none: break
            }
        case .storeChanged(let snapshot):
            self.snapshot = snapshot
            state.permission = snapshot.permission.rawValue
            state.mayBeLate = snapshot.mayBeLate
            state.statusLabel = switch (snapshot.permission, snapshot.mayBeLate) {
            case (.authorized, false): "Reminders are on"
            case (.authorized, true): "Reminders may arrive a few minutes late"
            case (.denied, _): "Reminders are off. Turn them on in Settings."
            case (.notDetermined, _): "Reminders aren't set up yet"
            }
            state.reminders = snapshot.pending.map { reminder in
                Item(id: reminder.id, kind: reminder.kind, fireAt: HealthTime.format(reminder.fireAt, in: snapshot.zone),
                     title: reminder.title, body: reminder.body)
            }
        }
    }
}
