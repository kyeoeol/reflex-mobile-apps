import SwiftUI

/// Controlled toggle; selection and editability come from the screen's state.
struct PillToggle: View {
    let title: String
    let isOn: Bool
    var isEnabled: Bool = true
    var accessibilityTitle: String? = nil
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) { Text(title).frame(maxWidth: .infinity) }
            .buttonStyle(PillButtonStyle(kind: isOn ? .primary : .outline, isControl: true, horizontalPadding: 8))
            .disabled(!isEnabled)
            .accessibilityLabel(accessibilityTitle ?? title)
            .accessibilityValue(isOn ? "On" : "Off")
            .accessibilityRemoveTraits(.isButton)
            .accessibilityAddTraits(.isToggle)
    }
}

struct WeekdayPicker: View {
    let selectedWeekdays: [Int]
    let onToggle: (Int) -> Void
    @ScaledMetric(relativeTo: .footnote) private var minimumWidth = 48.0
    private let labels = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]
    private let names = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: max(48, minimumWidth)), spacing: 4)], spacing: 8) {
            ForEach(labels.indices, id: \.self) { index in
                PillToggle(title: labels[index], isOn: selectedWeekdays.contains(index + 1),
                           accessibilityTitle: names[index]) { onToggle(index + 1) }
            }
        }.accessibilityElement(children: .contain).accessibilityLabel("Days")
    }
}

/// No inferred permissions: every visible action is gated by the corresponding supplied flag.
struct DoseRow: View {
    let name: String
    let dose: String
    let meta: String
    let status: DoseStatusKind
    var timing: String? = nil
    let needsAction: Bool
    let canTake: Bool
    let canTakeAt: Bool
    let canSkip: Bool
    let canUndo: Bool
    let onTake: () -> Void
    let onTakeAt: () -> Void
    let onSkip: () -> Void
    let onUndo: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HairlineDivider()
            Menu {
                if canTake { Button("Take now", action: onTake) }
                if canTakeAt { Button("Change time…", action: onTakeAt) }
                if canSkip { Button("Skip", action: onSkip) }
                if canUndo { Button("Undo", action: onUndo) }
            } label: {
                VStack(alignment: .leading, spacing: 6) {
                    Text(meta).font(HealthTypography.meta).foregroundStyle(HealthColors.secondary)
                    Text(name).font(HealthTypography.row)
                    if !dose.isEmpty { Text(dose).font(HealthTypography.caption).foregroundStyle(HealthColors.secondary) }
                    StatusChip(kind: status)
                    if let timing { Text(timing).font(HealthTypography.caption).foregroundStyle(HealthColors.secondary) }
                }.frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .padding(.vertical, 8).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel([name, dose, meta, status.label, timing].compactMap { $0 }.joined(separator: ", "))
            if needsAction {
                ViewThatFits(in: .horizontal) {
                    HStack { quickActions }
                    VStack(alignment: .leading) { quickActions }
                }
            }
        }.foregroundStyle(HealthColors.primary).padding(.bottom, 16)
    }

    @ViewBuilder private var quickActions: some View {
        if canTake { Button("Taken", action: onTake).buttonStyle(PillButtonStyle()).accessibilityLabel("Mark \(name) taken") }
        if canSkip { Button("Skip", action: onSkip).buttonStyle(PillButtonStyle(kind: .outline)).accessibilityLabel("Skip \(name)") }
    }
}

struct AsNeededRow: View {
    let name: String
    let dose: String
    let summary: String
    let canUndo: Bool
    let onLog: () -> Void
    let onUndo: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ListRow(title: name, meta: dose, detail: summary)
            ViewThatFits(in: .horizontal) {
                HStack { actions }
                VStack(alignment: .leading) { actions }
            }
        }
    }
    @ViewBuilder private var actions: some View {
        Button("Log taken", action: onLog).buttonStyle(PillButtonStyle()).accessibilityLabel("Log \(name) taken")
        if canUndo { Button("Undo", action: onUndo).buttonStyle(PillButtonStyle(kind: .outline)).accessibilityLabel("Undo latest \(name) dose") }
    }
}

struct InlineErrorBanner: View {
    let message: String
    var body: some View {
        Text(message).font(HealthTypography.smallBody).foregroundStyle(HealthColors.destructive)
            .frame(maxWidth: .infinity, alignment: .leading).padding(16)
            .background(HealthColors.destructiveBackground, in: RoundedRectangle(cornerRadius: 16))
            .onChange(of: message, initial: true) { _, value in AccessibilityNotification.Announcement(value).post() }
    }
}

/// The date button asks the host to open a native date picker; it does no date arithmetic.
struct HistoryDayPager: View {
    let dateLabel: String
    let canGoNext: Bool
    let onPrevious: () -> Void
    let onNext: () -> Void
    let onSelectDate: () -> Void
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack { previous; date; next }
            VStack { date; HStack { previous; Spacer(); next } }
        }
    }
    private var date: some View { PillSelect(label: "History date", title: dateLabel, onTap: onSelectDate) }
    private var previous: some View {
        Button(action: onPrevious) { Image(systemName: "chevron.left").accessibilityHidden(true) }
            .buttonStyle(PillButtonStyle(kind: .outline)).accessibilityLabel("Previous day")
    }
    private var next: some View {
        Button(action: onNext) { Image(systemName: "chevron.right").accessibilityHidden(true) }
            .buttonStyle(PillButtonStyle(kind: .outline)).disabled(!canGoNext).accessibilityLabel("Next day")
    }
}

struct ReminderRow: View {
    let kind: String
    let fireAtLabel: String
    let title: String
    let bodyText: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ListRow(title: title, meta: fireAtLabel, detail: bodyText)
            Text(kind).font(HealthTypography.caption).foregroundStyle(HealthColors.secondary)
        }.accessibilityElement(children: .combine)
    }
}
