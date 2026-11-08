import Foundation

// MARK: - 教务 .do 数据解析（移植自 Android DoScheduleParser）

enum DoScheduleParser {
    struct ParseError: Error { let message: String }

    static let defaultTotalWeeks = 20
    static let maxWeek = 30

    static let segmentRegex = Rx(#"^\s*(.*?)\s*星期([一二三四五六日天])\s*第\s*(\d+)\s*节?\s*[-—–~～至]\s*第?\s*(\d+)\s*节?\s*(.*)$"#)
    static let weekItemRegex = Rx(#"第?(\d+)\s*(?:-\s*(\d+))?\s*周(?:\s*[（(]\s*(单|双)\s*[)）]|\s*[单双])?"#)
    static let discreteWeeksRegex = Rx(#"^第?\s*(\d+(?:\s*[,，、]\s*\d+)+)\s*周?\s*(?:[（(]\s*(单|双)\s*[)）])?\s*周?$"#)

    static func parse(_ rawContent: String) throws -> [CourseSchedule] {
        guard let data = rawContent.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ParseError(message: "invalid json")
        }
        guard let datas = root["datas"] as? [String: Any] else { return [] }

        var rows: [[String: Any]] = []
        for case let table as [String: Any] in datas.values {
            if let tableRows = table["rows"] as? [[String: Any]] {
                rows.append(contentsOf: tableRows)
            }
        }

        var seen = Set<String>()
        var unique: [[String: Any]] = []
        for row in rows {
            let key = [text(row["KCM"]), text(row["SKJS"]), text(row["YPSJDD"]), text(row["ZCMC"]),
                       text(row["SKXQ"]), text(row["KSJC"]), text(row["JSJC"]),
                       text(row["JASMC"]), text(row["JXLDM_DISPLAY"])].joined(separator: "|")
            if seen.insert(key).inserted {
                unique.append(row)
            }
        }

        return unique.map { row in
            let semester = {
                let display = text(row["XNXQDM_DISPLAY"]).trimmingCharacters(in: .whitespaces)
                if !display.isEmpty { return display }
                return text(row["XNXQDM"]).trimmingCharacters(in: .whitespaces)
            }()
            return CourseSchedule(
                courseName: text(row["KCM"]),
                teacher: text(row["SKJS"]),
                semester: semester,
                credit: double(row["XF"]),
                rawWeekText: text(row["ZCMC"]),
                meetings: meetings(of: row)
            )
        }
    }

    // MARK: 排版时间解析

    private static func meetings(of row: [String: Any]) -> [MeetingTime] {
        let arranged = text(row["YPSJDD"]).trimmingCharacters(in: .whitespacesAndNewlines)
        if arranged.isEmpty {
            return fallbackMeeting(row).map { [$0] } ?? []
        }
        let parsed = splitSegments(arranged).compactMap { parseSegment($0, row) }
        if parsed.isEmpty {
            return fallbackMeeting(row).map { [$0] } ?? []
        }
        return parsed
    }

    private static func splitSegments(_ text: String) -> [String] {
        let separators = CharacterSet(charactersIn: ",，;；\n")
        let pieces = text.components(separatedBy: separators).map { $0.trimmingCharacters(in: .whitespaces) }
        var result: [String] = []
        var buffer = ""
        for piece in pieces {
            if !buffer.isEmpty { buffer.append('，') }
            buffer.append(piece)
            if buffer.contains("星期") && buffer.contains("节") {
                result.append(buffer.trimmingCharacters(in: .whitespaces))
                buffer = ""
            }
        }
        if !buffer.isEmpty {
            result.append(buffer.trimmingCharacters(in: .whitespaces))
        }
        return result.filter { !$0.isEmpty }
    }

    private static func parseSegment(_ segment: String, _ row: [String: Any]) -> MeetingTime? {
        guard let groups = segmentRegex.matchEntire(segment), groups.count >= 5 else { return nil }
        let weekExpr = groups[0].trimmingCharacters(in: .whitespaces)
        guard let weekday = Weekday.from(chinese: groups[1]),
              let start = Int(groups[2]), let end = Int(groups[3]) else { return nil }
        var location = groups[4].trimmingCharacters(in: .whitespaces)
        if location.isEmpty {
            location = text(row["JASMC"]).isEmpty ? text(row["JXLDM_DISPLAY"]) : text(row["JASMC"])
        }
        return MeetingTime(
            weekday: weekday,
            startSection: min(start, end),
            endSection: max(start, end),
            weekRules: parseWeekRules(weekExpr),
            location: location
        )
    }

    private static func fallbackMeeting(_ row: [String: Any]) -> MeetingTime? {
        guard let weekdayNumber = intVal(row["SKXQ"]),
              let weekday = Weekday(rawValue: weekdayNumber),
              let start = intVal(row["KSJC"]),
              let end = intVal(row["JSJC"]) else { return nil }
        let location = text(row["JASMC"]).isEmpty ? text(row["JXLDM_DISPLAY"]) : text(row["JASMC"])
        return MeetingTime(
            weekday: weekday,
            startSection: min(start, end),
            endSection: max(start, end),
            weekRules: parseWeekRules(text(row["ZCMC"])),
            location: location
        )
    }

    static func parseWeekRules(_ weekExpr: String) -> [WeekRule] {
        let expr = weekExpr.trimmingCharacters(in: .whitespaces)
        if expr.isEmpty {
            return [WeekRule(startWeek: 1, endWeek: defaultTotalWeeks, parity: .all)]
        }
        if !expr.contains(where: { $0.isNumber }) {
            let parity: WeekParity
            if expr.contains("单") { parity = .odd }
            else if expr.contains("双") { parity = .even }
            else { parity = .all }
            return [WeekRule(startWeek: 1, endWeek: defaultTotalWeeks, parity: parity)]
        }

        if let groups = discreteWeeksRegex.matchEntire(expr), groups.count >= 1 {
            let parity: WeekParity
            if groups.count > 1 && groups[1] == "单" { parity = .odd }
            else if groups.count > 1 && groups[1] == "双" { parity = .even }
            else { parity = .all }
            let weeks = groups[0]
                .components(separatedBy: CharacterSet(charactersIn: ",，、"))
                .compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
                .filter { (1...maxWeek).contains($0) }
            if !weeks.isEmpty {
                return weeks.map { WeekRule(startWeek: $0, endWeek: $0, parity: parity) }
            }
        }

        let separators = CharacterSet(charactersIn: ",，、;；")
        let rules = expr.components(separatedBy: separators).compactMap(parseWeekItem)
        return rules.isEmpty ? [WeekRule(startWeek: 1, endWeek: defaultTotalWeeks, parity: .all)] : rules
    }

    private static func parseWeekItem(_ part: String) -> WeekRule? {
        guard let groups = weekItemRegex.firstMatch(in: part.trimmingCharacters(in: .whitespaces)),
              groups.count >= 2,
              let start = Int(groups[0]) else { return nil }
        // 第 2 组（结束周）为可选，缺省时与开始周相同
        let end = Int(groups[1]) ?? start
        let parity: WeekParity
        if groups[2] == "单" { parity = .odd }
        else if groups[2] == "双" { parity = .even }
        else { parity = .all }
        guard (1...maxWeek).contains(start), (1...maxWeek).contains(end) else { return nil }
        return start <= end ? WeekRule(startWeek: start, endWeek: end, parity: parity)
                            : WeekRule(startWeek: end, endWeek: start, parity: parity)
    }

    // MARK: 宽松取值

    static func text(_ value: Any?) -> String {
        if let s = value as? String { return s }
        if let n = value as? NSNumber { return n.stringValue }
        return ""
    }

    static func intVal(_ value: Any?) -> Int? {
        if let n = value as? NSNumber { return n.intValue }
        if let s = value as? String { return Int(s.trimmingCharacters(in: .whitespaces)) }
        return nil
    }

    static func double(_ value: Any?) -> Double? {
        if let n = value as? NSNumber { return n.doubleValue }
        if let s = value as? String { return Double(s.trimmingCharacters(in: .whitespaces)) }
        return nil
    }
}

// MARK: - 正则小工具（组值从 1 号捕获组开始返回）

struct Rx {
    let regex: NSRegularExpression

    init(_ pattern: String) {
        regex = try! NSRegularExpression(pattern: pattern)
    }

    /// 返回捕获组数组（下标 0 = 第 1 个捕获组）；可选组缺失时为空字符串
    func firstMatch(in text: String) -> [String]? {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = regex.firstMatch(in: text, range: range) else { return nil }
        var groups: [String] = []
        for i in 1..<match.numberOfRanges {
            let r = match.range(at: i)
            groups.append(r.location != NSNotFound ? String(text[Range(r, in: text)!]) : "")
        }
        return groups
    }

    /// 等价 Kotlin matchEntire：要求整串匹配
    func matchEntire(_ text: String) -> [String]? {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = regex.firstMatch(in: text, range: range),
              match.range.location == 0, match.range.length == text.utf16.count else { return nil }
        var groups: [String] = []
        for i in 1..<match.numberOfRanges {
            let r = match.range(at: i)
            groups.append(r.location != NSNotFound ? String(text[Range(r, in: text)!]) : "")
        }
        return groups
    }
}
