import XCTest
@testable import JLUSchedule

/// 与 Android 端 DoScheduleParserTest / WeekScheduleCalculatorTest /
/// SemesterStartDatePolicyTest / ScheduleBackupCodecTest 用例一一对应
final class CoreTests: XCTestCase {

    private func scheduleJson(_ rows: String) -> String {
        """
        {
          "datas": {
            "xskcb": {
              "rows": [\(rows)]
            }
          }
        }
        """
    }

    // MARK: 解析器

    func testParseAcceptsNumericFieldsReturnedAsStrings() throws {
        let courses = try DoScheduleParser.parse(scheduleJson("""
            {"KCM":"高等数学","SKJS":"张三","XNXQDM":"2026-2027-1","XF":"3.5",
             "ZCMC":"1-16周","SKXQ":"1","KSJC":"3","JSJC":"4","JASMC":"逸夫楼A101"}
            """))
        XCTAssertEqual(courses.count, 1)
        let course = try XCTUnwrap(courses.first)
        XCTAssertEqual(course.courseName, "高等数学")
        XCTAssertEqual(course.credit ?? 0, 3.5, accuracy: 0.0001)
        let meeting = try XCTUnwrap(course.meetings.first)
        XCTAssertEqual(meeting.weekday, .monday)
        XCTAssertEqual(meeting.startSection, 3)
        XCTAssertEqual(meeting.endSection, 4)
        XCTAssertEqual(meeting.location, "逸夫楼A101")
    }

    func testParseSplitsMultipleSegmentsAndParity() throws {
        let courses = try DoScheduleParser.parse(scheduleJson("""
            {"KCM":"大学物理","SKJS":"李四","XNXQDM_DISPLAY":"2026-2027学年第一学期","XF":2,
             "YPSJDD":"1-8周(单) 星期二 第1节-第2节 逸夫楼B201,9-16周(双) 星期四 第7节-第8节 中心校区C301",
             "ZCMC":"1-16周","SKXQ":2,"KSJC":1,"JSJC":2}
            """))
        let course = try XCTUnwrap(courses.first)
        XCTAssertEqual(course.semester, "2026-2027学年第一学期")
        XCTAssertEqual(course.meetings.count, 2)
        XCTAssertEqual(course.meetings[0].weekday, .tuesday)
        XCTAssertEqual(course.meetings[0].weekRules.first?.parity, .odd)
        XCTAssertEqual(course.meetings[1].weekday, .thursday)
        XCTAssertEqual(course.meetings[1].weekRules.first?.parity, .even)
    }

    func testParseSupportsDiscreteWeekList() throws {
        let courses = try DoScheduleParser.parse(scheduleJson("""
            {"KCM":"体育","SKJS":"钱七","YPSJDD":"第1,3,5,7周 星期三 第3节-第4节 田径场"}
            """))
        let rules = try XCTUnwrap(courses.first).meetings.first!.weekRules
        XCTAssertEqual(rules.map { $0.startWeek }, [1, 3, 5, 7])
        XCTAssertEqual(rules.map { $0.endWeek }, [1, 3, 5, 7])
    }

    func testParseSupportsFullWidthParenParityAndChineseComma() throws {
        let courses = try DoScheduleParser.parse(scheduleJson("""
            {"KCM":"数据结构","SKJS":"吴十",
             "YPSJDD":"1-8周(单) 星期二 第1节-第2节 A楼，9-16周(双) 星期四 第7节-第8节 B楼"}
            """))
        let meetings = try XCTUnwrap(courses.first).meetings
        XCTAssertEqual(meetings.count, 2)
        XCTAssertEqual(meetings[0].location, "A楼")
        XCTAssertEqual(meetings[0].weekRules.first?.parity, .odd)
        XCTAssertEqual(meetings[1].location, "B楼")
        XCTAssertEqual(meetings[1].weekRules.first?.parity, .even)
    }

    func testParseSwapsReversedRanges() throws {
        let courses = try DoScheduleParser.parse(scheduleJson("""
            {"KCM":"光学","SKJS":"王二","YPSJDD":"16-1周 星期一 第4节-第2节 教室"}
            """))
        let meeting = try XCTUnwrap(courses.first).meetings.first!
        XCTAssertEqual(meeting.startSection, 2)
        XCTAssertEqual(meeting.endSection, 4)
        let rule = try XCTUnwrap(meeting.weekRules.first)
        XCTAssertEqual(rule.startWeek, 1)
        XCTAssertEqual(rule.endWeek, 16)
    }

    func testParseToleratesExplicitNulls() throws {
        XCTAssertEqual(try DoScheduleParser.parse(#"{"datas": null}"#).count, 0)
        XCTAssertEqual(try DoScheduleParser.parse(#"{"datas": {"x": {"rows": null}}}"#).count, 0)
    }

    func testImportParserSelectsLatestSemesterBatch() {
        let old = ScheduleImportCacheParser.CacheEntry(
            url: "https://x/cxxszhxqkb.do", fileName: "0001.do", content: scheduleJson("""
                {"KCM":"旧课","XNXQDM_DISPLAY":"2025-2026学年第一学期","ZCMC":"1-16周","SKXQ":1,"KSJC":1,"JSJC":2}
                """), sequence: 1)
        let new = ScheduleImportCacheParser.CacheEntry(
            url: "https://x/cxxszhxqkb.do", fileName: "0002.do", content: scheduleJson("""
                {"KCM":"新课","XNXQDM_DISPLAY":"2026-2027学年第一学期","ZCMC":"1-16周","SKXQ":1,"KSJC":1,"JSJC":2}
                """), sequence: 2)
        let result = ScheduleImportCacheParser.parse(entries: [old, new])
        XCTAssertEqual(result.courses.map { $0.courseName }, ["新课"])
        XCTAssertEqual(result.selectedSemester, "2026-2027学年第一学期")
        XCTAssertEqual(result.inferredSemesterStartDate, SimpleDate(year: 2026, month: 8, day: 31))
    }

    // MARK: 周次计算

    private func course(_ rules: [WeekRule], weekday: Weekday = .monday) -> CourseSchedule {
        CourseSchedule(courseName: "测试课程", teacher: "老师", semester: "2026-2027-1",
                       credit: nil, rawWeekText: "custom",
                       meetings: [MeetingTime(weekday: weekday, startSection: 1, endSection: 2,
                                              weekRules: rules, location: "教室")])
    }

    func testGuessCurrentWeek() {
        let start = SimpleDate(year: 2026, month: 8, day: 31)
        XCTAssertEqual(WeekScheduleCalculator.guessCurrentWeek(semesterStart: start, today: start, totalWeeks: 20), 1)
        XCTAssertEqual(WeekScheduleCalculator.guessCurrentWeek(semesterStart: start, today: start.adding(days: 7), totalWeeks: 20), 2)
        XCTAssertEqual(WeekScheduleCalculator.guessCurrentWeek(semesterStart: start, today: start.adding(days: -30), totalWeeks: 20), 1)
        XCTAssertEqual(WeekScheduleCalculator.guessCurrentWeek(semesterStart: start, today: start.adding(days: 365), totalWeeks: 20), 20)
    }

    func testParityRules() {
        let courses = [course([WeekRule(startWeek: 1, endWeek: 16, parity: .odd)])]
        XCTAssertEqual(WeekScheduleCalculator.meetings(inWeek: 1, of: courses).count, 1)
        XCTAssertEqual(WeekScheduleCalculator.meetings(inWeek: 2, of: courses).count, 0)
        XCTAssertEqual(WeekScheduleCalculator.meetings(inWeek: 3, of: courses).count, 1)
    }

    func testPastWeeksShowEndedCourses() {
        let courses = [course([WeekRule(startWeek: 1, endWeek: 5, parity: .odd)])]
        let display = WeekScheduleCalculator.displayMeetings(of: courses, week: 4, showNonCurrent: true)
        XCTAssertEqual(display.count, 1)
        XCTAssertFalse(display[0].isCurrentWeek)
        XCTAssertEqual(display[0].nextActiveWeek, 5)
    }

    // MARK: 学期日期

    func testSemesterLabelInference() {
        XCTAssertEqual(SemesterStartDatePolicy.inferFromSemesterLabelOrNull("2026-2027学年第一学期"),
                       SimpleDate(year: 2026, month: 8, day: 31))
        XCTAssertEqual(SemesterStartDatePolicy.inferFromSemesterLabelOrNull("2026-2027学年第二学期"),
                       SimpleDate(year: 2027, month: 2, day: 22))
        XCTAssertNil(SemesterStartDatePolicy.inferFromSemesterLabelOrNull("未知学期"))
    }

    func testNormalizeToWeekStart() {
        XCTAssertEqual(SemesterStartDatePolicy.normalizeToWeekStart(SimpleDate(year: 2026, month: 9, day: 2)),
                       SimpleDate(year: 2026, month: 8, day: 31))
    }

    func testDefaultForToday() {
        XCTAssertEqual(SemesterStartDatePolicy.defaultForToday(SimpleDate(year: 2026, month: 8, day: 15)),
                       SimpleDate(year: 2026, month: 8, day: 31))
        XCTAssertEqual(SemesterStartDatePolicy.defaultForToday(SimpleDate(year: 2026, month: 3, day: 15)),
                       SimpleDate(year: 2026, month: 2, day: 23))
    }

    // MARK: 备份编解码

    func testBackupRoundTrip() {
        let courses = [course([WeekRule(startWeek: 1, endWeek: 16, parity: .odd)])]
        let profiles = [ScheduleBackupCodec.BackupProfile(
            name: "主课表", semesterStartDate: "2026-08-31",
            courses: courses.map { ScheduleBackupCodec.toBackupCourse($0) })]
        let decoded = ScheduleBackupCodec.decode(ScheduleBackupCodec.encode(exportedAt: "2026-09-05", profiles: profiles))
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.count, 1)
        XCTAssertEqual(decoded?[0].courses.first?.courseName, "测试课程")
        XCTAssertEqual(decoded?[0].courses.first?.meetings.first?.weekRules.first?.parity, "ODD")
        XCTAssertNil(ScheduleBackupCodec.decode("not a json {"))
    }
}
