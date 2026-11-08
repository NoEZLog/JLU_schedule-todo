import Foundation

// MARK: - 学期开始日期推断（移植自 Android SemesterStartDatePolicy）

enum SemesterStartDatePolicy {
    static let codePattern = Rx(#"(20\d{2})\D+(20\d{2})\D*([12])"#)
    static let yearRangePattern = Rx(#"(20\d{2})\D+(20\d{2})"#)

    static func inferFromCourses(_ courses: [CourseSchedule], fallback: SimpleDate? = nil) -> SimpleDate {
        inferFromCoursesOrNull(courses) ?? fallback ?? defaultForToday()
    }

    static func inferFromCoursesOrNull(_ courses: [CourseSchedule]) -> SimpleDate? {
        let labels = courses.map { $0.semester.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        guard !labels.isEmpty else { return nil }
        var counts: [String: Int] = [:]
        for label in labels { counts[label, default: 0] += 1 }
        guard let dominant = counts.max(by: { $0.value < $1.value })?.key else { return nil }
        return inferFromSemesterLabelOrNull(dominant)
    }

    static func inferFromSemesterLabelOrNull(_ label: String) -> SimpleDate? {
        guard let key = normalizedSemesterKey(label) else { return nil }
        let parts = key.split(separator: "-")
        guard parts.count == 3,
              let firstYear = Int(parts[0]), let secondYear = Int(parts[1]) else { return nil }
        return startDate(forTerm: String(parts[2]), firstYear: firstYear, secondYear: secondYear)
    }

    static func normalizedSemesterKey(_ label: String) -> String? {
        let normalized = label.trimmingCharacters(in: .whitespaces)
        if normalized.isEmpty { return nil }

        if let groups = codePattern.firstMatch(in: normalized), groups.count >= 3,
           let firstYear = Int(groups[0]), let secondYear = Int(groups[1]) {
            return "\(firstYear)-\(secondYear)-\(groups[2])"
        }

        if let groups = yearRangePattern.firstMatch(in: normalized), groups.count >= 2,
           let firstYear = Int(groups[0]), let secondYear = Int(groups[1]) {
            var term: String?
            if normalized.contains("第一") || normalized.contains("第1") || normalized.contains("秋") { term = "1" }
            else if normalized.contains("第二") || normalized.contains("第2") || normalized.contains("春") { term = "2" }
            if let term = term {
                return "\(firstYear)-\(secondYear)-\(term)"
            }
        }
        return nil
    }

    static func defaultForToday(_ today: SimpleDate = .today()) -> SimpleDate {
        if today.month >= 8 {
            return mondayOnOrBefore(SimpleDate(year: today.year, month: 9, day: 1))
        }
        return mondayOnOrBefore(SimpleDate(year: today.year, month: 2, day: 24))
    }

    static func normalizeToWeekStart(_ date: SimpleDate) -> SimpleDate {
        date.previousOrSameMonday()
    }

    private static func startDate(forTerm term: String, firstYear: Int, secondYear: Int) -> SimpleDate? {
        switch term {
        case "1": return mondayOnOrBefore(SimpleDate(year: firstYear, month: 9, day: 1))
        case "2": return mondayOnOrBefore(SimpleDate(year: secondYear, month: 2, day: 24))
        default: return nil
        }
    }

    private static func mondayOnOrBefore(_ date: SimpleDate) -> SimpleDate {
        date.previousOrSameMonday()
    }
}
