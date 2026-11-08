package cn.jlu.schedule.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对拍向量由 cas.jlu.edu.cn 真实下发的 des.js 在 Node 中运行生成，
 * 保证 Kotlin 移植与网页端登录脚本逐字节一致。
 */
class TpassDesTest {

    @Test
    fun `encrypt matches des_js output for standard login payload`() {
        val data = "2024123456Passw0rd!LT-865101-y49RBwcxZu1VlM9Rsze5pvWLarXNIF-tpass"
        val expected =
            "912F97589B78631BC1BB5938DF9F21900DF67FD9B501B95CAEDCC497394FAC6D" +
                "C4DBB6D2B426B428DB95E9A1C677DDAD5B393792498927EFC864AA1C4D2EBCC" +
                "2445F730869FA3F2BA1AF95C623E79F70B51C3B80A2EB86E7EC99DD849BBC45" +
                "4A76299EBC9E481B641493A97F9F30ADA27D19BD21C0685F7C7FC545B38D6F1" +
                "738B2DA8880EA270330"
        assertEquals(expected, TpassDes.encrypt(data, "1", "2", "3"))
    }

    @Test
    fun `encrypt matches des_js output for short payload`() {
        assertEquals(
            "4860B1E277464DB150B87714D462E933B241A05DC4798236344027267C6430B" +
                "7DF423CAA640D9D70",
            TpassDes.encrypt("abc123456LT-1-abc", "1", "2", "3")
        )
    }

    @Test
    fun `encrypt matches des_js output with chinese utf16 code units`() {
        assertEquals(
            "806DEB9164AA26FA846D835E8E77757EF88433E427DA81E323A4114DE41FC06" +
                "F4234D41FC8C98508",
            TpassDes.encrypt("zhongwen测试密码!@#LT-x", "1", "2", "3")
        )
    }

    @Test
    fun `encrypt matches des_js output for padded single block`() {
        assertEquals(
            "7C3D5DB065A6C3558058B4E9A11C14A4",
            TpassDes.encrypt("LT-empty", "1", "2", "3")
        )
    }

    @Test
    fun `decrypt reverses encrypt`() {
        val samples = listOf(
            "2024123456" + "p@ss词组test" + "LT-xxxx-others",
            "a1",
            "abcdef",
            "混合混合ABC123!@#LT-0000-zzz"
        )
        samples.forEach { sample ->
            val encrypted = TpassDes.encrypt(sample, "1", "2", "3")
            assertEquals(sample, TpassDes.decrypt(encrypted, "1", "2", "3"))
        }
    }

    @Test
    fun `encrypted output is uppercase hex with 16 chars per block`() {
        val encrypted = TpassDes.encrypt("some-student-id-2024-password-01", "1", "2", "3")
        assertTrue(encrypted.matches(Regex("[0-9A-F]+")))
        assertEquals(0, encrypted.length % 16)
    }
}
