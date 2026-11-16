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
    /// Each day's identifier is `<identifierPrefix>.<1-7>`.
    var identifierPrefix: String = "weekday"
    let onToggle: (Int) -> Void
    @ScaledMetric(relativeTo: .footnote) private var minimumWidth = 48.0
    private let labels = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]
    private let names = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: max(48, minimumWidth)), spacing: 4)], spacing: 8) {
            ForEach(labels.indices, id: \.self) { index in
                PillToggle(title: labels[index], isOn: selectedWeekdays.contains(index + 1),
                           accessibilityTitle: names[index]) { onToggle(index + 1) }
                    .accessibilityIdentifier("\(identifierPrefix).\(index + 1)")
            }
        }.accessibilityElement(children: .contain).accessibilityLabel("Days")
    }
}

/// No inferred permissions: every visible action is gated by the corresponding supplied flag.
/// `identifier` is the row's accessibility identifier (`<screen>.dose.<doseId>`); the status chip and the inline
/// buttons get `.status`, `.take`, `.skip` suffixes, the menu entries `doseMenu.*`.
struct DoseRow: View {
    let identifier: String
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
                if canTake { Button("Take now", action: onTake).accessibilityIdentifier("doseMenu.take") }
                if canTakeAt { Button("Change time…", action: onTakeAt).accessibilityIdentifier("doseMenu.takeAt") }
                if canSkip { Button("Skip", action: onSkip).accessibilityIdentifier("doseMenu.skip") }
                if canUndo { Button("Undo", action: onUndo).accessibilityIdentifier("doseMenu.undo") }
            } label: {
                VStack(alignment: .leading, spacing: 6) {
                    Text(meta).font(HealthTypography.meta).foregroundStyle(HealthColors.secondary)
                    Text(name).font(HealthTypography.row).fixedSize(horizontal: false, vertical: true)
                    if !dose.isEmpty { Text(dose).font(HealthTypography.caption).foregroundStyle(HealthColors.secondary) }
                }.frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .padding(.top, 8).contentShape(Rectangle())
            }.buttonStyle(.plain)
                .accessibilityLabel([name, dose, meta, status.label, timing].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", "))
                .accessibilityHint("Shows dose actions")
                .accessibilityIdentifier(identifier)
            HStack(spacing: 8) {
                StatusChip(kind: status).accessibilityIdentifier("\(identifier).status")
                if let timing { Text(timing).font(HealthTypography.caption).foregroundStyle(HealthColors.secondary) }
            }
            if needsAction {
                ViewThatFits(in: .horizontal) {
                    HStack { quickActions }
                    VStack(alignment: .leading) { quickActions }
                }
            }
        }.foregroundStyle(HealthColors.primary).padding(.bottom, 16)
    }

    @ViewBuilder private var quickActions: some View {
        if canTake {
            Button("Taken", action: onTake).buttonStyle(PillButtonStyle())
                .accessibilityLabel("Mark \(name) taken").accessibilityIdentifier("\(identifier).take")
        }
        if canSkip {
            Button("Skip", action: onSkip).buttonStyle(PillButtonStyle(kind: .outline))
                .accessibilityLabel("Skip \(name)").accessibilityIdentifier("\(identifier).skip")
        }
    }
}

struct AsNeededRow: View {
    /// `today.asNeeded.<medicationId>`; `.summary`, `.log`, `.undo` are suffixes on it.
    let identifier: String
    let name: String
    let dose: String
    let summary: String
    let canUndo: Bool
    let onLog: () -> Void
    let onUndo: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HairlineDivider()
            Text(name).font(HealthTypography.row).foregroundStyle(HealthColors.primary).padding(.top, 8)
            if !dose.isEmpty { Text(dose).font(HealthTypography.caption).foregroundStyle(HealthColors.secondary) }
            Text(summary).font(HealthTypography.caption).foregroundStyle(HealthColors.body)
                .accessibilityIdentifier("\(identifier).summary")
            ViewThatFits(in: .horizontal) {
                HStack { actions }
                VStack(alignment: .leading) { actions }
            }
        }.accessibilityElement(children: .contain).accessibilityIdentifier(identifier)
    }
    @ViewBuilder private var actions: some View {
        Button("Log taken", action: onLog).buttonStyle(PillButtonStyle()).accessibilityLabel("Log \(name) taken")
            .accessibilityIdentifier("\(identifier).log")
        if canUndo {
            Button("Undo", action: onUndo).buttonStyle(PillButtonStyle(kind: .outline)).accessibilityLabel("Undo latest \(name) dose")
                .accessibilityIdentifier("\(identifier).undo")
        }
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
    /// `<identifierPrefix>.date`, `.previous`, `.next`.
    var identifierPrefix: String = "history"
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
    private var date: some View {
        PillSelect(label: "History date", title: dateLabel, onTap: onSelectDate).accessibilityIdentifier("\(identifierPrefix).date")
    }
    private var previous: some View {
        Button(action: onPrevious) { Image(systemName: "chevron.left").accessibilityHidden(true) }
            .buttonStyle(PillButtonStyle(kind: .outline)).accessibilityLabel("Previous day")
            .accessibilityIdentifier("\(identifierPrefix).previous")
    }
    private var next: some View {
        Button(action: onNext) { Image(systemName: "chevron.right").accessibilityHidden(true) }
            .buttonStyle(PillButtonStyle(kind: .outline)).disabled(!canGoNext).accessibilityLabel("Next day")
            .accessibilityIdentifier("\(identifierPrefix).next")
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
