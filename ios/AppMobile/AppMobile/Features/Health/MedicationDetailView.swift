import AppCore
import SwiftUI

/// Medication detail (docs/design/DESIGN.md "MedicationDetail"). Recent history is read-only (no dose actions on
/// this screen). Delete asks through a native alert bound to `isConfirmingDelete`.
struct MedicationDetailView: View {
    let viewModel: MedicationDetailViewModel

    var body: some View {
        let state = viewModel.state
        ScrollView {
            VStack(alignment: .leading, spacing: HealthSpacing.lg) {
                VStack(alignment: .leading, spacing: HealthSpacing.xs) {
                    HStack(spacing: 6) {
                        Text(HealthFormat.kindLabel(state.kind))
                        Text("·").accessibilityHidden(true)
                        Text(HealthFormat.medicationStatusLabel(state.status)).accessibilityIdentifier("detail.status")
                    }
                    .font(HealthTypography.meta).tracking(0.52).foregroundStyle(HealthColors.secondary)
                    Text(state.name).font(HealthTypography.title).foregroundStyle(HealthColors.primary)
                        .accessibilityAddTraits(.isHeader).accessibilityIdentifier("detail.name")
                    if !state.dose.isEmpty {
                        Text(state.dose).font(HealthTypography.body).foregroundStyle(HealthColors.body)
                    }
                }
                VStack(alignment: .leading, spacing: HealthSpacing.xs) {
                    Text(state.schedule).font(HealthTypography.row).foregroundStyle(HealthColors.primary)
                        .accessibilityIdentifier("detail.schedule")
                    Text(dateRange(state)).font(HealthTypography.caption).foregroundStyle(HealthColors.secondary)
                    if !state.notes.isEmpty {
                        Text(state.notes).font(HealthTypography.smallBody).foregroundStyle(HealthColors.body)
                    }
                }
                VStack(alignment: .leading, spacing: HealthSpacing.xxs) {
                    Text("Adherence").font(HealthTypography.meta).foregroundStyle(HealthColors.secondary)
                    if let label = state.adherenceLabel {
                        Text(label).font(HealthTypography.display).foregroundStyle(HealthColors.primary)
                    }
                    Text(state.adherenceCaption).font(HealthTypography.caption).foregroundStyle(HealthColors.secondary)
                }
                .accessibilityElement(children: .combine).accessibilityIdentifier("detail.adherence")
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: HealthSpacing.xs) { lifecycleButtons(state) }
                    VStack(alignment: .leading, spacing: HealthSpacing.xs) { lifecycleButtons(state) }
                }
                if !state.history.isEmpty {
                    VStack(alignment: .leading, spacing: 0) {
                        SectionHeader(title: "Recent history").padding(.bottom, HealthSpacing.xs)
                        ForEach(state.history, id: \.id) { dose in
                            VStack(alignment: .leading, spacing: HealthSpacing.xs) {
                                ListRow(title: dose.name, meta: historyMeta(dose), detail: dose.dose.isEmpty ? nil : dose.dose)
                                StatusChip(kind: DoseStatusKind(status: dose.status))
                                    .accessibilityIdentifier("detail.dose.\(dose.id).status")
                                    .padding(.bottom, HealthSpacing.sm)
                            }
                            .accessibilityElement(children: .contain).accessibilityIdentifier("detail.dose.\(dose.id)")
                        }
                    }
                }
            }
            .padding(.horizontal, HealthSpacing.screen).padding(.bottom, HealthSpacing.xl)
        }
        .background(HealthColors.background)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Edit") { viewModel.send(action: .edit) }.accessibilityIdentifier("detail.edit")
            }
        }
        .modifier(HealthConfirmationDialog(
            isPresented: state.isConfirmingDelete, title: "Delete medication?",
            message: "This removes the medication and its dose history. This cannot be undone.", confirmTitle: "Delete",
            onConfirm: { viewModel.send(action: .confirmDelete) }, onDismiss: { viewModel.send(action: .cancelDelete) }))
    }

    @ViewBuilder
    private func lifecycleButtons(_ state: MedicationDetailViewModel.State) -> some View {
        if state.canArchive {
            Button("Archive") { viewModel.send(action: .archive) }
                .buttonStyle(PillButtonStyle(kind: .outline)).accessibilityIdentifier("detail.archive")
        }
        if state.canRestore {
            Button("Restore") { viewModel.send(action: .restore) }
                .buttonStyle(PillButtonStyle(kind: .outline)).accessibilityIdentifier("detail.restore")
        }
        Button("Delete") { viewModel.send(action: .delete) }
            .buttonStyle(PillButtonStyle(kind: .destructive)).accessibilityIdentifier("detail.delete")
    }

    private func dateRange(_ state: MedicationDetailViewModel.State) -> String {
        let start = HealthFormat.mediumDate(state.startDate)
        guard let end = state.endDate else { return "Since \(start)" }
        return "\(start) – \(HealthFormat.mediumDate(end))"
    }

    /// "Nov 16, 2026 · 08:00"; as-needed entries show their taken time.
    private func historyMeta(_ dose: DoseItem) -> String {
        let time = dose.time ?? dose.takenAt
        return [HealthFormat.mediumDate(dose.date), time].compactMap { $0 }.joined(separator: " · ")
    }
}
