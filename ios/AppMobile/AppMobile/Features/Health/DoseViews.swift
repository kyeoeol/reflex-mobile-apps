import AppCore
import SwiftUI

/// What a dose row asks its screen for. The screen turns it into its own `Take` / `TakeAt` / `Skip` / `Undo` action.
enum DoseCommand {
    case take(doseId: String)
    case pickTakenAt(TakeAtRequest)
    case skip(doseId: String)
    case undo(doseId: String)
}

/// "Change time…": the picker starts at the dose's own date and time (views have no clock).
struct TakeAtRequest: Identifiable {
    let id: String
    let start: Date

    init?(dose: DoseItem) {
        guard let time = dose.time, let start = HealthFormat.dateTime(date: dose.date, time: time) else { return nil }
        self.id = dose.id
        self.start = start
    }
}

/// A `DoseItem` as a `DoseRow`; every control is gated by the item's own flags.
struct HealthDoseRow: View {
    let prefix: String
    let dose: DoseItem
    let meta: String
    let onCommand: (DoseCommand) -> Void

    var body: some View {
        DoseRow(
            identifier: "\(prefix).dose.\(dose.id)", name: dose.name, dose: dose.dose, meta: meta,
            status: DoseStatusKind(status: dose.status), timing: timingText, needsAction: dose.needsAction,
            canTake: dose.canTake, canTakeAt: dose.canTakeAt, canSkip: dose.canSkip, canUndo: dose.canUndo,
            onTake: { onCommand(.take(doseId: dose.id)) },
            onTakeAt: { if let request = TakeAtRequest(dose: dose) { onCommand(.pickTakenAt(request)) } },
            onSkip: { onCommand(.skip(doseId: dose.id)) },
            onUndo: { onCommand(.undo(doseId: dose.id)) }
        )
    }

    /// "Taken at 08:10 · On time", from the supplied `takenAt` and `timing`.
    private var timingText: String? {
        let parts = [dose.takenAt.map { "Taken at \($0)" }, HealthFormat.timingLabel(dose.timing)].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

/// The native date-and-time picker behind "Change time…". Save sends `TakeAt` (`yyyy-MM-ddTHH:mm`); Cancel sends
/// nothing.
struct TakeAtSheet: View {
    let request: TakeAtRequest
    let onSave: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var value: Date

    init(request: TakeAtRequest, onSave: @escaping (String) -> Void) {
        self.request = request
        self.onSave = onSave
        _value = State(initialValue: request.start)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("When did you take it?").font(HealthTypography.row)
                    DatePicker("Taken at", selection: $value, displayedComponents: [.date, .hourAndMinute])
                        .datePickerStyle(.wheel).labelsHidden()
                        .environment(\.locale, HealthFormat.pickerLocale)
                        .environment(\.timeZone, HealthFormat.pickerTimeZone)
                        .environment(\.calendar, HealthFormat.pickerCalendar)
                        .accessibilityIdentifier("takeAt.picker")
                }.padding(HealthSpacing.screen)
            }
            .navigationTitle("Change time")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.accessibilityIdentifier("takeAt.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(HealthFormat.payloadDateTime(value))
                        dismiss()
                    }.accessibilityIdentifier("takeAt.save")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// A native date picker sheet (editor dates, History's day). Done sends the chosen `yyyy-MM-dd`; Cancel nothing.
struct DatePickerSheet: View {
    let title: String
    let start: Date
    let identifier: String
    let onDone: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var value: Date

    init(title: String, start: Date, identifier: String, onDone: @escaping (String) -> Void) {
        self.title = title
        self.start = start
        self.identifier = identifier
        self.onDone = onDone
        _value = State(initialValue: start)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                DatePicker(title, selection: $value, displayedComponents: .date)
                    .datePickerStyle(.graphical).labelsHidden()
                    .environment(\.timeZone, HealthFormat.pickerTimeZone)
                    .environment(\.calendar, HealthFormat.pickerCalendar)
                    .accessibilityIdentifier(identifier)
                    .padding(HealthSpacing.screen)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.accessibilityIdentifier("\(identifier).cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        onDone(HealthFormat.payloadDate(value))
                        dismiss()
                    }.accessibilityIdentifier("\(identifier).confirm")
                }
            }
        }
        .presentationDetents([.large])
    }
}

/// A screen's large title.
struct ScreenTitle: View {
    let text: String
    var body: some View {
        Text(text).font(HealthTypography.title).foregroundStyle(HealthColors.primary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A labeled native loading indicator (never an invented empty state).
struct LoadingIndicator: View {
    var body: some View {
        ProgressView("Loading").frame(maxWidth: .infinity).padding(.vertical, HealthSpacing.lg)
    }
}
