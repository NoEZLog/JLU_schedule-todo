package cn.jlu.schedule.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PyfaTranscriptParserTest {

    @Test
    fun `isLikelyPyfaPayload detects pyfa payloads accurately`() {
        assertTrue(PyfaTranscriptParser.isLikelyPyfaPayload("""{"datas":{"xsgzywcqk":{"rows":[]}}}"""))
        assertTrue(PyfaTranscriptParser.isLikelyPyfaPayload("""{"FAJDMC":"专业核心课","YQJDXF":40}"""))
        assertTrue(PyfaTranscriptParser.isLikelyPyfaPayload("""{"type":"pyfa_dom_extract","modules":[]}"""))
        assertTrue(PyfaTranscriptParser.isLikelyPyfaPayload("""{"datas":{"xywcqk":{"rows":[]},"YQXF":160}}"""))
        assertTrue(PyfaTranscriptParser.isLikelyPyfaPayload("""{"datas":{"grpyfacx":{"rows":[{"PYFAMC":"主修培养方案","ZSYQXF":164,"YWCXF":77.5}]}}}"""))

        assertFalse(PyfaTranscriptParser.isLikelyPyfaPayload(""))
        assertFalse(PyfaTranscriptParser.isLikelyPyfaPayload("""{"datas":{"xscjcx":{"rows":[]}}}"""))
        assertFalse(PyfaTranscriptParser.isLikelyPyfaPayload("""{"other":"data"}"""))
    }

    @Test
    fun `parses flat emap rows payload successfully`() {
        val payload = """
        {
          "code": "0",
          "msg": "成功",
          "datas": {
            "xsgzywcqk": {
              "totalSize": 5,
              "rows": [
                {
                  "FAJDMC": "毕业总要求",
                  "YQJDXF": 160.0,
                  "YHDXF": 102.5
                },
                {
                  "FAJDMC": "学科基础与专业必修",
                  "YQJDXF": 75.0,
                  "YHDXF": 65.0
                },
                {
                  "FAJDMC": "专业选修课",
                  "YQJDXF": 35.0,
                  "YHDXF": 20.5
                },
                {
                  "FAJDMC": "通识教育选修/核心课",
                  "YQJDXF": 16.0,
                  "YHDXF": 12.0
                },
                {
                  "FAJDMC": "实践教学与毕业设计",
                  "YQJDXF": 34.0,
                  "YHDXF": 5.0
                }
              ]
            }
          }
        }
        """.trimIndent()

        val plan = PyfaTranscriptParser.parse(payload)
        assertNotNull(plan)
        assertEquals(160.0, plan!!.totalRequiredCredits, 0.001)
        assertEquals(102.5, plan.totalEarnedCredits, 0.001)
        assertEquals(4, plan.requirements.size)

        assertEquals("学科基础与专业必修", plan.requirements[0].categoryName)
        assertEquals(75.0, plan.requirements[0].requiredCredits, 0.001)
        assertEquals(65.0, plan.requirements[0].earnedCredits, 0.001)

        assertEquals("专业选修课", plan.requirements[1].categoryName)
        assertEquals(35.0, plan.requirements[1].requiredCredits, 0.001)
        assertEquals(20.5, plan.requirements[1].earnedCredits, 0.001)
    }

    @Test
    fun `parses current academic completion endpoint`() {
        val payload = """
            {"datas":{"grpyfacx":{"rows":[
              {"PYFAMC":"主修培养方案","ZSYQXF":164.0,"YWCXF":77.5}
            ]}}}
        """.trimIndent()

        val plan = PyfaTranscriptParser.parse(payload)
        assertNotNull(plan)
        assertEquals(164.0, plan!!.totalRequiredCredits, 0.001)
        assertEquals(77.5, plan.totalEarnedCredits, 0.001)
        assertEquals("主修培养方案", plan.requirements.single().categoryName)
    }

    @Test
    fun `parses hierarchical tree payload successfully`() {
        val treePayload = """
        {
          "datas": {
            "pyfaTree": {
              "name": "毕业要求",
              "requiredCredits": 155.0,
              "earnedCredits": 88.0,
              "children": [
                {
                  "name": "公共基础课",
                  "requiredCredits": 42.0,
                  "earnedCredits": 40.0
                },
                {
                  "name": "专业核心课",
                  "requiredCredits": 50.0,
                  "earnedCredits": 30.0
                },
                {
                  "name": "集中实践",
                  "requiredCredits": 30.0,
                  "earnedCredits": 18.0
                }
              ]
            }
          }
        }
        """.trimIndent()

        val plan = PyfaTranscriptParser.parse(treePayload)
        assertNotNull(plan)
        assertEquals(155.0, plan!!.totalRequiredCredits, 0.001)
        assertEquals(88.0, plan.totalEarnedCredits, 0.001)
        assertEquals(3, plan.requirements.size)

        assertEquals("公共基础课", plan.requirements[0].categoryName)
        assertEquals(42.0, plan.requirements[0].requiredCredits, 0.001)
        assertEquals(40.0, plan.requirements[0].earnedCredits, 0.001)
    }

    @Test
    fun `parses DOM extract format accurately`() {
        val domExtract = """
        {
          "type": "pyfa_dom_extract",
          "totalRequired": 160.0,
          "totalEarned": 92.5,
          "modules": [
            {
              "name": "专业必修课",
              "required": 68.0,
              "earned": 52.0
            },
            {
              "name": "专业选修课",
              "required": 24.0,
              "earned": 14.5
            },
            {
              "name": "通识课",
              "required": 18.0,
              "earned": 16.0
            }
          ]
        }
        """.trimIndent()

        val plan = PyfaTranscriptParser.parse(domExtract)
        assertNotNull(plan)
        assertEquals(160.0, plan!!.totalRequiredCredits, 0.001)
        assertEquals(92.5, plan.totalEarnedCredits, 0.001)
        assertEquals(3, plan.requirements.size)
        assertEquals("专业必修课", plan.requirements[0].categoryName)
        assertEquals(68.0, plan.requirements[0].requiredCredits, 0.001)
        assertEquals(52.0, plan.requirements[0].earnedCredits, 0.001)
    }

    @Test
    fun `returns null on malformed or empty payloads`() {
        assertNull(PyfaTranscriptParser.parse(""))
        assertNull(PyfaTranscriptParser.parse("{ invalid json }"))
        assertNull(PyfaTranscriptParser.parse("""{"datas":{"other":{}}}"""))
    }
}
