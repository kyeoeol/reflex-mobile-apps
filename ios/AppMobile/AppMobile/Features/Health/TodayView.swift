import AppCore
import SwiftUI

/// Today (docs/design/DESIGN.md "Today"). Reads `TodayViewModel.state`, sends its actions; nothing else.
struct TodayView: View {
    let viewModel: TodayViewModel
    @State private var takeAt: TakeAtRequest?

    var body: some View {
        let state = viewModel.state
        ScrollView {
            VStack(alignment: .leading, spacing: HealthSpacing.lg) {
                HealthTopBar(title: "Health Beta", actionTitle: "Reminders", onAction: { viewModel.send(action: .openReminders) },
                             actionSymbol: "bell", actionIdentifier: "today.openReminders")
                VStack(alignment: .leading, spacing: HealthSpacing.xs) {
                    ScreenTitle(text: "Today")
                    if !state.date.isEmpty {
                        Text(HealthFormat.longDate(state.date)).font(HealthTypography.body).foregroundStyle(HealthColors.secondary)
                            .accessibilityIdentifier("today.date")
                    }
                }
                if state.isLoading { LoadingIndicator() }
                if let progress = state.progressLabel {
                    ProgressLine(fraction: state.scheduledCount > 0 ? Double(state.takenCount) / Double(state.scheduledCount) : 0,
                                 label: progress)
                        .accessibilityElement(children: .ignore).accessibilityLabel(progress)
                        .accessibilityIdentifier("today.progress")
                }
                messagePanel(state)
                if let banner = state.reminderBanner {
                    Banner(title: banner, actionTitle: state.reminderBannerAction, identifier: "today.banner") {
                        viewModel.send(action: .turnOnReminders)
                    }
                }
                if let error = state.doseError {
                    InlineErrorBanner(message: error).accessibilityIdentifier("today.doseError")
                }
                ForEach(state.groups, id: \.key) { group in
                    VStack(alignment: .leading, spacing: HealthSpacing.xs) {
                        Text(group.label).font(HealthTypography.section).foregroundStyle(HealthColors.primary)
                            .accessibilityAddTraits(.isHeader)
                        ForEach(group.doses, id: \.id) { dose in
                            HealthDoseRow(prefix: "today", dose: dose, meta: "", onCommand: handle)
                        }
                    }
                    .accessibilityElement(children: .contain).accessibilityIdentifier("today.group.\(group.key)")
                }
                if !state.asNeeded.isEmpty {
                    VStack(alignment: .leading, spacing: HealthSpacing.xs) {
                        SectionHeader(title: "As needed")
                        ForEach(state.asNeeded, id: \.medicationId) { item in
                            AsNeededRow(identifier: "today.asNeeded.\(item.medicationId)", name: item.name, dose: item.dose,
                                        summary: item.summary, canUndo: item.canUndo,
                                        onLog: { viewModel.send(action: .logAsNeeded(medicationId: item.medicationId)) },
                                        onUndo: { viewModel.send(action: .undoAsNeeded(medicationId: item.medicationId)) })
                        }
                    }
                }
            }
            .padding(.horizontal, HealthSpacing.screen).padding(.bottom, HealthSpacing.xl)
        }
        .background(HealthColors.background)
        .navigationTitle("Today")
        .toolbar(.hidden, for: .navigationBar)
        .sheet(item: $takeAt) { request in
            TakeAtSheet(request: request) { viewModel.send(action: .takeAt(doseId: request.id, takenAt: $0)) }
        }
    }

    /// The message treatment is chosen by `messageKind`, never by matching the text or counting rows.
    @ViewBuilder
    private func messagePanel(_ state: TodayViewModel.State) -> some View {
        if let message = state.message {
            switch state.messageKind {
            case "loadError":
                EmptyStateView(message: message, symbol: "exclamationmark.triangle", actionTitle: "Retry",
                               messageIdentifier: "today.message", actionIdentifier: "today.retry") {
                    viewModel.send(action: .retry)
                }
            case "saveError":
                VStack(alignment: .leading, spacing: HealthSpacing.sm) {
                    Text(message).font(HealthTypography.smallBody).foregroundStyle(HealthColors.destructive)
                        .accessibilityIdentifier("today.message")
                    Button("Retry") { viewModel.send(action: .retry) }
                        .buttonStyle(PillButtonStyle(kind: .outline)).accessibilityIdentifier("today.retry")
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(HealthSpacing.md)
                .background(HealthColors.destructiveBackground, in: RoundedRectangle(cornerRadius: HealthSpacing.panelRadius))
            case "noMedications":
                EmptyStateView(message: message, actionTitle: "Add medication", messageIdentifier: "today.message",
                               actionIdentifier: "today.addMedication") {
                    viewModel.send(action: .addMedication)
                }
            case "nothingToday":
                EmptyStateView(message: message, symbol: "calendar", messageIdentifier: "today.message")
            default:
                Text(message).font(HealthTypography.smallBody).foregroundStyle(HealthColors.secondary)
                    .accessibilityIdentifier("today.message")
            }
        }
    }

    private func handle(_ command: DoseCommand) {
        switch command {
        case .take(let id): viewModel.send(action: .take(doseId: id))
        case .pickTakenAt(let request): takeAt = request
        case .skip(let id): viewModel.send(action: .skip(doseId: id))
        case .undo(let id): viewModel.send(action: .undo(doseId: id))
        }
    }
}
