import SwiftUI
import UserNotifications

@main
struct JLUApp: App {
    @StateObject private var notifier = StoreChangeNotifier.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(notifier)
                .preferredColorScheme(colorScheme)
                .task {
                    await ReminderScheduler.refreshIfEnabled()
                }
        }
    }

    private var colorScheme: ColorScheme? {
        switch AppPreferences.darkMode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }
}

struct RootView: View {
    @EnvironmentObject var notifier: StoreChangeNotifier
    @Environment(\.colorScheme) private var systemScheme
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            TimetablePage(palette: palette)
                .tabItem { Label("课表", systemImage: "calendar") }
                .tag(0)
            TodayPage(palette: palette)
                .tabItem { Label("日程", systemImage: "clock") }
                .tag(1)
            SettingsPage(palette: palette)
                .tabItem { Label("设置", systemImage: "gearshape") }
                .tag(2)
        }
        .tint(palette.iconTint)
    }

    private var palette: ThemePalette {
        let dark: Bool
        switch AppPreferences.darkMode {
        case "light": dark = false
        case "dark": dark = true
        default: dark = systemScheme == .dark
        }
        return ThemePalette.palette(theme: AppPreferences.theme, dark: dark)
    }
}

// MARK: - 每日提醒（滚动 7 天窗口，每次启动/数据变化时重排）

enum ReminderScheduler {
    static let identifierPrefix = "daily_reminder_"

    static func refreshIfEnabled() async {
        guard AppPreferences.reminderEnabled else {
            clearPending()
            return
        }
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else {
            return
        }
        scheduleWindow()
    }

    static func requestAuthorizationAndSchedule() async -> Bool {
        let center = UNUserNotificationCenter.current()
        let granted = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
        guard granted == true else { return false }
        scheduleWindow()
        return true
    }

    static func clearPending() {
        let center = UNUserNotificationCenter.current()
        center.getPendingNotificationRequests { requests in
            let ids = requests.map { $0.identifier }.filter { $0.hasPrefix(identifierPrefix) }
            center.removePendingNotificationRequests(withIdentifiers: ids)
        }
    }

    /// 未来 7 天每天一条当日课程概览，内容在排程时按当天课表生成
    static func scheduleWindow() {
        clearPending()
        let center = UNUserNotificationCenter.current()
        let minute = AppPreferences.reminderMinute
        let courses = ProfileStore.shared.loadActiveCourses()
        let semesterStart = ProfileStore.shared.getActiveSemesterStartDate()
        let totalWeeks = WeekScheduleCalculator.totalWeeks(of: courses)
        let today = SimpleDate.today()

        for offset in 0..<7 {
            let date = today.adding(days: offset + 1)
            var fire = DateComponents()
            fire.year = date.year; fire.month = date.month; fire.day = date.day
            fire.hour = minute / 60; fire.minute = minute % 60

            let week = WeekScheduleCalculator.guessCurrentWeek(semesterStart: semesterStart, today: date, totalWeeks: totalWeeks)
            let meetings = WeekScheduleCalculator.meetings(inWeek: week, of: courses)
                .filter { $0.meeting.weekday == date.weekday }
            let title = "今日课程"
            let body: String
            if meetings.isEmpty {
                body = "今天没有课程安排"
            } else {
                let first = meetings.min { $0.meeting.startSection < $1.meeting.startSection }!
                let time = SectionTimes.ranges.indices.contains(first.meeting.startSection - 1)
                    ? String(SectionTimes.ranges[first.meeting.startSection - 1].prefix(5)) : "--:--"
                body = "\(meetings.count) 门课，最早 \(time) \(first.course.courseName)"
            }

            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            let trigger = UNCalendarNotificationTrigger(dateMatching: fire, repeats: false)
            let request = UNNotificationRequest(identifier: identifierPrefix + date.isoString,
                                                content: content, trigger: trigger)
            center.add(request)
        }
    }
}
