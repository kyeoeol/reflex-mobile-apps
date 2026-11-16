import AppCore
import SwiftUI

/// Medications (docs/design/DESIGN.md "Medications"): Active, Ended, Archived in Core's order; Add floats over the
/// list, and moves into the flow at accessibility text sizes.
struct MedicationsView: View {
    let viewModel: MedicationsViewModel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let state = viewModel.state
        let floatsAdd = !dynamicTypeSize.isAccessibilitySize && !state.isEmpty
        ScrollView {
            VStack(alignment: .leading, spacing: HealthSpacing.lg) {
                HealthTopBar(title: "Health Beta")
                ScreenTitle(text: "Medications")
                if state.isLoading { LoadingIndicator() }
                if state.isEmpty {
                    VStack(spacing: HealthSpacing.md) {
                        Image(systemName: "pills").font(.system(size: 28, weight: .ultraLight)).accessibilityHidden(true)
                        addButton
                    }
                    .frame(maxWidth: .infinity).padding(HealthSpacing.xl)
                    .background(HealthColors.secondaryBackground, in: RoundedRectangle(cornerRadius: HealthSpacing.panelRadius))
                    .accessibilityElement(children: .contain).accessibilityIdentifier("medications.empty")
                }
                section("Active", id: "active", rows: state.active)
                section("Ended", id: "ended", rows: state.ended)
                section("Archived", id: "archived", rows: state.archived)
                if !floatsAdd && !state.isEmpty { addButton }
            }
            .padding(.horizontal, HealthSpacing.screen)
            .padding(.bottom, floatsAdd ? 80 : HealthSpacing.xl)
        }
        .background(HealthColors.background)
        .overlay(alignment: .bottomTrailing) {
            if floatsAdd {
                FloatingPillButton(title: "Add") { viewModel.send(action: .add) }
                    .accessibilityLabel("Add medication").accessibilityIdentifier("medications.add")
                    .padding(HealthSpacing.screen)
            }
        }
        .navigationTitle("Medications")
        .toolbar(.hidden, for: .navigationBar)
    }

    private var addButton: some View {
        Button { viewModel.send(action: .add) } label: { Label("Add medication", systemImage: "plus") }
            .buttonStyle(PillButtonStyle()).accessibilityIdentifier("medications.add")
    }

    @ViewBuilder
    private func section(_ title: String, id: String, rows: [MedicationsViewModel.Row]) -> some View {
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader(title: title).padding(.bottom, HealthSpacing.xs)
                ForEach(rows, id: \.id) { row in
                    Button { viewModel.send(action: .open(id: row.id)) } label: {
                        VStack(alignment: .leading, spacing: 0) {
                            HairlineDivider()
                            VStack(alignment: .leading, spacing: 6) {
                                Text(row.name).font(HealthTypography.row).foregroundStyle(HealthColors.primary)
                                Text([row.dose, HealthFormat.kindLabel(row.kind)].filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(HealthTypography.caption).foregroundStyle(HealthColors.secondary)
                                Text(row.schedule).font(HealthTypography.caption).foregroundStyle(HealthColors.body)
                            }
                            .padding(.vertical, HealthSpacing.md)
                        }
                        .frame(maxWidth: .infinity, minHeight: HealthSpacing.controlHeight, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("medications.item.\(row.id)")
                }
            }
            .accessibilityElement(children: .contain).accessibilityIdentifier("medications.section.\(id)")
        }
    }
}
