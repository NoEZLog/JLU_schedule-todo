package cn.jlu.schedule.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TpassFormParserTest {

    @Test
    fun `parses lt and execution from real login page structure`() {
        val html = """
            <form id="loginForm" action="/tpass/login?service=https%3A%2F%2Fiedu.jlu.edu.cn%2F" method="post">
            <input type="text" id="un" placeholder="用户名"/>
            <input type="password" id="pd" placeholder="密码"/>
            <input type="hidden" id="rsa" name="rsa"/>
            <input type="hidden" id="ul" name="ul"/>
            <input type="hidden" id="pl" name="pl"/>
            <input type="hidden" id="sl" name="sl"/>
            <input type="hidden" id="lt" name="lt" value="LT-865101-y49RBwcxZu1VlM9Rsze5pvWLarXNIF-tpass" />
            <input type="hidden" name="execution" value="e1s1" />
            <input type="hidden" name="_eventId" value="submit" />
            </form>
        """.trimIndent()

        val form = TpassFormParser.parse(html)
        assertNotNull(form)
        assertEquals("LT-865101-y49RBwcxZu1VlM9Rsze5pvWLarXNIF-tpass", form!!.lt)
        assertEquals("e1s1", form.execution)
        assertEquals("submit", form.eventId)
    }

    @Test
    fun `returns null for non login page`() {
        assertNull(TpassFormParser.parse("<html><body>portal index</body></html>"))
    }

    @Test
    fun `extracts server rendered error message`() {
        val html = """<div id="errormsg" class="login_error">账号或密码错误，请重新输入。</div>"""
        assertEquals("账号或密码错误，请重新输入。", TpassFormParser.extractError(html))
    }

    @Test
    fun `extracts script rendered error message`() {
        val html = """<script>$("#errormsg").text("您的密码已过期，请修改。").show();</script>"""
        assertEquals("您的密码已过期，请修改。", TpassFormParser.extractError(html))
    }

    @Test
    fun `no error returns null`() {
        assertNull(TpassFormParser.extractError("<html><body>ok</body></html>"))
    }
}
