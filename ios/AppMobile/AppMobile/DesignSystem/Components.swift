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

struct EmptyStateView: View {
    let message: String
    var title: String? = nil
    var symbol: String = "pills"
    var actionTitle: String? = nil
    var onAction: () -> Void = {}

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: symbol).font(.system(size: 28, weight: .ultraLight)).accessibilityHidden(true)
            if let title { Text(title).font(HealthTypography.section) }
            Text(message).font(HealthTypography.smallBody).foregroundStyle(HealthColors.secondary)
            if let actionTitle { Button(actionTitle, action: onAction).buttonStyle(PillButtonStyle()) }
        }.multilineTextAlignment(.center).foregroundStyle(HealthColors.primary)
            .frame(maxWidth: .infinity).padding(32).background(HealthColors.secondaryBackground)
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
    var onAction: () -> Void = {}
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(HealthTypography.row)
            if let message { Text(message).font(HealthTypography.smallBody).foregroundStyle(HealthColors.secondary) }
            if let actionTitle { Button(actionTitle, action: onAction).buttonStyle(PillButtonStyle(kind: .outline)) }
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
        }
    }
}

struct HealthTextField: View {
    let label: String
    let value: String
    var placeholder: String = ""
    var error: String? = nil
    var multiline: Bool = false
    let onValueChange: (String) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label).font(HealthTypography.meta).foregroundStyle(HealthColors.primary)
            TextField(placeholder, text: Binding(get: { value }, set: { onValueChange($0) }), axis: multiline ? .vertical : .horizontal)
                .font(HealthTypography.input).foregroundStyle(HealthColors.primary)
                .padding(.horizontal, 16).padding(.vertical, 14)
                .frame(minHeight: HealthSpacing.controlHeight)
                .background(HealthColors.inputBackground, in: RoundedRectangle(cornerRadius: multiline ? 16 : 28))
                .overlay(RoundedRectangle(cornerRadius: multiline ? 16 : 28).strokeBorder(error == nil ? HealthColors.controlBorder : HealthColors.destructive, lineWidth: 1))
                .accessibilityLabel(label).accessibilityValue([value, error].compactMap { $0 }.joined(separator: ", "))
            if let error { Text(error).font(HealthTypography.caption).foregroundStyle(HealthColors.destructive) }
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
                }.buttonStyle(PillButtonStyle(kind: .outline))
            }
        }
    }
}

/// A native confirmation dialog; visibility belongs to the host.
struct HealthConfirmationDialog: ViewModifier {
    let isPresented: Bool
    let title: String
    let message: String
    let confirmTitle: String
    let onConfirm: () -> Void
    let onDismiss: () -> Void
    func body(content: Content) -> some View {
        content.confirmationDialog(title, isPresented: Binding(get: { isPresented }, set: { if !$0 { onDismiss() } }), titleVisibility: .visible) {
            Button(confirmTitle, role: .destructive, action: onConfirm)
            Button("Cancel", role: .cancel, action: onDismiss)
        } message: { Text(message) }
    }
}
