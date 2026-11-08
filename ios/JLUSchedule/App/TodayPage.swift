import SwiftUI

struct TodayPage: View {
    let palette: ThemePalette
    @EnvironmentObject var notifier: StoreChangeNotifier
    @Environment(\.colorScheme) private var systemScheme

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    summaryCard
                    statsRow
                    if meetings.isEmpty {
                        emptyCard
                    } else {
                        ForEach(Array(meetings.enumerated()), id: \.offset) { _, item in
                            courseCard(item)
                        }
                    }
                }
                .padding(12)
            }
            .background(palette.pageBackground)
            .navigationTitle("")
            .navigationBarHidden(true)
            .onAppear { reload() }
            .onChange(of: notifier.version) { _ in reload() }
        }
    }

    @State private var courses: [CourseSchedule] = []
    @State private var week = 1

    private var today: SimpleDate { SimpleDate.today() }

    private var meetings: [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime)] {
        WeekScheduleCalculator.meetings(inWeek: week, of: courses)
            .filter { $0.meeting.weekday == today.weekday }
    }

    private var summaryCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("今日日程")
                .font(.system(size: 20, weight: .bold))
                .foregroundColor(palette.textPrimary)
            Text("\(today.displayText) 周\(today.weekday.shortLabel)")
                .font(.system(size: 13))
                .foregroundColor(palette.textSecondary)
            Text("\(meetings.count) 门课")
                .font(.system(size: 12))
                .foregroundColor(palette.textPrimary)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Capsule().fill(palette.panelBackground))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 18).fill(palette.panelAltBackground))
    }

    private var statsRow: some View {
        HStack(spacing: 8) {
            statCard("最早：\(firstTime)")
            statCard("最晚：\(lastTime)")
        }
    }

    private var firstTime: String {
        guard let first = meetings.map({ $0.meeting.startSection }).min(),
              SectionTimes.ranges.indices.contains(first - 1) else { return "无" }
        return String(SectionTimes.ranges[first - 1].prefix(5))
    }

    private var lastTime: String {
        guard let last = meetings.map({ $0.meeting.endSection }).max(),
              SectionTimes.ranges.indices.contains(last - 1) else { return "无" }
        return String(SectionTimes.ranges[last - 1].suffix(5))
    }

    private func statCard(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12))
            .foregroundColor(palette.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 12).fill(palette.panelBackground))
    }

    private var emptyCard: some View {
        Text("今天没有课程安排")
            .font(.system(size: 15, weight: .semibold))
            .foregroundColor(palette.textPrimary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
            .background(RoundedRectangle(cornerRadius: 18).fill(palette.panelAltBackground))
    }

    private func courseCard(_ item: (courseIndex: Int, course: CourseSchedule, meeting: MeetingTime)) -> some View {
        let start = min(max(item.meeting.startSection, 1), SectionTimes.ranges.count)
        let end = min(max(item.meeting.endSection, start), SectionTimes.ranges.count)
        let startText = String(SectionTimes.ranges[start - 1].prefix(5))
        let endText = String(SectionTimes.ranges[end - 1].suffix(5))
        return VStack(alignment: .leading, spacing: 3) {
            Text(item.course.courseName)
                .font(.system(size: 14, weight: .medium))
            Text("第\(start)-\(end)节  \(startText)-\(endText)")
                .font(.system(size: 14))
            Text(item.meeting.location.isEmpty ? "教室待定" : item.meeting.location)
                .font(.system(size: 14))
        }
        .foregroundColor(palette.textPrimary)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18).fill(palette.panelBackground))
    }

    private func reload() {
        courses = ProfileStore.shared.loadActiveCourses()
        let semesterStart = ProfileStore.shared.getActiveSemesterStartDate()
        week = WeekScheduleCalculator.guessCurrentWeek(
            semesterStart: semesterStart, today: .today(),
            totalWeeks: WeekScheduleCalculator.totalWeeks(of: courses))
    }
}
