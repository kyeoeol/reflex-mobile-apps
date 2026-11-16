import AppCore
import SwiftUI

/// History (docs/design/DESIGN.md "History"): one day at a time. The day picker starts at the state's `date` and
/// has no view-side bound (Core ignores a future `SelectDate`).
struct HistoryView: View {
    let viewModel: HistoryViewModel
    @State private var takeAt: TakeAtRequest?
    @State private var isPickingDate = false

    var body: some View {
        let state = viewModel.state
        ScrollView {
            VStack(alignment: .leading, spacing: HealthSpacing.lg) {
                HealthTopBar(title: "Health Beta")
                ScreenTitle(text: "History")
                if state.isLoading {
                    LoadingIndicator()
                } else {
                    HistoryDayPager(identifierPrefix: "history", dateLabel: HealthFormat.mediumDate(state.date),
                                    canGoNext: state.canGoNext,
                                    onPrevious: { viewModel.send(action: .previousDay) },
                                    onNext: { viewModel.send(action: .nextDay) },
                                    onSelectDate: { isPickingDate = true })
                    if let summary = state.summaryLabel {
                        Text(summary).font(HealthTypography.meta).foregroundStyle(HealthColors.secondary)
                            .accessibilityIdentifier("history.summary")
                    }
                    if let error = state.doseError {
                        InlineErrorBanner(message: error).accessibilityIdentifier("history.doseError")
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(state.doses, id: \.id) { dose in
                            HealthDoseRow(prefix: "history", dose: dose, meta: dose.time ?? dose.takenAt ?? "", onCommand: handle)
                        }
                    }
                    if let message = state.message {
                        Text(message).font(HealthTypography.smallBody).foregroundStyle(HealthColors.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(HealthSpacing.md)
                            .background(HealthColors.secondaryBackground, in: RoundedRectangle(cornerRadius: HealthSpacing.panelRadius))
                            .accessibilityIdentifier("history.message")
                    }
                }
            }
            .padding(.horizontal, HealthSpacing.screen).padding(.bottom, HealthSpacing.xl)
        }
        .background(HealthColors.background)
        .navigationTitle("History")
        .toolbar(.hidden, for: .navigationBar)
        .sheet(item: $takeAt) { request in
            TakeAtSheet(request: request) { viewModel.send(action: .takeAt(doseId: request.id, takenAt: $0)) }
        }
        .sheet(isPresented: $isPickingDate) {
            DatePickerSheet(title: "Choose a day", start: HealthFormat.date(state.date) ?? HealthFormat.defaultTime,
                            identifier: "history.datePicker") { viewModel.send(action: .selectDate($0)) }
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
