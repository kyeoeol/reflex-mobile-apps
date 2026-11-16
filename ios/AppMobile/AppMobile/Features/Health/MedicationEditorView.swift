import AppCore
import SwiftUI

/// The medication editor (docs/design/DESIGN.md "MedicationEditor"): a full-screen form with Cancel on top (the
/// navigator's `goBack`) and Save pinned above the keyboard. Save is enabled by `canSave` alone; `isValid` drives
/// nothing; errors are exactly the supplied ones.
struct MedicationEditorView: View {
    let viewModel: MedicationEditorViewModel
    let onCancel: () -> Void
    @State private var isPickingTime = false
    @State private var pickingDate: DateField?

    enum DateField: String, Identifiable {
        case start, end
        var id: String { rawValue }
    }

    var body: some View {
        let state = viewModel.state
        ScrollView {
            VStack(alignment: .leading, spacing: HealthSpacing.lg) {
                ScreenTitle(text: state.mode == "edit" ? "Edit medication" : "Add medication")
                HealthTextField(label: "Name", value: state.name, error: state.nameError, identifier: "editor.name",
                                errorIdentifier: "editor.error.name") { viewModel.send(action: .setName($0)) }
                field("Kind") {
                    PillSegmentedControl(labels: ["Medication", "Supplement"], selectedIndex: state.kind == "supplement" ? 1 : 0,
                                         identifiers: ["editor.kind.medication", "editor.kind.supplement"]) { index in
                        viewModel.send(action: .setKind(index == 1 ? "supplement" : "medication"))
                    }
                }
                HealthTextField(label: "Dose", value: state.dose, placeholder: "e.g. 500 mg", identifier: "editor.dose") {
                    viewModel.send(action: .setDose($0))
                }
                PillToggle(title: "As needed", isOn: state.asNeeded, isEnabled: state.canChangeAsNeeded) {
                    viewModel.send(action: .setAsNeeded(!state.asNeeded))
                }.accessibilityIdentifier("editor.asNeeded")
                if !state.asNeeded {
                    times(state)
                    field("Days") {
                        WeekdayPicker(selectedWeekdays: state.weekdays, identifierPrefix: "editor.weekday") {
                            viewModel.send(action: .toggleWeekday($0))
                        }
                        errorText(state.weekdaysError, id: "editor.error.weekdays")
                    }
                }
                if state.asNeeded { errorText(state.timeError, id: "editor.error.time") }
                field("Start date") {
                    PillSelect(label: "Start date", title: HealthFormat.mediumDate(state.startDate)) { pickingDate = .start }
                        .accessibilityIdentifier("editor.startDate")
                }
                field("End date") {
                    PillSelect(label: "End date", title: state.endDate.map(HealthFormat.mediumDate) ?? "No end date") {
                        pickingDate = .end
                    }.accessibilityIdentifier("editor.endDate")
                    if state.endDate != nil {
                        Button("Clear end date") { viewModel.send(action: .setEndDate(nil)) }
                            .buttonStyle(PillButtonStyle(kind: .outline)).accessibilityIdentifier("editor.endDate.clear")
                    }
                    errorText(state.datesError, id: "editor.error.dates")
                }
                HealthTextField(label: "Notes", value: state.notes, multiline: true, identifier: "editor.notes") {
                    viewModel.send(action: .setNotes($0))
                }
            }
            .padding(.horizontal, HealthSpacing.screen).padding(.bottom, HealthSpacing.lg)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(HealthColors.background)
        .safeAreaInset(edge: .bottom) {
            Button { viewModel.send(action: .save) } label: {
                HStack(spacing: HealthSpacing.xs) {
                    if state.isSaving { ProgressView().tint(HealthColors.onPrimary) }
                    Text("Save")
                }.frame(maxWidth: .infinity)
            }
            .buttonStyle(PillButtonStyle()).disabled(!state.canSave)
            .accessibilityIdentifier("editor.save")
            .padding(.horizontal, HealthSpacing.screen).padding(.vertical, HealthSpacing.sm)
            .background(HealthColors.background)
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel", action: onCancel).accessibilityIdentifier("editor.cancel")
            }
        }
        .toolbar(.hidden, for: .tabBar)
        .sheet(isPresented: $isPickingTime) {
            TimePickerSheet { viewModel.send(action: .addTime($0)) }
        }
        .sheet(item: $pickingDate) { field in
            switch field {
            case .start:
                DatePickerSheet(title: "Start date", start: HealthFormat.date(state.startDate) ?? HealthFormat.defaultTime,
                                identifier: "editor.startDatePicker") { viewModel.send(action: .setStartDate($0)) }
            case .end:
                DatePickerSheet(title: "End date",
                                start: HealthFormat.date(state.endDate ?? state.startDate) ?? HealthFormat.defaultTime,
                                identifier: "editor.endDatePicker") { viewModel.send(action: .setEndDate($0)) }
            }
        }
    }

    @ViewBuilder
    private func times(_ state: MedicationEditorViewModel.State) -> some View {
        field("Times") {
            ForEach(state.times, id: \.self) { time in
                HStack {
                    Text(time).font(HealthTypography.row).foregroundStyle(HealthColors.primary)
                        .accessibilityIdentifier("editor.time.\(time)")
                    Spacer(minLength: HealthSpacing.sm)
                    Button { viewModel.send(action: .removeTime(time)) } label: {
                        Image(systemName: "xmark").accessibilityHidden(true)
                    }
                    .buttonStyle(PillButtonStyle(kind: .outline, horizontalPadding: 0))
                    .accessibilityLabel("Remove \(time)").accessibilityIdentifier("editor.time.\(time).remove")
                }
                .padding(.leading, HealthSpacing.md).padding(.trailing, HealthSpacing.xxs).padding(.vertical, HealthSpacing.xxs)
                .background(HealthColors.inputBackground, in: Capsule())
            }
            Button { isPickingTime = true } label: { Label("Add time", systemImage: "plus") }
                .buttonStyle(PillButtonStyle(kind: .outline)).disabled(!state.canAddTime)
                .accessibilityIdentifier("editor.addTime")
            errorText(state.timesError, id: "editor.error.times")
            errorText(state.timeError, id: "editor.error.time")
        }
    }

    private func field<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: HealthSpacing.xs) {
            Text(label).font(HealthTypography.meta).foregroundStyle(HealthColors.primary).accessibilityAddTraits(.isHeader)
            content()
        }
    }

    @ViewBuilder
    private func errorText(_ error: String?, id: String) -> some View {
        if let error {
            Text(error).font(HealthTypography.caption).foregroundStyle(HealthColors.destructive)
                .accessibilityIdentifier(id)
                .onAppear { AccessibilityNotification.Announcement(error).post() }
        }
    }
}

/// The Add time wheel: 24-hour, starting at a fixed 08:00. Add sends `AddTime {time: "HH:mm"}`; Cancel nothing.
struct TimePickerSheet: View {
    let onAdd: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var value = HealthFormat.defaultTime

    var body: some View {
        NavigationStack {
            ScrollView {
                DatePicker("Time", selection: $value, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.wheel).labelsHidden()
                    .environment(\.locale, HealthFormat.pickerLocale)
                    .environment(\.timeZone, HealthFormat.pickerTimeZone)
                    .environment(\.calendar, HealthFormat.pickerCalendar)
                    .accessibilityIdentifier("editor.timePicker")
                    .frame(maxWidth: .infinity)
                    .padding(HealthSpacing.screen)
            }
            .navigationTitle("Add time")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.accessibilityIdentifier("editor.timePicker.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        onAdd(HealthFormat.payloadTime(value))
                        dismiss()
                    }.accessibilityIdentifier("editor.timePicker.confirm")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
