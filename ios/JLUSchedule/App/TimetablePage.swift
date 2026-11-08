import SwiftUI

struct TimetablePage: View {
    let palette: ThemePalette
    @EnvironmentObject var notifier: StoreChangeNotifier
    @Environment(\.colorScheme) private var systemScheme

    @State private var courses: [CourseSchedule] = []
    @State private var semesterStart = SimpleDate.today()
    @State private var currentWeek = 1
    @State private var totalWeeks = WeekScheduleCalculator.defaultTotalWeeks
    @State private var selectedWeek = 1
    @State private var detailItem: (course: CourseSchedule, meeting: MeetingTime)?
    @State private var showAddSheet = false
    @State private var showImport = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                header
                weekPager
            }
            .background(palette.pageBackground)
            .navigationBarHidden(true)
            .sheet(isPresented: $showAddSheet, onDismiss: { reload() }) {
                AddCourseSheet(palette: palette, guessWeek: currentWeek)
            }
            .sheet(isPresented: $showImport, onDismiss: { reload() }) {
                ImportView(palette: palette)
            }
            .sheet(item: $detailItem) { item in
                CourseDetailSheet(palette: palette, course: item.course, meeting: item.meeting)
                    .presentationDetents([.medium, .large])
            }
            .onAppear { reload() }
            .onChange(of: notifier.version) { _ in reload() }
        }
    }

    private var dark: Bool { effectiveDark }

    private var effectiveDark: Bool {
        switch AppPreferences.darkMode {
        case "light": return false
        case "dark": return true
        default: return systemScheme == .dark
        }
    }

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(headerWeekText)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(palette.textPrimary)
                Text(SimpleDate.today().displayText)
                    .font(.system(size: 12))
                    .foregroundColor(palette.textSecondary)
            }
            Spacer()
            Button { showAddSheet = true } label: {
                Image(systemName: "plus").foregroundColor(palette.iconTint)
            }
            .padding(8)
            .background(Circle().fill(palette.panelBackground))
            .padding(.trailing, 6)
            Button { showImport = true } label: {
                Image(systemName: "square.and.arrow.up.on.square").foregroundColor(palette.iconTint)
            }
            .padding(8)
            .background(Circle().fill(palette.panelBackground))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    private var headerWeekText: String {
        let weekdayLabel = Weekday(rawValue: SimpleDate.today().weekdayMonBased)?.shortLabel ?? "一"
        return selectedWeek == currentWeek ? "第\(selectedWeek)周 周\(weekdayLabel)" : "第\(selectedWeek)周（非本周）"
    }

    private var weekPager: some View {
        TabView(selection: $selectedWeek) {
            ForEach(1...max(totalWeeks, 1), id: \.self) { week in
                WeekGridView(
                    palette: palette,
                    week: week,
                    today: SimpleDate.today(),
                    weekStart: semesterStart.adding(days: (week - 1) * 7),
                    items: WeekScheduleCalculator.displayMeetings(of: courses, week: week,
                                                                  showNonCurrent: AppPreferences.showNonCurrent),
                    fontScale: AppPreferences.fontScale,
                    currentSection: currentSection,
                    onTap: { course, meeting in detailItem = (course, meeting) }
                )
                .tag(week)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
    }

    private var currentSection: Int? {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        let now = formatter.string(from: Date())
        for (index, range) in SectionTimes.ranges.enumerated() {
            let parts = range.components(separatedBy: "-")
            if parts.count == 2, parts[0] <= now, now < parts[1] {
                return index + 1
            }
        }
        return nil
    }

    private func reload() {
        courses = ProfileStore.shared.loadActiveCourses()
        semesterStart = ProfileStore.shared.getActiveSemesterStartDate()
        totalWeeks = WeekScheduleCalculator.totalWeeks(of: courses)
        currentWeek = WeekScheduleCalculator.guessCurrentWeek(semesterStart: semesterStart, today: .today(), totalWeeks: totalWeeks)
        if selectedWeek < 1 || selectedWeek > totalWeeks {
            selectedWeek = currentWeek
        }
    }
}

// MARK: - 单周课表网格

struct WeekGridView: View {
    let palette: ThemePalette
    let week: Int
    let today: SimpleDate
    let weekStart: SimpleDate
    let items: [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime, isCurrentWeek: Bool, nextActiveWeek: Int)]
    let fontScale: Double
    let currentSection: Int?
    let onTap: (CourseSchedule, MeetingTime) -> Void

    private let leftColumnWidth: CGFloat = 44
    private let rowHeight: CGFloat = 46

    var body: some View {
        GeometryReader { geo in
            let dayWidth = max((geo.size.width - leftColumnWidth - 16) / 7, 38)
            HStack(spacing: 0) {
                VStack(spacing: 0) {
                    Text("\(weekStart.month)月")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(palette.textPrimary)
                        .frame(height: 34)
                    ForEach(1...12, id: \.self) { section in
                        VStack(spacing: 0) {
                            Text("\(section)").font(.system(size: 11 * fontScale, weight: .bold))
                            let range = SectionTimes.ranges[section - 1]
                            Text(String(range.prefix(5))).font(.system(size: 8 * fontScale))
                            Text(String(range.suffix(5))).font(.system(size: 8 * fontScale))
                        }
                        .foregroundColor(palette.textSecondary)
                        .frame(width: leftColumnWidth - 6, height: rowHeight)
                    }
                }
                .background(palette.gridLeftColumn)

                HStack(spacing: 2) {
                    ForEach(0..<7, id: \.self) { index in
                        dayColumn(dayIndex: index, width: dayWidth)
                    }
                }
                .padding(.horizontal, 4)
            }
        }
    }

    private func dayColumn(dayIndex: Int, width: CGFloat) -> some View {
        let date = weekStart.adding(days: dayIndex)
        let isToday = date == today
        let dayItems = items.filter { $0.meeting.weekday.rawValue == dayIndex + 1 }
        let visible = resolveOverlaps(dayItems)

        return VStack(spacing: 0) {
            VStack(spacing: 0) {
                Text(Weekday(rawValue: dayIndex + 1)?.shortLabel ?? "")
                    .font(.system(size: 12, weight: isToday ? .bold : .regular))
                Text("\(date.month)/\(date.day)")
                    .font(.system(size: 9))
            }
            .foregroundColor(palette.textPrimary)
            .frame(width: width, height: 34)
            .background(isToday ? palette.gridHeaderToday : palette.gridHeader)

            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(isToday ? palette.gridDayToday : palette.gridDayCell)
                VStack(spacing: 0) {
                    if let current = currentSection {
                        Rectangle()
                            .fill(palette.iconTint.opacity(0.55))
                            .frame(height: 1.5)
                            .offset(y: CGFloat(current - 1) * rowHeight)
                    }
                }
                ForEach(Array(visible.enumerated()), id: \.offset) { _, item in
                    card(for: item, width: width - 2)
                        .offset(y: CGFloat(item.meeting.startSection - 1) * rowHeight + 1)
                }
            }
            .frame(width: width, height: rowHeight * 12)
            .clipShape(RoundedRectangle(cornerRadius: 4))
        }
    }

    private func card(for item: (courseIndex: Int, course: CourseSchedule, meeting: MeetingTime, isCurrentWeek: Bool, nextActiveWeek: Int),
                      width: CGFloat) -> some View {
        let height = CGFloat(item.meeting.endSection - item.meeting.startSection + 1) * rowHeight - 2
        let color = cardColors[item.courseIndex % cardColors.count]
        return Button {
            onTap(item.course, item.meeting)
        } label: {
            VStack(alignment: .leading, spacing: 1) {
                if !item.isCurrentWeek {
                    Text("[非本周] 第\(item.nextActiveWeek)周").font(.system(size: 7.5 * fontScale))
                }
                Text(item.course.courseName)
                    .font(.system(size: 12 * fontScale, weight: .semibold))
                    .lineLimit(4)
                Text(item.meeting.location.isEmpty ? "教室待定" : item.meeting.location)
                    .font(.system(size: 9.5 * fontScale))
                    .lineLimit(2)
            }
            .foregroundColor(cardTextColor)
            .padding(3)
            .frame(width: width, height: height, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: 5).fill(color))
            .opacity(item.isCurrentWeek ? 1 : 0.55)
            .shadow(radius: item.isCurrentWeek ? 0 : 1)
        }
        .buttonStyle(.plain)
    }

    /// 冲突课程按优先级去重（与 Android resolveOverlapForDay 一致）
    private func resolveOverlaps(
        _ items: [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime, isCurrentWeek: Bool, nextActiveWeek: Int)]
    ) -> [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime, isCurrentWeek: Bool, nextActiveWeek: Int)] {
        var occupied = [Bool](repeating: false, count: 13)
        let sorted = items.sorted {
            if $0.isCurrentWeek != $1.isCurrentWeek { return $0.isCurrentWeek }
            if $0.nextActiveWeek != $1.nextActiveWeek { return $0.nextActiveWeek < $1.nextActiveWeek }
            return $0.meeting.startSection < $1.meeting.startSection
        }
        var selected: [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime, isCurrentWeek: Bool, nextActiveWeek: Int)] = []
        for item in sorted {
            let start = min(max(item.meeting.startSection, 1), 12)
            let end = min(max(item.meeting.endSection, 1), 12)
            guard !(start...end).contains(where: { occupied[$0] }) else { continue }
            selected.append(item)
            for section in start...end { occupied[section] = true }
        }
        return selected.sorted { $0.meeting.startSection < $1.meeting.startSection }
    }
}

// MARK: - 课程详情

struct CourseDetailSheet: View {
    let palette: ThemePalette
    let course: CourseSchedule
    let meeting: MeetingTime

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            RoundedRectangle(cornerRadius: 2)
                .fill(palette.textSecondary.opacity(0.4))
                .frame(width: 36, height: 4)
                .frame(maxWidth: .infinity)
            Text(course.courseName)
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(palette.detailTitle)
            row("教师：", course.teacher.isEmpty ? "未知" : course.teacher)
            row("节次：", "\(Weekday(rawValue: meeting.weekday.rawValue)?.shortLabel ?? "") 第\(meeting.startSection)-\(meeting.endSection)节 \(sectionTime)")
            row("周次：", weekText)
            row("地点：", meeting.location.isEmpty ? "未标注" : meeting.location)
            row("学期：", "\(course.semester)   学分：\(course.credit.map { "\($0)" } ?? "N/A")")
            Spacer()
        }
        .padding(20)
        .background(palette.detailBackground)
    }

    private var detailBackground: Color {
        palette.panelBackground
    }

    private var sectionTime: String {
        let start = SectionTimes.ranges.indices.contains(meeting.startSection - 1)
            ? String(SectionTimes.ranges[meeting.startSection - 1].prefix(5)) : "--:--"
        let end = SectionTimes.ranges.indices.contains(meeting.endSection - 1)
            ? String(SectionTimes.ranges[meeting.endSection - 1].suffix(5)) : "--:--"
        return "\(start)-\(end)"
    }

    private var weekText: String {
        if meeting.weekRules.isEmpty {
            return course.rawWeekText.isEmpty ? "未标注" : course.rawWeekText
        }
        return meeting.weekRules.map { rule in
            let range = rule.startWeek == rule.endWeek ? "\(rule.startWeek)周" : "\(rule.startWeek)-\(rule.endWeek)周"
            switch rule.parity {
            case .odd: return range + "(单)"
            case .even: return range + "(双)"
            case .all: return range
            }
        }.joined(separator: "，")
    }

    private func row(_ label: String, _ value: String) -> some View {
        Text(label + value)
            .font(.system(size: 14))
            .foregroundColor(palette.textSecondary)
    }
}
