import SwiftUI
import UIKit

/// Relative text styles preserve Dynamic Type scaling for every token.
enum HealthTypography {
    static var display: Font {
        Font(UIFontMetrics(forTextStyle: .largeTitle).scaledFont(for: UIFont.systemFont(ofSize: 48, weight: .medium)))
    }
    static let title = Font.system(.largeTitle, design: .default, weight: .medium)
    static let section = Font.system(.title2, design: .default, weight: .medium)
    static let row = Font.system(.body, design: .default, weight: .medium)
    static let body = Font.system(.body, design: .default, weight: .regular)
    static let smallBody = Font.system(.subheadline, design: .default, weight: .regular)
    static let meta = Font.system(.footnote, design: .default, weight: .medium)
    static let caption = Font.system(.footnote, design: .default, weight: .regular)
    static let input = Font.system(.body, design: .default, weight: .regular)
    static let footnote = Font.system(.caption2, design: .default, weight: .regular)
}
