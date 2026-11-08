package cn.jlu.schedule.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class JwApiClientSessionTest {
    @Test
    fun staleCookieRedirectedToCasIsExpired() {
        assertEquals(
            JwApiClient.SessionStatus.EXPIRED,
            JwApiClient.classifySessionResponse("cas.jlu.edu.cn", 200, "<html>login</html>")
        )
    }

    @Test
    fun loginFormOnIeduIsExpired() {
        assertEquals(
            JwApiClient.SessionStatus.EXPIRED,
            JwApiClient.classifySessionResponse("iedu.jlu.edu.cn", 200, "<form id=\"loginForm\">")
        )
    }

    @Test
    fun serverFailureIsUnavailableAndBusinessPageIsValid() {
        assertEquals(
            JwApiClient.SessionStatus.UNAVAILABLE,
            JwApiClient.classifySessionResponse("iedu.jlu.edu.cn", 503, "")
        )
        assertEquals(
            JwApiClient.SessionStatus.VALID,
            JwApiClient.classifySessionResponse("iedu.jlu.edu.cn", 200, "<html>课程表</html>")
        )
    }
}
