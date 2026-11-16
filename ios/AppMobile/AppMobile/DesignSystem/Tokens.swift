import SwiftUI
import UIKit

/// Semantic roles shared with the Android palette. No feature or Core dependencies.
enum HealthColors {
    private static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((hex >> 16) & 255) / 255,
                           green: CGFloat((hex >> 8) & 255) / 255,
                           blue: CGFloat(hex & 255) / 255, alpha: 1)
        })
    }

    static let background = adaptive(0xFFFFFF, 0x000000)
    static let secondaryBackground = adaptive(0xF2F2F2, 0x141414)
    static let tertiaryBackground = adaptive(0xEBEBEB, 0x262626)
    static let inputBackground = adaptive(0xF2F2F2, 0x121212)
    static let primary = adaptive(0x000000, 0xFFFFFF)
    static let secondary = adaptive(0x666666, 0x999999)
    static let tertiary = adaptive(0xB3B3B3, 0x4D4D4D)
    static let body = adaptive(0x474747, 0xB8B8B8)
    static let border = adaptive(0xE6E6E6, 0x1A1A1A)
    static let controlBorder = adaptive(0x8C8C8C, 0x757575)
    static let strongBorder = adaptive(0xCCCCCC, 0x333333)
    static let buttonSecondary = adaptive(0xF2F2F2, 0x1A1A1A)
    static let onPrimary = background
    static let success = adaptive(0x007A51, 0x53D6A3)
    static let successBackground = adaptive(0xDBF8EB, 0x002218)
    static let destructive = adaptive(0xBF1725, 0xFF7C86)
    static let destructiveBackground = adaptive(0xFEDEE3, 0x3D0105)
    static let brandTop = Color(red: 1, green: 97 / 255, blue: 173 / 255)
    static let brandBottom = Color(red: 1, green: 39 / 255, blue: 25 / 255)
    static let brandGradient = LinearGradient(colors: [brandTop, brandBottom], startPoint: .top, endPoint: .bottom)
}

enum HealthSpacing {
    static let xxs: CGFloat = 4
    static let xs: CGFloat = 8
    static let sm: CGFloat = 12
    static let md: CGFloat = 16
    static let lg: CGFloat = 24
    static let xl: CGFloat = 32
    static let xxl: CGFloat = 48
    static let screen: CGFloat = 20
    static let controlHeight: CGFloat = 48
    static let panelRadius: CGFloat = 16
    static let pillRadius: CGFloat = 9999
}
