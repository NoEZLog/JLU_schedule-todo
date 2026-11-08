package cn.jlu.schedule.data

import cn.jlu.schedule.model.CourseSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SemesterStartDatePolicyTest {
    @Test
    fun inferFromSemesterLabel_returnsFallTermMonday() {
        assertEquals(
            LocalDate.of(2026, 8, 31),
            SemesterStartDatePolicy.inferFromSemesterLabelOrNull("2026-2027学年第一学期")
        )
    }

    @Test
    fun inferFromSemesterLabel_returnsSpringTermMonday() {
        assertEquals(
            LocalDate.of(2027, 2, 22),
            SemesterStartDatePolicy.inferFromSemesterLabelOrNull("2026-2027学年第二学期")
        )
    }

    @Test
    fun inferFromSemesterLabel_supportsAutumnKeyword() {
        assertEquals(
            LocalDate.of(2026, 8, 31),
            SemesterStartDatePolicy.inferFromSemesterLabelOrNull("2026-2027学年秋季")
        )
    }

    @Test
    fun inferFromSemesterLabel_returnsNullForUnrecognizedLabel() {
        assertNull(SemesterStartDatePolicy.inferFromSemesterLabelOrNull("未知学期"))
        assertNull(SemesterStartDatePolicy.inferFromSemesterLabelOrNull(""))
    }

    @Test
    fun normalizeToWeekStart_returnsMondayOfSameWeek() {
        // 2026-09-02 是周三，归一到 2026-08-31 周一
        assertEquals(
            LocalDate.of(2026, 8, 31),
            SemesterStartDatePolicy.normalizeToWeekStart(LocalDate.of(2026, 9, 2))
        )
        assertEquals(
            LocalDate.of(2026, 8, 31),
            SemesterStartDatePolicy.normalizeToWeekStart(LocalDate.of(2026, 8, 31))
        )
    }

    @Test
    fun defaultForToday_usesFallTermFromAugust() {
        assertEquals(
            LocalDate.of(2026, 8, 31),
            SemesterStartDatePolicy.defaultForToday(LocalDate.of(2026, 8, 15))
        )
    }

    @Test
    fun defaultForToday_usesSpringTermBeforeAugust() {
        assertEquals(
            LocalDate.of(2026, 2, 23),
            SemesterStartDatePolicy.defaultForToday(LocalDate.of(2026, 3, 15))
        )
    }

    @Test
    fun defaultForToday_inJanuaryPointsToUpcomingSpringTerm() {
        assertEquals(
            LocalDate.of(2027, 2, 22),
            SemesterStartDatePolicy.defaultForToday(LocalDate.of(2027, 1, 10))
        )
    }

    @Test
    fun inferFromCourses_usesDominantSemesterLabel() {
        val courses = listOf(
            courseWithSemester("2026-2027学年第一学期"),
            courseWithSemester("2026-2027学年第一学期"),
            courseWithSemester("2025-2026学年第二学期")
        )
        assertEquals(LocalDate.of(2026, 8, 31), SemesterStartDatePolicy.inferFromCourses(courses))
    }

    @Test
    fun inferFromCourses_fallsBackWhenNoSemesterInfo() {
        val courses = listOf(courseWithSemester("  "))
        assertEquals(
            LocalDate.of(2026, 3, 15),
            SemesterStartDatePolicy.inferFromCourses(courses, LocalDate.of(2026, 3, 15))
        )
    }

    private fun courseWithSemester(semester: String): CourseSchedule {
        return CourseSchedule(
            courseName = "课程",
            teacher = "老师",
            semester = semester,
            credit = null,
            rawWeekText = "1-16周",
            meetings = emptyList()
        )
    }
}
