package cn.jlu.schedule.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeTranscriptParserTest {

    private val sampleMultiSemesterPayload = """
        {
          "code": "0",
          "msg": "成功",
          "datas": {
            "xscjcx": {
              "totalSize": 4,
              "pageNumber": 1,
              "pageSize": 1000,
              "rows": [
                {
                  "XNXQDM": "2024-2025-2",
                  "KCH": "CS101",
                  "KCM": "软件工程导论",
                  "XF": 3.0,
                  "ZCJ": "92",
                  "XSZCJMC": "92",
                  "DJCJMC": "优秀"
                },
                {
                  "XNXQDM": "2024-2025-2",
                  "KCH": "PE102",
                  "KCM": "大学体育4",
                  "XF": "1.0",
                  "ZCJ": "",
                  "XSZCJMC": "良好",
                  "DJCJMC": "良好"
                },
                {
                  "XNXQDM": "2023-2024-1",
                  "KCH": "MATH001",
                  "KCM": "高等数学A",
                  "XF": 5.5,
                  "ZCJ": "88.5"
                },
                {
                  "XNXQDM": "2023-2024-1",
                  "KCH": "ENG002",
                  "KCM": "大学英语2",
                  "XF": 2.0,
                  "ZCJ": null,
                  "XSZCJMC": null,
                  "DJCJMC": "通过"
                },
                {
                  "XNXQDM": "2023-2024-1",
                  "KCH": "INVALID01",
                  "KCM": "零学分测试课程",
                  "XF": 0.0,
                  "ZCJ": "100"
                },
                {
                  "XNXQDM": "2023-2024-1",
                  "KCH": "INVALID02",
                  "KCM": "无成绩课程",
                  "XF": 2.0,
                  "ZCJ": null,
                  "XSZCJMC": "",
                  "DJCJMC": null
                }
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun `isLikelyGradePayload identifies valid and invalid payloads`() {
        assertTrue(GradeTranscriptParser.isLikelyGradePayload(sampleMultiSemesterPayload))
        assertFalse(GradeTranscriptParser.isLikelyGradePayload("""{"datas":{"wdksap":{"rows":[]}}}"""))
        assertFalse(GradeTranscriptParser.isLikelyGradePayload("plain text"))
    }

    @Test
    fun `parses multi-semester records and extracts credit and score`() {
        val grades = GradeTranscriptParser.parse(sampleMultiSemesterPayload)
        // 4 valid courses (CS101, PE102, MATH001, ENG002), 2 invalid filtered out (0 credits, no score)
        assertEquals(4, grades.size)

        val cs = grades[0]
        assertEquals("CS101", cs.courseCode)
        assertEquals("软件工程导论", cs.name)
        assertEquals(3.0, cs.credit, 0.001)
        assertEquals("92", cs.scoreText)
        assertEquals("2024-2025-2", cs.semesterCode)

        // PE102: ZCJ is blank, falls back to XSZCJMC "良好"
        val pe = grades[1]
        assertEquals("PE102", pe.courseCode)
        assertEquals("大学体育4", pe.name)
        assertEquals(1.0, pe.credit, 0.001)
        assertEquals("良好", pe.scoreText)
        assertEquals("2024-2025-2", pe.semesterCode)

        // MATH001: 2023-2024-1, 88.5
        val math = grades[2]
        assertEquals("MATH001", math.courseCode)
        assertEquals("高等数学A", math.name)
        assertEquals(5.5, math.credit, 0.001)
        assertEquals("88.5", math.scoreText)
        assertEquals("2023-2024-1", math.semesterCode)

        // ENG002: ZCJ and XSZCJMC are null, falls back to DJCJMC "通过"
        val eng = grades[3]
        assertEquals("ENG002", eng.courseCode)
        assertEquals("大学英语2", eng.name)
        assertEquals(2.0, eng.credit, 0.001)
        assertEquals("通过", eng.scoreText)
        assertEquals("2023-2024-1", eng.semesterCode)
    }

    @Test
    fun `returns empty list on malformed payload or missing rows`() {
        assertEquals(emptyList<Any>(), GradeTranscriptParser.parse(""))
        assertEquals(emptyList<Any>(), GradeTranscriptParser.parse("{ invalid json }"))
        assertEquals(emptyList<Any>(), GradeTranscriptParser.parse("""{"datas":{"other":{}}}"""))
    }
}
