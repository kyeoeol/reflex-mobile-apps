import AppCore
import SwiftUI

/// Reminders (docs/design/DESIGN.md "Reminders"): the literal status, Turn on reminders (Core decides between
/// asking, opening Settings and doing nothing), and the scheduler's pending list as read back from the OS.
struct RemindersView: View {
    let viewModel: RemindersViewModel

    var body: some View {
        let state = viewModel.state
        ScrollView {
            VStack(alignment: .leading, spacing: HealthSpacing.lg) {
                ScreenTitle(text: "Reminders")
                VStack(alignment: .leading, spacing: HealthSpacing.sm) {
                    Text(state.statusLabel).font(HealthTypography.row).foregroundStyle(HealthColors.primary)
                        .accessibilityIdentifier("reminders.status")
                    if let action = state.actionLabel {
                        Button(action) { viewModel.send(action: .turnOnReminders) }
                            .buttonStyle(PillButtonStyle(kind: .outline)).accessibilityIdentifier("reminders.turnOn")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(HealthSpacing.md)
                .background(HealthColors.secondaryBackground, in: RoundedRectangle(cornerRadius: HealthSpacing.panelRadius))
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(state.reminders, id: \.id) { reminder in
                        ReminderRow(kind: reminder.kind, fireAtLabel: HealthFormat.fireAtLabel(reminder.fireAt),
                                    title: reminder.title, bodyText: reminder.body)
                            .accessibilityIdentifier("reminders.item.\(reminder.id)")
                    }
                }
            }
            .padding(.horizontal, HealthSpacing.screen).padding(.bottom, HealthSpacing.xl)
        }
        .background(HealthColors.background)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
    }
}
