package cn.jlu.schedule.remote

import cn.jlu.schedule.auth.CampusCookieJar
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScheduleRemoteSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient(): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(CampusCookieJar(File(tmp.root, "cookies.json")))
            .build()

    private fun endpointUrl(): String {
        // 路径需命中课表接口特征（cxxszhxqkb.do）
        return server.url("/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do").toString()
    }

    private val scheduleJson = """
        {"datas":{"rows":[{"KCM":"深度学习","YPSJDD":"第1-8周 星期一 第1-2节 前卫逸夫楼"}]}}
    """.trimIndent()

    @Test
    fun `fetch returns payload for schedule response`() = runBlocking {
        server.enqueue(MockResponse().setBody(scheduleJson))

        val result = ScheduleRemoteSource.fetch(newClient(), endpointUrl())

        assertTrue(result.isSuccess)
        val fetch = result.getOrThrow()
        assertTrue(fetch.json.contains("\"KCM\""))
        assertTrue(fetch.finalUrl.contains("cxxszhxqkb.do"))
    }

    @Test
    fun `fetch reports session expired when redirected to login form`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """<input type="hidden" id="lt" name="lt" value="LT-1-x"/><form id="loginForm"></form>"""
            )
        )

        val result = ScheduleRemoteSource.fetch(newClient(), endpointUrl())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ScheduleRemoteSource.FetchError.SessionExpired)
    }

    @Test
    fun `fetch reports non payload for wrong body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"msg":"error"}"""))

        val result = ScheduleRemoteSource.fetch(newClient(), endpointUrl())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ScheduleRemoteSource.FetchError.NotSchedulePayload)
    }

    @Test
    fun `endpoint learning validates and dedupes url`() {
        var stored: String? = null
        fun learn(current: String?, candidate: String): String? {
            var next = current
            JwEndpoints.learnScheduleEndpoint(current, candidate) { stored = it; next = it }
            return next
        }

        var current: String? = null
        // 非课表地址不学习
        current = learn(current, "https://iedu.jlu.edu.cn/index")
        assertEquals(null, stored)
        // 课表地址学习成功
        current = learn(current, "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do")
        assertEquals(
            "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do",
            stored
        )
        // 非 URL 文本不学习
        current = learn(current, "not-a-url")
        assertEquals(
            "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do",
            stored
        )
        // 重复学习同地址无变化
        current = learn(current, "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do")
        assertEquals(
            "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do",
            stored
        )
        // resolve：非法自学习地址返回 null
        assertEquals(null, JwEndpoints.resolveScheduleEndpoint("junk"))
        assertEquals(
            "https://vpn.jlu.edu.cn/proxy/x",
            JwEndpoints.resolveScheduleEndpoint("https://vpn.jlu.edu.cn/proxy/x")
        )
    }
}
