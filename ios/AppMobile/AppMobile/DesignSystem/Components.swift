import SwiftUI
import UIKit

/// Hosts own every value and action. Components never reference feature state.
enum PillButtonKind { case primary, outline, destructive }

struct PillButtonStyle: ButtonStyle {
    var kind: PillButtonKind = .primary
    var isControl: Bool = false
    var horizontalPadding: CGFloat = 20
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(HealthTypography.meta)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, horizontalPadding)
            .frame(minWidth: HealthSpacing.controlHeight, minHeight: HealthSpacing.controlHeight)
            .foregroundStyle(foreground)
            .background(background, in: Capsule())
            .overlay(Capsule().strokeBorder(kind == .outline ? (isControl ? HealthColors.controlBorder : HealthColors.strongBorder) : .clear, lineWidth: 1))
            .opacity(isEnabled ? (configuration.isPressed ? 0.7 : 1) : 0.38)
            .contentShape(Capsule())
    }

    private var foreground: Color {
        switch kind {
        case .primary: HealthColors.onPrimary
        case .outline: HealthColors.primary
        case .destructive: HealthColors.destructive
        }
    }
    private var background: Color {
        switch kind {
        case .primary: HealthColors.primary
        case .outline: .clear
        case .destructive: HealthColors.destructiveBackground
        }
    }
}

struct HairlineDivider: View {
    @Environment(\.displayScale) private var displayScale
    var body: some View {
        Rectangle().fill(HealthColors.border).frame(height: 1 / displayScale).accessibilityHidden(true)
    }
}

struct MetaRow: View {
    let label: String
    var detail: String? = nil
    var symbol: String? = nil

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            if let symbol { Image(systemName: symbol).accessibilityHidden(true) }
            Text(label)
            if let detail { Text("·").accessibilityHidden(true); Text(detail).foregroundStyle(HealthColors.secondary) }
        }
        .font(HealthTypography.meta).tracking(0.52).foregroundStyle(HealthColors.secondary)
        .accessibilityElement(children: .combine)
    }
}

struct ListRow: View {
    let title: String
    let meta: String
    var detail: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HairlineDivider()
            VStack(alignment: .leading, spacing: 6) {
                Text(meta).font(HealthTypography.meta).foregroundStyle(HealthColors.secondary)
                Text(title).font(HealthTypography.row).foregroundStyle(HealthColors.primary)
                if let detail { Text(detail).font(HealthTypography.caption).foregroundStyle(HealthColors.secondary) }
            }.padding(.vertical, 16)
        }.frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

struct SectionHeader: View {
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).foregroundStyle(HealthColors.primary)
            if let subtitle { Text(subtitle).foregroundStyle(HealthColors.secondary) }
        }.font(HealthTypography.section).frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityAddTraits(.isHeader)
    }
}

enum DoseStatusKind: CaseIterable {
    case upcoming, due, taken, skipped, missed

    var label: String {
        switch self {
        case .upcoming: "Upcoming"
        case .due: "Due"
        case .taken: "Taken"
        case .skipped: "Skipped"
        case .missed: "Missed"
        }
    }
    var symbol: String {
        switch self {
        case .upcoming: "clock"
        case .due: "circle.inset.filled"
        case .taken: "checkmark"
        case .skipped: "minus"
        case .missed: "exclamationmark"
        }
    }
}

struct StatusChip: View {
    let kind: DoseStatusKind
    var body: some View {
        Label(kind.label, systemImage: kind.symbol)
            .font(HealthTypography.meta).padding(.horizontal, 10).padding(.vertical, 6)
            .foregroundStyle(foreground).background(background, in: Capsule())
            .accessibilityLabel(kind.label)
    }
    private var foreground: Color {
        switch kind {
        case .taken: HealthColors.success
        case .missed: HealthColors.destructive
        case .due: HealthColors.onPrimary
        case .upcoming, .skipped: HealthColors.secondary
        }
    }
    private var background: Color {
        switch kind {
        case .taken: HealthColors.successBackground
        case .missed: HealthColors.destructiveBackground
        case .due: HealthColors.primary
        case .upcoming, .skipped: HealthColors.secondaryBackground
        }
    }
}

struct ProgressLine: View {
    /// A presentation fraction, supplied by the host; this view does no dose counting.
    let fraction: Double
    let label: String
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(label).font(HealthTypography.meta).foregroundStyle(HealthColors.secondary)
            ProgressView(value: fraction, total: 1).tint(HealthColors.primary)
                .accessibilityLabel(label)
        }
    }
}

/// A centered message panel. `symbol: nil` shows no icon. At accessibility text sizes the icon is left out and the
/// padding tightens, so the message and its action stay in view instead of starting under the tab bar.
struct EmptyStateView: View {
    let message: String
    var title: String? = nil
    var symbol: String? = "pill"
    var actionTitle: String? = nil
    var messageIdentifier: String? = nil
    var actionIdentifier: String? = nil
    var onAction: () -> Void = {}
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .title) private var symbolSize = 28.0

    var body: some View {
        let isLargeText = dynamicTypeSize.isAccessibilitySize
        VStack(spacing: 16) {
            if let symbol, !isLargeText {
                Image(systemName: symbol).font(.system(size: symbolSize, weight: .ultraLight)).accessibilityHidden(true)
            }
            if let title { Text(title).font(HealthTypography.section) }
            Text(message).font(HealthTypography.smallBody).foregroundStyle(HealthColors.secondary)
                .accessibilityIdentifier(messageIdentifier ?? "")
            if let actionTitle {
                Button(actionTitle, action: onAction).buttonStyle(PillButtonStyle())
                    .accessibilityIdentifier(actionIdentifier ?? "")
            }
        }.multilineTextAlignment(.center).foregroundStyle(HealthColors.primary)
            .frame(maxWidth: .infinity).padding(isLargeText ? HealthSpacing.md : HealthSpacing.xl)
            .background(HealthColors.secondaryBackground, in: RoundedRectangle(cornerRadius: HealthSpacing.panelRadius))
    }
}

struct FloatingPillButton: View {
    let title: String
    var symbol: String = "plus"
    let onTap: () -> Void
    var body: some View {
        Button(action: onTap) { Label(title, systemImage: symbol) }
            .buttonStyle(PillButtonStyle())
            .padding(6).background(HealthColors.background, in: Capsule())
    }
}

struct Banner: View {
    let title: String
    var message: String? = nil
    var actionTitle: String? = nil
    /// The title's identifier; the action is `<identifier>.action`.
    var identifier: String? = nil
    var onAction: () -> Void = {}
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(HealthTypography.row).accessibilityIdentifier(identifier ?? "")
            if let message { Text(message).font(HealthTypography.smallBody).foregroundStyle(HealthColors.secondary) }
            if let actionTitle {
                Button(actionTitle, action: onAction).buttonStyle(PillButtonStyle(kind: .outline))
                    .accessibilityIdentifier(identifier.map { "\($0).action" } ?? "")
            }
        }.foregroundStyle(HealthColors.primary).frame(maxWidth: .infinity, alignment: .leading)
            .padding(16).background(HealthColors.secondaryBackground, in: RoundedRectangle(cornerRadius: 16))
    }
}

struct PillSelect: View {
    let label: String
    let title: String
    let onTap: () -> Void
    var body: some View {
        Button(action: onTap) {
            HStack { Text(title); Spacer(minLength: 12); Image(systemName: "chevron.down").accessibilityHidden(true) }
        }.buttonStyle(PillButtonStyle(kind: .outline, isControl: true))
            .accessibilityLabel(label).accessibilityValue(title)
    }
}

struct PillSegmentedControl: View {
    let labels: [String]
    let selectedIndex: Int
    var identifiers: [String] = []
    let onSelect: (Int) -> Void
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 4) { segments }
            VStack(spacing: 4) { segments }
        }.padding(4).background(HealthColors.secondaryBackground, in: RoundedRectangle(cornerRadius: 28))
    }
    private var segments: some View {
        ForEach(labels.indices, id: \.self) { index in
            Button { onSelect(index) } label: { Text(labels[index]).frame(maxWidth: .infinity) }
                .buttonStyle(PillButtonStyle(kind: selectedIndex == index ? .primary : .outline))
                .accessibilityAddTraits(selectedIndex == index ? [.isSelected] : [])
                .accessibilityIdentifier(index < identifiers.count ? identifiers[index] : labels[index])
        }
    }
}

/// A labeled field over a local edit buffer: typing updates the buffer and reports each change through
/// `onValueChange`; the buffer re-syncs from `value` only when the screen's state differs from the last value
/// reported (so no keystroke is dropped and input-method composition such as Hangul stays intact).
struct HealthTextField: View {
    let label: String
    let value: String
    var placeholder: String = ""
    var error: String? = nil
    var multiline: Bool = false
    var identifier: String? = nil
    var errorIdentifier: String? = nil
    let onValueChange: (String) -> Void
    @State private var text = ""
    @State private var lastReported: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label).font(HealthTypography.meta).foregroundStyle(HealthColors.primary).accessibilityHidden(true)
            TextField(placeholder, text: $text, axis: multiline ? .vertical : .horizontal)
                .font(HealthTypography.input).foregroundStyle(HealthColors.primary)
                .lineLimit(multiline ? 3... : 1...)
                .padding(.horizontal, 16).padding(.vertical, 14)
                .frame(minHeight: HealthSpacing.controlHeight)
                .background(HealthColors.inputBackground, in: RoundedRectangle(cornerRadius: multiline ? 16 : 28))
                .overlay(RoundedRectangle(cornerRadius: multiline ? 16 : 28).strokeBorder(error == nil ? HealthColors.controlBorder : HealthColors.destructive, lineWidth: 1))
                .accessibilityLabel(label).accessibilityValue([text, error].compactMap { $0 }.joined(separator: ", "))
                .accessibilityIdentifier(identifier ?? label)
            if let error {
                Text(error).font(HealthTypography.caption).foregroundStyle(HealthColors.destructive)
                    .accessibilityIdentifier(errorIdentifier ?? "\(identifier ?? label).error")
            }
        }
        .onAppear {
            if lastReported == nil { text = value; lastReported = value }
        }
        .onChange(of: text) { _, newText in
            guard newText != lastReported else { return }
            lastReported = newText
            onValueChange(newText)
        }
        .onChange(of: value) { _, newValue in
            guard newValue != lastReported else { return }
            lastReported = newValue
            text = newValue
        }
        .onChange(of: error) { _, newError in
            if let newError { AccessibilityNotification.Announcement(newError).post() }
        }
    }
}

struct HealthTopBar: View {
    let title: String
    var actionTitle: String? = nil
    var onAction: () -> Void = {}
    var backTitle: String? = nil
    var onBack: () -> Void = {}
    var actionSymbol: String? = nil
    var actionIdentifier: String? = nil
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) { content }
            VStack(alignment: .leading, spacing: 8) { content }
        }.padding(.vertical, 8).foregroundStyle(HealthColors.primary)
    }
    private var content: some View {
        Group {
            if let backTitle {
                Button(action: onBack) { Label(backTitle, systemImage: "chevron.left") }
                    .buttonStyle(PillButtonStyle(kind: .outline))
            }
            Text(title).font(HealthTypography.meta).frame(maxWidth: .infinity, alignment: .leading)
            if let actionTitle {
                Button(action: onAction) {
                    if let actionSymbol { Label(actionTitle, systemImage: actionSymbol) } else { Text(actionTitle) }
                }.buttonStyle(PillButtonStyle(kind: .outline)).accessibilityIdentifier(actionIdentifier ?? actionTitle)
            }
        }
    }
}

/// A native confirmation alert; visibility belongs to the host. The presentation binding ignores writes: the
/// buttons send the outcome themselves (a setter that sent "cancel" could run before, and undo, the confirm).
struct HealthConfirmationDialog: ViewModifier {
    let isPresented: Bool
    let title: String
    let message: String
    let confirmTitle: String
    let onConfirm: () -> Void
    let onDismiss: () -> Void
    func body(content: Content) -> some View {
        content.alert(title, isPresented: Binding(get: { isPresented }, set: { _ in })) {
            Button(confirmTitle, role: .destructive, action: onConfirm).accessibilityIdentifier("deleteDialog.confirm")
            Button("Cancel", role: .cancel, action: onDismiss).accessibilityIdentifier("deleteDialog.cancel")
        } message: { Text(message) }
    }
}
