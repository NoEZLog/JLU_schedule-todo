package cn.jlu.schedule.data

import cn.jlu.schedule.domain.GpaCourse
import cn.jlu.schedule.domain.GpaGradeType
import cn.jlu.schedule.domain.ImportedGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GradeStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun emptySavedGradesDoNotReappearFromGpaCourses() {
        val gpaCourse = GpaCourse(
            id = "manual-1", name = "已删除成绩", gradeType = GpaGradeType.PERCENT,
            score = 90.0, credit = 2.0
        )
        GpaCourseStore.save(temp.root, listOf(gpaCourse))
        assertEquals(1, GradeStore.load(temp.root).size)

        GradeStore.save(temp.root, emptyList())
        assertTrue(GradeStore.load(temp.root).isEmpty())

        GradeStore.clear(temp.root)
        assertTrue(GradeStore.load(temp.root).isEmpty())
    }

    @Test
    fun corruptGradesFileIsPreservedForRecovery() {
        GpaCourseStore.save(temp.root, listOf(GpaCourse(
            id = "manual-1", name = "绩点课程", gradeType = GpaGradeType.PERCENT,
            score = 90.0, credit = 2.0
        )))
        val file = java.io.File(temp.root, "tools/grades.json")
        file.parentFile!!.mkdirs()
        file.writeText("{invalid json")

        assertTrue(GradeStore.load(temp.root).isEmpty())
        assertEquals("{invalid json", file.readText())
    }

    @Test
    fun syncKeepsManualEditsAndOlderGradesMissingFromPartialResponse() {
        val edited = ImportedGrade("CS101", "程序设计", 3.0, "95", "2025-2026-1", isCustom = true)
        val older = ImportedGrade("MA101", "高等数学", 4.0, "88", "2024-2025-1")
        val stale = ImportedGrade("EN101", "大学英语", 2.0, "70", "2025-2026-1")
        val fetched = listOf(
            ImportedGrade("CS101", "程序设计", 3.0, "90", "2025-2026-1"),
            ImportedGrade("EN101", "大学英语", 2.0, "86", "2025-2026-1")
        )

        val merged = GradeStore.mergeSyncedGrades(listOf(edited, older, stale), fetched)

        assertEquals(3, merged.size)
        assertTrue(edited in merged)
        assertTrue(older in merged)
        assertTrue(fetched[1] in merged)
        assertFalse(stale in merged)
        assertFalse(fetched[0] in merged)
    }

    @Test
    fun sameNameWithDifferentCourseCodesRemainsSeparate() {
        val existing = ImportedGrade("A100", "专题课", 2.0, "80", "2025-2026-1")
        val fetched = ImportedGrade("B200", "专题课", 2.0, "90", "2025-2026-1")

        assertEquals(listOf(existing, fetched), GradeStore.mergeSyncedGrades(listOf(existing), listOf(fetched)))
    }

    @Test
    fun manualGradeMarkerPersistsAcrossReload() {
        val manual = ImportedGrade("CS101", "程序设计", 3.0, "95", "2025-2026-1", isCustom = true)

        GradeStore.save(temp.root, listOf(manual))

        assertEquals(listOf(manual), GradeStore.load(temp.root))
    }

    @Test
    fun syncPreservesManualCoursesAndExclusionsAndFiltersUnscoredGrades() {
        val manual = GpaCourse(id = "manual-1", name = "自定义课程", gradeType = GpaGradeType.PERCENT, score = 96.0, credit = 1.0)
        val previous = GpaCourse(id = "jw-CS101-2024-2025-1", name = "重修课", gradeType = GpaGradeType.PERCENT, score = 65.0, credit = 3.0, included = false)
        GpaCourseStore.save(temp.root, listOf(manual, previous))

        GradeStore.syncToGpaCourses(temp.root, listOf(
            ImportedGrade(courseCode = "CS101", name = "重修课", credit = 3.0, scoreText = "65", semesterCode = "2024-2025-1"),
            ImportedGrade(courseCode = "CS101", name = "重修课", credit = 3.0, scoreText = "88", semesterCode = "2025-2026-1"),
            ImportedGrade(courseCode = "PASS1", name = "通过课程", credit = 1.0, scoreText = "通过", semesterCode = "2025-2026-1")
        ))

        val result = GpaCourseStore.load(temp.root)
        assertEquals(2, result.size)
        assertEquals(manual, result.first())
        assertEquals("jw-CS101-2025-2026-1", result.last().id)
        assertEquals(88.0, result.last().score, 0.0)
        assertFalse(result.last().included)
        assertTrue(result.none { it.name == "通过课程" })
    }
}
