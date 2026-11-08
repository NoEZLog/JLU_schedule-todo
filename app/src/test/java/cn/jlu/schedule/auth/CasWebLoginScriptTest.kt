package cn.jlu.schedule.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CasWebLoginScriptTest {
    @Test
    fun `escapes credentials before inserting them into JavaScript`() {
        val script = CasWebLoginScript.build(
            JluCredentials("student\";alert(1)//", "pass\\word\nnext\u2028line")
        )

        assertTrue(script.contains("user.value = \"student\\\";alert(1)//\";"))
        assertTrue(script.contains("pass.value = \"pass\\\\word\\nnext\\u2028line\";"))
        assertFalse(script.contains("pass\\word\nnext"))
        assertTrue(script.contains("location.hostname !== 'cas.jlu.edu.cn'"))
        assertTrue(script.contains("location.pathname !== '/tpass/login'"))
    }
}
