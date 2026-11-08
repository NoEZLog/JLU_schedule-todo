package cn.jlu.schedule.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamScheduleParserTest {

    private val validPayload = """
        {"datas":{"wdksap":{"totalSize":2,"rows":[
          {
            "KCH":"CS202",
            "KCM":"操作系统",
            "KSSJMS":"2026-06-25 09:00 - 11:00",
            "JASMC":"前卫南区李四光楼201",
            "ZWH":"42",
            "KSMC":"期末考试"
          },
          {
            "KCH":"MATH101",
            "KCM":"高等数学B",
            "KSSJMS":"2026-06-28 14:00 - 16:00",
            "JASMC":"敬信教学楼302",
            "ZWH":"",
            "KSMC":"期末考试"
          },
          {
            "KCH":"EMPTY",
            "KCM":"",
            "KSSJMS":"2026-06-30 09:00 - 11:00",
            "JASMC":"无"
          }
        ]}}}
    """.trimIndent()

    @Test
    fun `recognizes exam payload correctly`() {
        assertTrue(ExamScheduleParser.isLikelyExamPayload(validPayload))
        assertTrue(ExamScheduleParser.isLikelyExamPayload("""{"datas":{"cxxsksap":{"rows":[]}},"code":"0"}"""))
        assertFalse(ExamScheduleParser.isLikelyExamPayload("""{"datas":{"xskcb":{"rows":[]}}}"""))
        assertFalse(ExamScheduleParser.isLikelyExamPayload("plain text"))
    }

    @Test
    fun `parses exam rows with timing, venue, and seat`() {
        val exams = ExamScheduleParser.parse(validPayload)
        assertEquals(2, exams.size)

        val os = exams[0]
        assertEquals("操作系统", os.courseName)
        assertEquals("CS202", os.courseCode)
        assertEquals("2026-06-25 09:00 - 11:00", os.examTimeText)
        assertEquals("前卫南区李四光楼201", os.location)
        assertEquals("42号", os.seatNumber)
        assertEquals("期末考试", os.examType)
        assertTrue(os.timestamp > 0L)
        assertFalse(os.isCustom)

        val math = exams[1]
        assertEquals("高等数学B", math.courseName)
        assertEquals("MATH101", math.courseCode)
        assertEquals("", math.seatNumber)
    }

    @Test
    fun `handles malformed and empty payload gracefully`() {
        assertTrue(ExamScheduleParser.parse("invalid json").isEmpty())
        assertTrue(ExamScheduleParser.parse("""{"datas":null}""").isEmpty())
        assertTrue(ExamScheduleParser.parse("""{"datas":{"wdksap":{"rows":[]}}}""").isEmpty())
    }
}
