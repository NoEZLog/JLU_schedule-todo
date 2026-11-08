import Foundation

// MARK: - 备份文件编解码（格式与 Android ScheduleBackupCodec 一致，version 1）

enum ScheduleBackupCodec {
    struct BackupFile: Codable {
        var version: Int = 1
        var exportedAt: String = ""
        var profiles: [BackupProfile] = []
    }

    struct BackupProfile: Codable {
        var name: String
        var semesterStartDate: String?
        var courses: [BackupCourse]
    }

    struct BackupCourse: Codable {
        var courseName: String
        var teacher: String = ""
        var semester: String = ""
        var credit: Double?
        var rawWeekText: String = ""
        var meetings: [BackupMeeting]
    }

    struct BackupMeeting: Codable {
        var weekday: String
        var startSection: Int
        var endSection: Int
        var weekRules: [BackupWeekRule] = []
        var location: String = ""
    }

    struct BackupWeekRule: Codable {
        var startWeek: Int
        var endWeek: Int
        var parity: String = "ALL"
    }

    static func encode(exportedAt: String, profiles: [BackupProfile]) -> String {
        let file = BackupFile(exportedAt: exportedAt, profiles: profiles)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(file) else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    static func decode(_ content: String) -> [BackupProfile]? {
        guard let data = content.data(using: .utf8),
              let file = try? JSONDecoder().decode(BackupFile.self, from: data),
              (1...1).contains(file.version) else { return nil }
        return file.profiles
    }

    static func toBackupCourse(_ course: CourseSchedule) -> BackupCourse {
        BackupCourse(
            courseName: course.courseName,
            teacher: course.teacher,
            semester: course.semester,
            credit: course.credit,
            rawWeekText: course.rawWeekText,
            meetings: course.meetings.map { meeting in
                BackupMeeting(
                    weekday: meeting.weekday.rawValue.description,
                    startSection: meeting.startSection,
                    endSection: meeting.endSection,
                    weekRules: meeting.weekRules.map {
                        BackupWeekRule(startWeek: $0.startWeek, endWeek: $0.endWeek, parity: $0.parity.rawValue)
                    },
                    location: meeting.location
                )
            }
        )
    }

    static func toCourseSchedule(_ backup: BackupCourse) -> CourseSchedule {
        CourseSchedule(
            courseName: backup.courseName,
            teacher: backup.teacher,
            semester: backup.semester,
            credit: backup.credit,
            rawWeekText: backup.rawWeekText,
            meetings: backup.meetings.map { meeting in
                MeetingTime(
                    weekday: Weekday(rawValue: Int(meeting.weekday) ?? 1) ?? .monday,
                    startSection: meeting.startSection,
                    endSection: meeting.endSection,
                    weekRules: meeting.weekRules.map {
                        WeekRule(startWeek: $0.startWeek, endWeek: $0.endWeek,
                                 parity: WeekParity(rawValue: $0.parity) ?? .all)
                    },
                    location: meeting.location
                )
            }
        )
    }
}

// MARK: - 导入缓存解析（移植自 Android ScheduleImportCacheParser，条目内容来自 WebView 捕获）

enum ScheduleImportCacheParser {
    struct CacheEntry {
        var url: String
        var fileName: String
        var content: String
        var sequence: Int
    }

    struct RejectedEntry {
        var entry: CacheEntry
        var reason: String
    }

    struct ParseResult {
        var courses: [CourseSchedule]
        var selectedSemester: String
        var inferredSemesterStartDate: SimpleDate?
        var scannedEntries: Int
        var parsedBatchCount: Int
        var rejectedEntries: [RejectedEntry]
    }

    private struct Batch {
        var entry: CacheEntry
        var courses: [CourseSchedule]
        var semesterKey: String
        var semesterLabel: String
    }

    static let targetScheduleFile = "cxxszhxqkb.do"
    static let minSchedulePayloadBytes = 80

    static func parse(entries: [CacheEntry]) -> ParseResult {
        let candidates = prioritize(entries)
        var batches: [Batch] = []
        var rejected: [RejectedEntry] = []

        for entry in candidates {
            let size = entry.content.utf8.count
            if size <= 0 { rejected.append(RejectedEntry(entry: entry, reason: "文件为空")); continue }
            if size > 2 * 1024 * 1024 { rejected.append(RejectedEntry(entry: entry, reason: "文件过大")); continue }
            guard looksLikeSchedulePayload(url: entry.url, text: entry.content, size: size) else {
                rejected.append(RejectedEntry(entry: entry, reason: "不是课表响应")); continue
            }
            guard let parsed = try? DoScheduleParser.parse(entry.content) else {
                rejected.append(RejectedEntry(entry: entry, reason: "解析失败")); continue
            }
            let valid = parsed.filter { !$0.courseName.isEmpty && !$0.meetings.isEmpty }
            if valid.isEmpty {
                rejected.append(RejectedEntry(entry: entry, reason: "课表为空")); continue
            }
            batches.append(Batch(
                entry: entry,
                courses: valid,
                semesterKey: dominantSemesterKey(valid),
                semesterLabel: dominantSemesterLabel(valid)
            ))
        }

        guard let latestBatch = batches.max(by: { $0.entry.sequence < $1.entry.sequence }) else {
            return ParseResult(courses: [], selectedSemester: "", inferredSemesterStartDate: nil,
                               scannedEntries: candidates.count, parsedBatchCount: 0, rejectedEntries: rejected)
        }

        let selectedBatches = latestBatch.semesterKey.isEmpty
            ? [latestBatch]
            : batches.filter { $0.semesterKey == latestBatch.semesterKey }
        var selectedCourses = selectedBatches.sorted { $0.entry.sequence < $1.entry.sequence }.flatMap { $0.courses }
        if !latestBatch.semesterKey.isEmpty {
            selectedCourses = selectedCourses.filter { course in
                let key = semesterKey(course.semester)
                return key.isEmpty || key == latestBatch.semesterKey
            }
        }

        return ParseResult(
            courses: selectedCourses,
            selectedSemester: latestBatch.semesterLabel.isEmpty ? latestBatch.semesterKey : latestBatch.semesterLabel,
            inferredSemesterStartDate: SemesterStartDatePolicy.inferFromCoursesOrNull(selectedCourses),
            scannedEntries: candidates.count,
            parsedBatchCount: batches.count,
            rejectedEntries: rejected
        )
    }

    static func looksLikeSchedulePayload(url: String, text: String, size: Int) -> Bool {
        guard size >= minSchedulePayloadBytes else { return false }
        guard isLikelyScheduleUrl(url) else { return false }
        guard text.contains("\"datas\""), text.contains("\"rows\"") else { return false }
        let hasCourseField = text.contains("\"KCM\"")
        let hasTimeField = text.contains("\"YPSJDD\"") || text.contains("\"SKXQ\"")
        return hasCourseField && hasTimeField
    }

    static func isLikelyScheduleUrl(_ url: String) -> Bool {
        url.range(of: targetScheduleFile, options: .caseInsensitive) != nil ||
            url.range(of: "modules/xskcb", options: .caseInsensitive) != nil
    }

    private static func dominantSemesterKey(_ courses: [CourseSchedule]) -> String {
        var counts: [String: Int] = [:]
        for course in courses {
            let key = semesterKey(course.semester)
            if !key.isEmpty { counts[key, default: 0] += 1 }
        }
        return counts.max(by: { $0.value < $1.value })?.key ?? ""
    }

    private static func dominantSemesterLabel(_ courses: [CourseSchedule]) -> String {
        let key = dominantSemesterKey(courses)
        if key.isEmpty { return "" }
        var counts: [String: Int] = [:]
        for course in courses {
            let label = course.semester.trimmingCharacters(in: .whitespaces)
            if !label.isEmpty && semesterKey(label) == key { counts[label, default: 0] += 1 }
        }
        return counts.max(by: { $0.value < $1.value })?.key ?? ""
    }

    private static func semesterKey(_ label: String) -> String {
        let raw = label.trimmingCharacters(in: .whitespaces)
        if raw.isEmpty { return "" }
        return SemesterStartDatePolicy.normalizedSemesterKey(raw) ?? raw
    }

    private static func prioritize(_ entries: [CacheEntry]) -> [CacheEntry] {
        entries.sorted { a, b in
            let aTarget = a.url.range(of: targetScheduleFile, options: .caseInsensitive) != nil
            let bTarget = b.url.range(of: targetScheduleFile, options: .caseInsensitive) != nil
            if aTarget != bTarget { return aTarget }
            let aModule = a.url.range(of: "modules/xskcb", options: .caseInsensitive) != nil
            let bModule = b.url.range(of: "modules/xskcb", options: .caseInsensitive) != nil
            if aModule != bModule { return aModule }
            let aExt = a.fileName.lowercased().contains(".do") || a.fileName.lowercased().contains(".json")
            let bExt = b.fileName.lowercased().contains(".do") || b.fileName.lowercased().contains(".json")
            if aExt != bExt { return aExt }
            if a.sequence != b.sequence { return a.sequence > b.sequence }
            return a.content.utf8.count > b.content.utf8.count
        }
    }
}
