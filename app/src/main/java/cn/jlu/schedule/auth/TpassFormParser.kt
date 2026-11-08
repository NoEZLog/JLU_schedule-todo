package cn.jlu.schedule.auth

import java.util.regex.Pattern

/** 吉大统一认证（TPASS）相关常量，均来自对 cas.jlu.edu.cn 真实登录页的抓取分析 */
object TpassConfig {
    const val CAS_LOGIN_URL = "https://cas.jlu.edu.cn/tpass/login"
    const val CAS_HOST = "cas.jlu.edu.cn"
    const val IEDU_HOST = "iedu.jlu.edu.cn"

    /** 智慧教育平台门户（登录 service 参数指向它，登录成功后即建立 iedu 会话） */
    const val IEDU_PORTAL_URL = "https://iedu.jlu.edu.cn/jwapp/sys/emaphome/portal/index.do"
    const val VPN_HOST = "vpn.jlu.edu.cn"
    const val VPN_LOGIN_URL = "https://vpn.jlu.edu.cn/login"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}

/** TPASS 登录页表单字段 */
data class TpassLoginForm(
    val lt: String,
    val execution: String?,
    val eventId: String?
)

/**
 * 解析 TPASS 登录页 HTML。字段名与提交算法均来自 login10.js：
 * rsa = strEnc(username + password + lt, "1", "2", "3")，ul/pl 为用户名/密码长度，sl 固定 0。
 */
object TpassFormParser {

    private val LT_PATTERN = Pattern.compile("id=\"lt\"[^>]*value=\"([^\"]*)\"")
    private val EXECUTION_PATTERN = Pattern.compile("name=\"execution\"[^>]*value=\"([^\"]*)\"")

    /** 服务端渲染错误：&lt;div id="errormsg" ...&gt;提示&lt;/div&gt; */
    private val ERROR_PATTERN = Pattern.compile("id=\"errormsg\"[^>]*>\\s*([^<]{2,80})<")

    /** 页面脚本渲染错误：$("#errormsg").text("提示") */
    private val ERROR_JS_PATTERN = Pattern.compile("\\$\\(\"#errormsg\"\\)\\.text\\(\"([^\"]{2,80})\"\\)")

    /** 登录页含表单返回字段；非登录页（比如已登录后的空表单）返回 null */
    fun parse(html: String): TpassLoginForm? {
        val lt = matcherGroup(LT_PATTERN, html) ?: return null
        return TpassLoginForm(
            lt = lt,
            execution = matcherGroup(EXECUTION_PATTERN, html),
            eventId = "submit"
        )
    }

    /** 从失败后重新渲染的登录页里提取错误提示（如"账号或密码错误"） */
    fun extractError(html: String): String? =
        matcherGroup(ERROR_PATTERN, html) ?: matcherGroup(ERROR_JS_PATTERN, html)

    private fun matcherGroup(pattern: Pattern, html: String): String? {
        val matcher = pattern.matcher(html)
        return if (matcher.find()) matcher.group(1) else null
    }
}
