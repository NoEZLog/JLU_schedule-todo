import SwiftUI

// MARK: - 主题色板（与 Android colors.xml 六套色一一对应）

struct ThemePalette {
    var pageBackground: Color
    var panelBackground: Color
    var panelAltBackground: Color
    var textPrimary: Color
    var textSecondary: Color
    var iconTint: Color
    var buttonBackground: Color
    var buttonText: Color
    var gridHeader: Color
    var gridHeaderToday: Color
    var gridLeftColumn: Color
    var gridDayCell: Color
    var gridDayToday: Color

    static func palette(theme: String, dark: Bool) -> ThemePalette {
        switch theme {
        case "ocean":
            return dark ? oceanDark : oceanLight
        case "mint":
            return dark ? mintDark : mintLight
        default:
            return dark ? warmDark : warmLight
        }
    }

    static let warmLight = ThemePalette(
        pageBackground: Color(hex: 0xF7E7CC), panelBackground: Color(hex: 0xFFE2BF),
        panelAltBackground: Color(hex: 0xFFEED8), textPrimary: Color(hex: 0x5C3C1E),
        textSecondary: Color(hex: 0x7A5A37), iconTint: Color(hex: 0x5A3D1F),
        buttonBackground: Color(hex: 0xEFD7B1), buttonText: Color(hex: 0x3A2A1A),
        gridHeader: Color(hex: 0xFFE9C9), gridHeaderToday: Color(hex: 0xFFD7B5),
        gridLeftColumn: Color(hex: 0xFFEFD6), gridDayCell: Color(hex: 0xFFF5E6),
        gridDayToday: Color(hex: 0xFFE8CD))

    static let warmDark = ThemePalette(
        pageBackground: Color(hex: 0x221A12), panelBackground: Color(hex: 0x33281C),
        panelAltBackground: Color(hex: 0x2B2116), textPrimary: Color(hex: 0xF3E7D3),
        textSecondary: Color(hex: 0xC9B697), iconTint: Color(hex: 0xE8D5B5),
        buttonBackground: Color(hex: 0x4A3924), buttonText: Color(hex: 0xF3E7D3),
        gridHeader: Color(hex: 0x3A2C1B), gridHeaderToday: Color(hex: 0x4C3A22),
        gridLeftColumn: Color(hex: 0x2E2315), gridDayCell: Color(hex: 0x271E12),
        gridDayToday: Color(hex: 0x342819))

    static let oceanLight = ThemePalette(
        pageBackground: Color(hex: 0xEAF5FF), panelBackground: Color(hex: 0xE1F1FF),
        panelAltBackground: Color(hex: 0xF0F8FF), textPrimary: Color(hex: 0x1F3A56),
        textSecondary: Color(hex: 0x3E5876), iconTint: Color(hex: 0x2E4E72),
        buttonBackground: Color(hex: 0xD6E7FA), buttonText: Color(hex: 0x1F3A56),
        gridHeader: Color(hex: 0xDCEEFF), gridHeaderToday: Color(hex: 0xBFDFFF),
        gridLeftColumn: Color(hex: 0xE8F4FF), gridDayCell: Color(hex: 0xF2F8FF),
        gridDayToday: Color(hex: 0xD6EBFF))

    static let oceanDark = ThemePalette(
        pageBackground: Color(hex: 0x101B26), panelBackground: Color(hex: 0x1B2C3D),
        panelAltBackground: Color(hex: 0x16232F), textPrimary: Color(hex: 0xDDEAF6),
        textSecondary: Color(hex: 0xA3BDD3), iconTint: Color(hex: 0xBAD2E8),
        buttonBackground: Color(hex: 0x274058), buttonText: Color(hex: 0xDDEAF6),
        gridHeader: Color(hex: 0x1E3247), gridHeaderToday: Color(hex: 0x2A4560),
        gridLeftColumn: Color(hex: 0x18293A), gridDayCell: Color(hex: 0x142230),
        gridDayToday: Color(hex: 0x1D3143))

    static let mintLight = ThemePalette(
        pageBackground: Color(hex: 0xE8FAF2), panelBackground: Color(hex: 0xDCF4E8),
        panelAltBackground: Color(hex: 0xECF9F2), textPrimary: Color(hex: 0x1D4A3A),
        textSecondary: Color(hex: 0x396458), iconTint: Color(hex: 0x2D5A4E),
        buttonBackground: Color(hex: 0xCFEADF), buttonText: Color(hex: 0x1D4A3A),
        gridHeader: Color(hex: 0xD7F3E6), gridHeaderToday: Color(hex: 0xBFEAD6),
        gridLeftColumn: Color(hex: 0xE7F8EF), gridDayCell: Color(hex: 0xF0FBF5),
        gridDayToday: Color(hex: 0xD4F4E4))

    static let mintDark = ThemePalette(
        pageBackground: Color(hex: 0x0F1F19), panelBackground: Color(hex: 0x1A3028),
        panelAltBackground: Color(hex: 0x152620), textPrimary: Color(hex: 0xD9EFE4),
        textSecondary: Color(hex: 0xA2C4B6), iconTint: Color(hex: 0xB8D9CB),
        buttonBackground: Color(hex: 0x24453A), buttonText: Color(hex: 0xD9EFE4),
        gridHeader: Color(hex: 0x1C362C), gridHeaderToday: Color(hex: 0x27493A),
        gridLeftColumn: Color(hex: 0x182B23), gridDayCell: Color(hex: 0x14241C),
        gridDayToday: Color(hex: 0x1B3127))
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: 1)
    }
}

let cardColors: [Color] = [
    Color(hex: 0xFAD8C0), Color(hex: 0xC9E7FF), Color(hex: 0xD8F4D2), Color(hex: 0xFFE6A8),
    Color(hex: 0xE6D7FF), Color(hex: 0xFFD7E0), Color(hex: 0xD8F0EE), Color(hex: 0xFFE1C4),
]

/// 深色底淡彩卡片上的固定深色文字（对齐 Android 端 CARD_TEXT_COLOR）
let cardTextColor = Color(hex: 0x37312A)

/// 应用内统一节次-时间对照（与 Android SectionTimes 一致）
enum SectionTimes {
    static let ranges = [
        "08:00-08:45", "08:45-09:40", "10:00-10:45", "10:45-11:40",
        "13:30-14:15", "14:15-15:10", "15:30-16:15", "16:15-17:10",
        "18:20-19:05", "19:05-19:50", "20:00-20:45", "20:45-21:30",
    ]
}

// MARK: - 偏好设置（对应 Android AppPreferences）

enum AppPreferences {
    static let defaults = UserDefaults.standard

    static var theme: String {
        get { defaults.string(forKey: "theme_color") ?? "warm" }
        set { defaults.set(newValue, forKey: "theme_color") }
    }

    /// system / light / dark
    static var darkMode: String {
        get { defaults.string(forKey: "dark_mode") ?? "system" }
        set { defaults.set(newValue, forKey: "dark_mode") }
    }

    static var fontScale: Double {
        get {
            let saved = defaults.double(forKey: "timetable_font_scale")
            if saved == 0 { return 1.0 }
            if saved > 1.08 { return 1.15 }
            if saved < 0.98 { return 0.95 }
            return 1.0
        }
        set {
            let safe: Double
            if newValue < 0.98 { safe = 0.95 }
            else if newValue > 1.08 { safe = 1.15 }
            else { safe = 1.0 }
            defaults.set(safe, forKey: "timetable_font_scale")
        }
    }

    static var showNonCurrent: Bool {
        get { defaults.object(forKey: "show_non_current") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "show_non_current") }
    }

    static var reminderEnabled: Bool {
        get { defaults.bool(forKey: "daily_reminder_enabled") }
        set { defaults.set(newValue, forKey: "daily_reminder_enabled") }
    }

    static var reminderMinute: Int {
        get {
            guard let saved = defaults.object(forKey: "daily_reminder_minute") as? Int else { return 450 }
            return min(max(saved, 0), 1439)
        }
        set { defaults.set(min(max(newValue, 0), 1439), forKey: "daily_reminder_minute") }
    }
}

// MARK: - 全局数据版本（对应 Android ScheduleRepository 的 StateFlow 通知机制）

final class StoreChangeNotifier: ObservableObject {
    static let shared = StoreChangeNotifier()
    @Published var version = 0
    private init() {}

    func notifyChanged() {
        version += 1
    }
}
