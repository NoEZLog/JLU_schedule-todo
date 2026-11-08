package cn.jlu.schedule.ui.timetable

import cn.jlu.schedule.domain.CourseMeetingDisplayRef
import cn.jlu.schedule.model.CourseSchedule
import cn.jlu.schedule.model.MeetingTime
import cn.jlu.schedule.model.Weekday
import cn.jlu.schedule.ui.theme.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekTimetableRendererConflictTest {

    private val renderer = WeekTimetableRenderer(
        periodRanges = listOf(
            "08:00-08:45", "08:55-09:40", "10:00-10:45", "10:55-11:40",
            "13:30-14:15", "14:25-15:10", "15:30-16:15", "16:25-17:10",
            "18:20-19:05", "19:15-20:00", "20:10-20:55", "21:05-21:50"
        ),
        weekdayLabels = emptyMap(),
        fontScale = 0.95f,
        palette = ThemePalette(
            pageBackground = 0,
            navBackground = 0,
            panelBackground = 0,
            panelAltBackground = 0,
            textPrimary = 0,
            textSecondary = 0,
            iconTint = 0,
            buttonBackground = 0,
            buttonText = 0,
            gridHeader = 0,
            gridHeaderToday = 0,
            gridLeftColumn = 0,
            gridDayCell = 0,
            gridDayToday = 0,
            detailCard = 0,
            detailTitle = 0,
            detailBody = 0,
            detailMeta = 0,
            isDark = false
        ),
        hasCustomBackground = false
    )

    private fun createItem(
        index: Int,
        name: String,
        weekday: Weekday,
        start: Int,
        end: Int,
        isCurrentWeek: Boolean = true,
        nextActiveWeek: Int = 1
    ): CourseMeetingDisplayRef {
        val course = CourseSchedule(
            courseName = name,
            teacher = "测试教师",
            semester = "2026-2027学年第1学期",
            credit = 2.0,
            rawWeekText = "1-16周",
            meetings = emptyList()
        )
        val meeting = MeetingTime(
            weekday = weekday,
            startSection = start,
            endSection = end,
            weekRules = emptyList(),
            location = "测试教室"
        )
        return CourseMeetingDisplayRef(
            courseIndex = index,
            course = course,
            meeting = meeting,
            isCurrentWeek = isCurrentWeek,
            nextActiveWeek = nextActiveWeek
        )
    }

    @Test
    fun testNonOverlappingCoursesFormSeparateSlots() {
        val courseA = createItem(0, "高等数学", Weekday.THURSDAY, 1, 2)
        val courseB = createItem(1, "大学物理", Weekday.THURSDAY, 3, 4)

        val slots = renderer.resolveSlotsForDay(null, Weekday.THURSDAY, listOf(courseA, courseB))
        assertEquals(2, slots.size)
        assertFalse(slots[0].hasConflict)
        assertEquals("高等数学", slots[0].primary.course.courseName)
        assertFalse(slots[1].hasConflict)
        assertEquals("大学物理", slots[1].primary.course.courseName)
    }

    @Test
    fun testOverlappingCurrentWeekCoursesFormConflict() {
        val danpianji = createItem(0, "单片机控制与应用实验", Weekday.THURSDAY, 1, 4, isCurrentWeek = true)
        val lisan = createItem(1, "离散数学Ⅱ", Weekday.THURSDAY, 1, 2, isCurrentWeek = true)

        val slots = renderer.resolveSlotsForDay(null, Weekday.THURSDAY, listOf(danpianji, lisan))
        assertEquals(1, slots.size)
        assertTrue(slots[0].hasConflict)
        assertEquals(2, slots[0].allCourses.size)
        // 节次跨度长 (1-4) 优先作为默认封面
        assertEquals("单片机控制与应用实验", slots[0].primary.course.courseName)
    }

    @Test
    fun testPinnedCourseTakesPrecedence() {
        val danpianji = createItem(0, "单片机控制与应用实验", Weekday.THURSDAY, 1, 4, isCurrentWeek = true)
        val lisan = createItem(1, "离散数学Ⅱ", Weekday.THURSDAY, 1, 2, isCurrentWeek = true)

        // 用户置顶了离散数学Ⅱ
        val slots = renderer.resolveSlotsForDay(null, Weekday.THURSDAY, listOf(danpianji, lisan)) { _, sec ->
            if (sec in 1..4) "离散数学Ⅱ" else null
        }

        assertEquals(1, slots.size)
        assertTrue(slots[0].hasConflict)
        assertEquals("离散数学Ⅱ", slots[0].primary.course.courseName)
        assertEquals(2, slots[0].allCourses.size)
    }

    @Test
    fun testShortPinnedCoverKeepsLongCourseVisibleInRemainingSections() {
        val longCourse = createItem(0, "单片机控制与应用实验", Weekday.THURSDAY, 1, 4)
        val shortCourse = createItem(1, "离散数学Ⅱ", Weekday.THURSDAY, 1, 2)

        val slot = renderer.resolveSlotsForDay(null, Weekday.THURSDAY, listOf(longCourse, shortCourse)) { _, _ ->
            "离散数学Ⅱ"
        }.single()

        assertEquals("离散数学Ⅱ", slot.primary.course.courseName)
        assertEquals(1, slot.uncoveredSegments().size)
        assertEquals("单片机控制与应用实验", slot.uncoveredSegments().single().course.course.courseName)
        assertEquals(3, slot.uncoveredSegments().single().startSection)
        assertEquals(4, slot.uncoveredSegments().single().endSection)
    }

    @Test
    fun testLongCoverNeedsNoContinuation() {
        val longCourse = createItem(0, "单片机控制与应用实验", Weekday.THURSDAY, 1, 4)
        val shortCourse = createItem(1, "离散数学Ⅱ", Weekday.THURSDAY, 1, 2)

        val slot = renderer.resolveSlotsForDay(null, Weekday.THURSDAY, listOf(longCourse, shortCourse)).single()

        assertTrue(slot.uncoveredSegments().isEmpty())
    }

    @Test
    fun testSameCourseDifferentWeeksDoesNotFormConflict() {
        // 同一门课跨周次的不同 meeting（例如马克思主义基本原理第1-9周线下，第10周线上）
        val meetingCurrent = createItem(0, "马克思主义基本原理", Weekday.THURSDAY, 7, 8, isCurrentWeek = true, nextActiveWeek = 4)
        val meetingFuture = createItem(0, "马克思主义基本原理", Weekday.THURSDAY, 7, 8, isCurrentWeek = false, nextActiveWeek = 10)

        val slots = renderer.resolveSlotsForDay(null, Weekday.THURSDAY, listOf(meetingCurrent, meetingFuture))
        assertEquals(1, slots.size)
        // 绝不判定为冲突！
        assertFalse(slots[0].hasConflict)
        assertEquals("马克思主义基本原理", slots[0].primary.course.courseName)
        assertEquals(1, slots[0].allCourses.size)
    }

    @Test
    fun testDifferentCoursesDifferentWeeksDoesNotFormConflict() {
        // 交替开设的不同课程（例如前半学期数据挖掘，后半学期人工智能概论）
        val dataMining = createItem(0, "数据挖掘", Weekday.FRIDAY, 5, 6, isCurrentWeek = true, nextActiveWeek = 4)
        val aiIntro = createItem(1, "人工智能概论", Weekday.FRIDAY, 5, 6, isCurrentWeek = false, nextActiveWeek = 9)

        val slots = renderer.resolveSlotsForDay(null, Weekday.FRIDAY, listOf(dataMining, aiIntro))
        assertEquals(1, slots.size)
        // 当前周只上数据挖掘，人工智能被本周课程遮蔽，绝不判定为冲突！
        assertFalse(slots[0].hasConflict)
        assertEquals("数据挖掘", slots[0].primary.course.courseName)
        assertEquals(1, slots[0].allCourses.size)
    }

    @Test
    fun testNonCurrentCoursesInFreeSlotDoesNotFormConflict() {
        // 空闲节次（本周无课）展示未来周次课程
        val dl = createItem(0, "深度学习", Weekday.SATURDAY, 5, 8, isCurrentWeek = false, nextActiveWeek = 9)

        val slots = renderer.resolveSlotsForDay(null, Weekday.SATURDAY, listOf(dl))
        assertEquals(1, slots.size)
        assertFalse(slots[0].hasConflict)
        assertEquals("深度学习", slots[0].primary.course.courseName)
        assertEquals(1, slots[0].allCourses.size)
    }

    @Test
    fun testSameCourseDuplicateMeetingRecordsDeduplicated() {
        // 同一门课在同一时段存在多条 meeting 记录时去重，不判为冲突
        val dup1 = createItem(0, "离散数学Ⅱ", Weekday.MONDAY, 1, 2, isCurrentWeek = true)
        val dup2 = createItem(0, "离散数学Ⅱ", Weekday.MONDAY, 1, 2, isCurrentWeek = true)

        val slots = renderer.resolveSlotsForDay(null, Weekday.MONDAY, listOf(dup1, dup2))
        assertEquals(1, slots.size)
        assertFalse(slots[0].hasConflict)
        assertEquals("离散数学Ⅱ", slots[0].primary.course.courseName)
        assertEquals(1, slots[0].allCourses.size)
    }
}
