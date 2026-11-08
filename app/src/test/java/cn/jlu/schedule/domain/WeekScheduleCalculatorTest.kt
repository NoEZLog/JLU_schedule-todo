package cn.jlu.schedule.domain

import cn.jlu.schedule.model.CourseSchedule
import cn.jlu.schedule.model.MeetingTime
import cn.jlu.schedule.model.WeekParity
import cn.jlu.schedule.model.WeekRule
import cn.jlu.schedule.model.Weekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeekScheduleCalculatorTest {
    private fun course(
        weekRules: List<WeekRule>,
        weekday: Weekday = Weekday.MONDAY,
        startSection: Int = 1,
        endSection: Int = 2
    ): CourseSchedule {
        return CourseSchedule(
            courseName = "测试课程",
            teacher = "老师",
            semester = "2026-2027-1",
            credit = null,
            rawWeekText = "custom",
            meetings = listOf(MeetingTime(weekday, startSection, endSection, weekRules, "教室"))
        )
    }

    @Test
    fun totalWeeks_returnsMaxEndWeekAcrossAllCourses() {
        val courses = listOf(
            course(listOf(WeekRule(1, 16))),
            course(listOf(WeekRule(2, 20, WeekParity.ODD)), weekday = Weekday.TUESDAY)
        )
        assertEquals(20, WeekScheduleCalculator.totalWeeks(courses))
    }

    @Test
    fun totalWeeks_fallsBackToDefaultWhenNoCourses() {
        assertEquals(20, WeekScheduleCalculator.totalWeeks(emptyList()))
    }

    @Test
    fun guessCurrentWeek_returnsFirstWeekOnSemesterStart() {
        val start = LocalDate.of(2026, 8, 31)
        assertEquals(1, WeekScheduleCalculator.guessCurrentWeek(start, start, 20))
        assertEquals(1, WeekScheduleCalculator.guessCurrentWeek(start, start.plusDays(6), 20))
    }

    @Test
    fun guessCurrentWeek_advancesEverySevenDays() {
        val start = LocalDate.of(2026, 8, 31)
        assertEquals(2, WeekScheduleCalculator.guessCurrentWeek(start, start.plusDays(7), 20))
        assertEquals(3, WeekScheduleCalculator.guessCurrentWeek(start, start.plusDays(14), 20))
    }

    @Test
    fun guessCurrentWeek_clampsBeforeSemesterStart() {
        val start = LocalDate.of(2026, 8, 31)
        assertEquals(1, WeekScheduleCalculator.guessCurrentWeek(start, start.minusDays(1), 20))
        assertEquals(1, WeekScheduleCalculator.guessCurrentWeek(start, start.minusDays(30), 20))
    }

    @Test
    fun guessCurrentWeek_clampsToTotalWeeks() {
        val start = LocalDate.of(2026, 8, 31)
        assertEquals(20, WeekScheduleCalculator.guessCurrentWeek(start, start.plusDays(365), 20))
    }

    @Test
    fun activeWeekOrNull_excludesDatesOutsideSemester() {
        val start = LocalDate.of(2026, 8, 31)
        assertNull(WeekScheduleCalculator.activeWeekOrNull(start, start.minusDays(1), 20))
        assertEquals(1, WeekScheduleCalculator.activeWeekOrNull(start, start, 20))
        assertEquals(20, WeekScheduleCalculator.activeWeekOrNull(start, start.plusDays(139), 20))
        assertNull(WeekScheduleCalculator.activeWeekOrNull(start, start.plusDays(140), 20))
    }

    @Test
    fun meetingsForWeek_respectsParityRules() {
        val courses = listOf(course(listOf(WeekRule(1, 16, WeekParity.ODD))))
        assertEquals(1, WeekScheduleCalculator.meetingsForWeek(courses, 1).size)
        assertEquals(0, WeekScheduleCalculator.meetingsForWeek(courses, 2).size)
        assertEquals(1, WeekScheduleCalculator.meetingsForWeek(courses, 3).size)
    }

    @Test
    fun meetingsForWeek_treatsEmptyRulesAsEveryWeek() {
        val courses = listOf(course(emptyList()))
        assertEquals(1, WeekScheduleCalculator.meetingsForWeek(courses, 5).size)
        assertEquals(1, WeekScheduleCalculator.meetingsForWeek(courses, 18).size)
    }

    @Test
    fun meetingsForDisplayWeek_showsEndedCoursesInPastWeeks() {
        // 第 10 周回看第 4 周：只上 1-5 周单周的课当时真实存在，应显示“第 5 周”提示
        val courses = listOf(course(listOf(WeekRule(1, 5, WeekParity.ODD))))
        val display = WeekScheduleCalculator.meetingsForDisplayWeek(
            courses = courses,
            week = 4,
            baseWeek = 10,
            showNonCurrent = true
        )
        assertEquals(1, display.size)
        val ref = display.single()
        assertTrue(ref.isCurrentWeek.not())
        assertEquals(5, ref.nextActiveWeek)
    }

    @Test
    fun meetingsForDisplayWeek_marksCurrentWeekInPastView() {
        val courses = listOf(course(listOf(WeekRule(1, 5, WeekParity.ODD))))
        val display = WeekScheduleCalculator.meetingsForDisplayWeek(
            courses = courses,
            week = 3,
            baseWeek = 10,
            showNonCurrent = true
        )
        assertEquals(1, display.size)
        assertTrue(display.single().isCurrentWeek)
    }

    @Test
    fun meetingsForDisplayWeek_hidesNonCurrentWhenDisabled() {
        val courses = listOf(course(listOf(WeekRule(6, 10))))
        val display = WeekScheduleCalculator.meetingsForDisplayWeek(
            courses = courses,
            week = 2,
            baseWeek = 2,
            showNonCurrent = false
        )
        assertTrue(display.isEmpty())
    }

    @Test
    fun meetingsForDisplayWeek_showsUpcomingCourseInCurrentView() {
        val courses = listOf(course(listOf(WeekRule(6, 10))))
        val display = WeekScheduleCalculator.meetingsForDisplayWeek(
            courses = courses,
            week = 2,
            baseWeek = 2,
            showNonCurrent = true
        )
        assertEquals(1, display.size)
        assertEquals(6, display.single().nextActiveWeek)
    }

    @Test
    fun meetingsForDisplayWeek_dropsCourseWithNoFutureMeetings() {
        val courses = listOf(course(listOf(WeekRule(1, 2))))
        val display = WeekScheduleCalculator.meetingsForDisplayWeek(
            courses = courses,
            week = 3,
            baseWeek = 3,
            showNonCurrent = true
        )
        assertTrue(display.isEmpty())
    }

    @Test
    fun meetingsForDisplayWeek_mapsCourseIndexCorrectly() {
        val courses = listOf(
            course(listOf(WeekRule(1, 2))),
            course(listOf(WeekRule(1, 16)), weekday = Weekday.WEDNESDAY, startSection = 3, endSection = 4)
        )
        val display = WeekScheduleCalculator.meetingsForDisplayWeek(
            courses = courses,
            week = 5,
            baseWeek = 5,
            showNonCurrent = true
        )
        assertNotNull(display.singleOrNull { it.courseIndex == 1 })
        assertEquals(Weekday.WEDNESDAY, display.single { it.courseIndex == 1 }.meeting.weekday)
    }
}
