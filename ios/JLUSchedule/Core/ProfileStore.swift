import Foundation

// MARK: - 多课表持久化（语义对齐 Android ImportedScheduleStorage）

final class ProfileStore {
    struct ProfileInfo: Identifiable, Hashable {
        var id: String
        var name: String
        var isActive: Bool
        var updatedAt: Date
        var semesterStartDate: SimpleDate
    }

    struct ImportResult {
        var profileId: String
        var profileName: String
        var courseCount: Int
        var isNewProfile: Bool
        var semesterStartDate: SimpleDate?
    }

    struct ManualCourseInput {
        var courseName: String
        var teacher: String
        var location: String
        var weekday: Weekday
        var startSection: Int
        var endSection: Int
        var startWeek: Int
        var endWeek: Int
        var parity: WeekParity = .all
        var semester: String = "手动添加"
    }

    private struct ProfileMeta: Codable {
        var id: String
        var name: String
        var coursesFile: String
        var createdAt: Date
        var updatedAt: Date
        var semesterStartDate: String?

        enum CodingKeys: String, CodingKey {
            case id, name, coursesFile, createdAt, updatedAt, semesterStartDate
        }

        init(id: String, name: String, coursesFile: String, createdAt: Date, updatedAt: Date, semesterStartDate: String?) {
            self.id = id; self.name = name; self.coursesFile = coursesFile
            self.createdAt = createdAt; self.updatedAt = updatedAt; self.semesterStartDate = semesterStartDate
        }

        // 宽松解码：字段缺失给默认值，避免整体解码失败导致数据"消失"
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
            name = try c.decodeIfPresent(String.self, forKey: .name) ?? "默认课表"
            coursesFile = try c.decodeIfPresent(String.self, forKey: .coursesFile) ?? "courses_\(id).json"
            createdAt = try c.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date()
            updatedAt = try c.decodeIfPresent(Date.self, forKey: .updatedAt) ?? Date()
            semesterStartDate = try c.decodeIfPresent(String.self, forKey: .semesterStartDate)
        }
    }

    private struct Meta: Codable {
        var activeId: String
        var profiles: [ProfileMeta]
    }

    static let shared = ProfileStore()
    static let defaultProfileName = "默认课表"

    private let lock = NSLock()
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    private init() {
        encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
    }

    // MARK: - 目录

    private var rootDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("JLUSchedule", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    private var metaURL: URL { rootDir.appendingPathComponent("meta.json") }
    private var legacyURL: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("imported_schedule.do")
    }

    private func coursesURL(_ fileName: String) -> URL { rootDir.appendingPathComponent(fileName) }

    // MARK: - 公开 API

    func listProfiles() -> [ProfileInfo] {
        lock.lock(); defer { lock.unlock() }
        let meta = ensureInitialized()
        return meta.profiles.map {
            ProfileInfo(id: $0.id, name: $0.name, isActive: $0.id == meta.activeId,
                        updatedAt: $0.updatedAt, semesterStartDate: semesterStart(of: $0))
        }
    }

    func setActiveProfile(_ id: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        guard meta.profiles.contains(where: { $0.id == id }) else { return false }
        meta.activeId = id
        save(meta)
        return true
    }

    func createEmptyProfile(named name: String) -> ProfileInfo {
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        let now = Date()
        let id = UUID().uuidString
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let profileName = trimmed.isEmpty ? "课表\(timestampLabel())" : trimmed
        let fileName = "courses_\(id).json"
        writeCourses([], to: fileName)
        let start = SemesterStartDatePolicy.defaultForToday()
        meta.profiles.append(ProfileMeta(id: id, name: profileName, coursesFile: fileName,
                                         createdAt: now, updatedAt: now, semesterStartDate: start.isoString))
        meta.activeId = id
        save(meta)
        return ProfileInfo(id: id, name: profileName, isActive: true, updatedAt: now, semesterStartDate: start)
    }

    func renameProfile(_ id: String, to newName: String) -> Bool {
        let trimmed = newName.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return false }
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        guard let idx = meta.profiles.firstIndex(where: { $0.id == id }) else { return false }
        meta.profiles[idx].name = trimmed
        meta.profiles[idx].updatedAt = Date()
        save(meta)
        return true
    }

    /// 先写 meta 再删课程文件，避免悬空引用
    func deleteProfile(_ id: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        guard let target = meta.profiles.first(where: { $0.id == id }), meta.profiles.count > 1 else { return false }
        let remained = meta.profiles.filter { $0.id != id }
        meta.activeId = meta.activeId == id ? remained[0].id : meta.activeId
        meta.profiles = remained
        save(meta)
        try? FileManager.default.removeItem(at: coursesURL(target.coursesFile))
        return true
    }

    func importParsed(_ courses: [CourseSchedule], mode: ImportMode,
                      newProfileName: String? = nil,
                      semesterStartDate: SimpleDate? = nil) -> ImportResult {
        lock.lock(); defer { lock.unlock() }
        let merged = mergeDuplicates(courses)
        var meta = ensureInitialized()
        let now = Date()
        let start = semesterStartDate ?? SemesterStartDatePolicy.inferFromCourses(merged)

        switch mode {
        case .overwriteActive:
            let active = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
            writeCourses(merged, to: active.coursesFile)
            if let idx = meta.profiles.firstIndex(where: { $0.id == active.id }) {
                meta.profiles[idx].updatedAt = now
                meta.profiles[idx].semesterStartDate = start.isoString
            }
            meta.activeId = active.id
            save(meta)
            return ImportResult(profileId: active.id, profileName: active.name,
                                courseCount: merged.count, isNewProfile: false, semesterStartDate: start)
        case .createNew:
            let id = UUID().uuidString
            let trimmed = newProfileName?.trimmingCharacters(in: .whitespaces)
            let profileName = (trimmed?.isEmpty ?? true) ? "课表\(timestampLabel())" : trimmed!
            let fileName = "courses_\(id).json"
            writeCourses(merged, to: fileName)
            meta.profiles.append(ProfileMeta(id: id, name: profileName, coursesFile: fileName,
                                             createdAt: now, updatedAt: now, semesterStartDate: start.isoString))
            meta.activeId = id
            save(meta)
            return ImportResult(profileId: id, profileName: profileName,
                                courseCount: merged.count, isNewProfile: true, semesterStartDate: start)
        }
    }

    enum ImportMode { case overwriteActive, createNew }

    func addManualCourse(_ input: ManualCourseInput) throws {
        func validate(_ condition: Bool, _ message: String) throws {
            guard condition else { throw NSError(domain: "ProfileStore", code: 2,
                                                userInfo: [NSLocalizedDescriptionKey: message]) }
        }
        try validate(!input.courseName.trimmingCharacters(in: .whitespaces).isEmpty, "课程名不能为空")
        try validate((1...12).contains(input.startSection), "开始节必须在 1-12 之间")
        try validate((1...12).contains(input.endSection), "结束节必须在 1-12 之间")
        try validate(input.endSection >= input.startSection, "结束节不能小于开始节")
        try validate((1...30).contains(input.startWeek), "开始周必须在 1-30 之间")
        try validate((1...30).contains(input.endWeek), "结束周必须在 1-30 之间")
        try validate(input.endWeek >= input.startWeek, "结束周不能小于开始周")

        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        let active = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        var courses = readCourses(active.coursesFile)
        let course = CourseSchedule(
            courseName: input.courseName.trimmingCharacters(in: .whitespaces),
            teacher: input.teacher,
            semester: input.semester,
            credit: nil,
            rawWeekText: "\(input.startWeek)-\(input.endWeek)周",
            meetings: [MeetingTime(weekday: input.weekday, startSection: input.startSection,
                                   endSection: input.endSection,
                                   weekRules: [WeekRule(startWeek: input.startWeek, endWeek: input.endWeek, parity: input.parity)],
                                   location: input.location)]
        )
        courses.append(course)
        writeCourses(courses, to: active.coursesFile)
        if let idx = meta.profiles.firstIndex(where: { $0.id == active.id }) {
            meta.profiles[idx].updatedAt = Date()
        }
        save(meta)
    }

    func loadActiveCourses() -> [CourseSchedule] {
        lock.lock(); defer { lock.unlock() }
        let meta = ensureInitialized()
        let active = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        return readCourses(active.coursesFile)
    }

    func getActiveSemesterStartDate() -> SimpleDate {
        lock.lock(); defer { lock.unlock() }
        let meta = ensureInitialized()
        let active = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        return semesterStart(of: active)
    }

    func setActiveSemesterStartDate(_ date: SimpleDate) {
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        let active = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        if let idx = meta.profiles.firstIndex(where: { $0.id == active.id }) {
            meta.profiles[idx].semesterStartDate = SemesterStartDatePolicy.normalizeToWeekStart(date).isoString
            meta.profiles[idx].updatedAt = Date()
        }
        save(meta)
    }

    func exportBackup() -> String {
        lock.lock(); defer { lock.unlock() }
        let meta = ensureInitialized()
        let profiles = meta.profiles.map { profile in
            ScheduleBackupCodec.BackupProfile(
                name: profile.name,
                semesterStartDate: profile.semesterStartDate,
                courses: readCourses(profile.coursesFile).map { ScheduleBackupCodec.toBackupCourse($0) }
            )
        }
        let formatter = ISO8601DateFormatter()
        return ScheduleBackupCodec.encode(exportedAt: formatter.string(from: Date()), profiles: profiles)
    }

    /// 用备份替换全部课表，返回恢复数量
    func importBackup(_ content: String) throws -> Int {
        guard let backupProfiles = ScheduleBackupCodec.decode(content) else {
            throw NSError(domain: "ProfileStore", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "备份文件格式不正确"])
        }
        lock.lock(); defer { lock.unlock() }
        let existing = (try? FileManager.default.contentsOfDirectory(at: rootDir, includingPropertiesForKeys: nil)) ?? []
        for url in existing where url.lastPathComponent.hasPrefix("courses_") && url.pathExtension == "json" {
            try? FileManager.default.removeItem(at: url)
        }
        let now = Date()
        var newProfiles: [ProfileMeta] = []
        for backup in backupProfiles {
            let id = UUID().uuidString
            let fileName = "courses_\(id).json"
            writeCourses(backup.courses.map { ScheduleBackupCodec.toCourseSchedule($0) }, to: fileName)
            newProfiles.append(ProfileMeta(id: id, name: backup.name.isEmpty ? "课表\(timestampLabel())" : backup.name,
                                           coursesFile: fileName, createdAt: now, updatedAt: now,
                                           semesterStartDate: backup.semesterStartDate))
        }
        if newProfiles.isEmpty {
            _ = ensureInitialized()
        } else {
            save(Meta(activeId: newProfiles[0].id, profiles: newProfiles))
        }
        return newProfiles.count
    }

    func exportActiveTimetableText(periodRanges: [String]) -> String {
        lock.lock(); defer { lock.unlock() }
        let meta = ensureInitialized()
        let active = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        let courses = readCourses(active.coursesFile)
        let start = semesterStart(of: active)
        let maxWeek = courses.flatMap { $0.meetings }.flatMap { $0.weekRules }.map { $0.endWeek }.max() ?? 0

        var out = "课表：\(active.name)\n学期开始：\(start.isoString)（第1周周一）\n总周数：\(maxWeek)\n"
        for weekday in Weekday.allCases {
            out += "\n【周\(weekday.shortLabel)】\n"
            var meetings: [(CourseSchedule, MeetingTime)] = []
            for course in courses {
                for meeting in course.meetings where meeting.weekday == weekday {
                    meetings.append((course, meeting))
                }
            }
            meetings.sort { $0.1.startSection < $1.1.startSection }
            var seen = Set<String>()
            let distinct = meetings.filter { item in
                let key = "\(item.0.courseName)|\(item.1.startSection)|\(item.1.endSection)|\(item.1.location)|\(item.0.rawWeekText)"
                return seen.insert(key).inserted
            }
            if distinct.isEmpty { out += "（无课程）\n" }
            for (course, meeting) in distinct {
                let startText = periodRanges.indices.contains(meeting.startSection - 1)
                    ? periodRanges[meeting.startSection - 1].components(separatedBy: "-")[0] : "--:--"
                let endText = periodRanges.indices.contains(meeting.endSection - 1)
                    ? periodRanges[meeting.endSection - 1].components(separatedBy: "-")[1] : "--:--"
                out += "第\(meeting.startSection)-\(meeting.endSection)节 \(startText)-\(endText)  \(course.courseName)"
                if !meeting.location.isEmpty { out += "  \(meeting.location)" }
                if !course.teacher.isEmpty { out += "  \(course.teacher)" }
                if !course.rawWeekText.isEmpty { out += "  \(course.rawWeekText)" }
                out += "\n"
            }
        }
        return out
    }

    // MARK: - 内部实现

    private func ensureInitialized() -> Meta {
        let fm = FileManager.default
        if !fm.fileExists(atPath: rootDir.path) {
            try? fm.createDirectory(at: rootDir, withIntermediateDirectories: true)
        }
        if fm.fileExists(atPath: metaURL.path), let data = try? Data(contentsOf: metaURL), !data.isEmpty {
            if let meta = try? decoder.decode(Meta.self, from: data), !meta.profiles.isEmpty {
                let migrated = fillMissingSemesterStarts(meta)
                if migrated != meta { save(migrated) }
                return migrated
            }
            // meta 损坏：保留现场，尝试从课程文件恢复
            let backup = rootDir.appendingPathComponent("meta.json.corrupt-\(Int(Date().timeIntervalSince1970))")
            try? fm.moveItem(at: metaURL, to: backup)
        }

        if let salvaged = salvageOrphans() {
            save(salvaged)
            return salvaged
        }
        return initializeDefault()
    }

    private func salvageOrphans() -> Meta? {
        let fm = FileManager.default
        guard let urls = try? fm.contentsOfDirectory(at: rootDir, includingPropertiesForKeys: [.contentModificationDateKey]) else {
            return nil
        }
        let orphanFiles = urls.filter {
            $0.lastPathComponent.hasPrefix("courses_") && $0.pathExtension == "json"
        }.sorted { a, b in
            let da = (try? a.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            let db = (try? b.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            return da > db
        }
        guard !orphanFiles.isEmpty else { return nil }

        var profiles: [ProfileMeta] = []
        for (index, url) in orphanFiles.enumerated() {
            let id = url.deletingPathExtension().lastPathComponent
            guard !id.isEmpty else { continue }
            let courses = readCourses(url.lastPathComponent)
            profiles.append(ProfileMeta(
                id: id,
                name: index == 0 ? Self.defaultProfileName : "恢复课表\(index + 1)",
                coursesFile: url.lastPathComponent,
                createdAt: (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date(),
                updatedAt: (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date(),
                semesterStartDate: SemesterStartDatePolicy.inferFromCourses(courses).isoString
            ))
        }
        guard !profiles.isEmpty else { return nil }
        return Meta(activeId: profiles[0].id, profiles: profiles)
    }

    private func initializeDefault() -> Meta {
        let id = UUID().uuidString
        let fileName = "courses_\(id).json"
        let courses = loadLegacyCourses()
        writeCourses(courses, to: fileName)
        let now = Date()
        let start = SemesterStartDatePolicy.inferFromCourses(courses)
        let meta = Meta(activeId: id, profiles: [
            ProfileMeta(id: id, name: Self.defaultProfileName, coursesFile: fileName,
                        createdAt: now, updatedAt: now, semesterStartDate: start.isoString)
        ])
        save(meta)
        return meta
    }

    /// 兼容旧版散落的 .do 文件（如有）
    private func loadLegacyCourses() -> [CourseSchedule] {
        let fm = FileManager.default
        guard fm.fileExists(atPath: legacyURL.path) else { return [] }
        defer { try? fm.removeItem(at: legacyURL) }
        guard let text = try? String(contentsOf: legacyURL, encoding: .utf8) else { return [] }
        return (try? DoScheduleParser.parse(text)) ?? []
    }

    private func fillMissingSemesterStarts(_ meta: Meta) -> Meta {
        var changed = false
        var profiles = meta.profiles
        for i in profiles.indices where profiles[i].semesterStartDate == nil || profiles[i].semesterStartDate!.isEmpty {
            let courses = readCourses(profiles[i].coursesFile)
            profiles[i].semesterStartDate = SemesterStartDatePolicy.inferFromCourses(courses).isoString
            changed = true
        }
        return changed ? Meta(activeId: meta.activeId, profiles: profiles) : meta
    }

    private func semesterStart(of profile: ProfileMeta) -> SimpleDate {
        guard let raw = profile.semesterStartDate, let date = SimpleDate.parse(raw) else {
            return SemesterStartDatePolicy.defaultForToday()
        }
        return SemesterStartDatePolicy.normalizeToWeekStart(date)
    }

    private func readCourses(_ fileName: String) -> [CourseSchedule] {
        let url = coursesURL(fileName)
        guard let data = try? Data(contentsOf: url), !data.isEmpty else { return [] }
        return (try? decoder.decode([PersistedCourse].self, from: data)).map { $0.map { $0.toCourseSchedule() } } ?? []
    }

    private func writeCourses(_ courses: [CourseSchedule], to fileName: String) {
        let persisted = courses.map { PersistedCourse(from: $0) }
        guard let data = try? encoder.encode(persisted) else { return }
        try? data.write(to: coursesURL(fileName), options: .atomic)
    }

    private func save(_ meta: Meta) {
        guard let data = try? encoder.encode(meta) else { return }
        try? data.write(to: metaURL, options: .atomic)
    }

    private func mergeDuplicates(_ courses: [CourseSchedule]) -> [CourseSchedule] {
        var seen = Set<String>()
        var result: [CourseSchedule] = []
        for course in courses {
            var key = "\(course.courseName)|\(course.teacher)|\(course.semester)|\(course.credit ?? -1)|\(course.rawWeekText)|"
            let meetings = course.meetings.sorted {
                ($0.weekday.rawValue, $0.startSection) < ($1.weekday.rawValue, $1.startSection)
            }
            for meeting in meetings {
                key += "\(meeting.weekday.rawValue):\(meeting.startSection)-\(meeting.endSection)@\(meeting.location)#"
                key += meeting.weekRules.map { "\($0.startWeek)-\($0.endWeek)(\($0.parity.rawValue))" }.joined(separator: ",")
                key += ";"
            }
            if seen.insert(key).inserted { result.append(course) }
        }
        return result
    }

    private func timestampLabel() -> String {
        let f = DateFormatter()
        f.dateFormat = "MMdd_HHmm"
        return f.string(from: Date())
    }
}

// MARK: - 持久化模型（与 Android PersistedCourse 结构一致）

private struct PersistedCourse: Codable {
    var courseName: String
    var teacher: String
    var semester: String
    var credit: Double?
    var rawWeekText: String
    var meetings: [PersistedMeeting]

    init(from course: CourseSchedule) {
        courseName = course.courseName
        teacher = course.teacher
        semester = course.semester
        credit = course.credit
        rawWeekText = course.rawWeekText
        meetings = course.meetings.map { PersistedMeeting(from: $0) }
    }

    func toCourseSchedule() -> CourseSchedule {
        CourseSchedule(courseName: courseName, teacher: teacher, semester: semester,
                       credit: credit, rawWeekText: rawWeekText,
                       meetings: meetings.map { $0.toMeetingTime() })
    }
}

private struct PersistedMeeting: Codable {
    var weekday: String
    var startSection: Int
    var endSection: Int
    var weekRules: [PersistedWeekRule]
    var location: String

    init(from meeting: MeetingTime) {
        weekday = meeting.weekday.rawValue.description
        startSection = meeting.startSection
        endSection = meeting.endSection
        weekRules = meeting.weekRules.map { PersistedWeekRule(startWeek: $0.startWeek, endWeek: $0.endWeek, parity: $0.parity.rawValue) }
        location = meeting.location
    }

    func toMeetingTime() -> MeetingTime {
        MeetingTime(weekday: Weekday(rawValue: Int(weekday) ?? 1) ?? .monday,
                    startSection: startSection, endSection: endSection,
                    weekRules: weekRules.map {
                        WeekRule(startWeek: $0.startWeek, endWeek: $0.endWeek,
                                 parity: WeekParity(rawValue: $0.parity) ?? .all)
                    },
                    location: location)
    }
}

private struct PersistedWeekRule: Codable {
    var startWeek: Int
    var endWeek: Int
    var parity: String
}
