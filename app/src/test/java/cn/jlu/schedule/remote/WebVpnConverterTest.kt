package cn.jlu.schedule.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVpnConverterTest {

    /** 参考实现（webvpn-converter 默认密钥）计算的基准向量 */
    @Test
    fun encryptHostMatchesReferenceVectors() {
        assertEquals(
            "77726476706e69737468656265737421f9f24589693a6445300d8db9d6562d",
            WebVpnConverter.encryptHost("iedu.jlu.edu.cn")
        )
        assertEquals(
            "77726476706e69737468656265737421f3f652d22d3c7d1e7b0c9ce29b5b",
            WebVpnConverter.encryptHost("cas.jlu.edu.cn")
        )
    }

    @Test
    fun wrapsCampusUrlsWithEncryptedHost() {
        val wrapped = WebVpnConverter.wrap(
            "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/*default/index.do"
        )!!
        assertTrue(wrapped.startsWith(
            "https://vpn.jlu.edu.cn/https/77726476706e69737468656265737421f9f24589693a6445300d8db9d6562d/"
        ))
        assertTrue(wrapped.endsWith("/jwapp/sys/wdkb/*default/index.do"))
    }

    @Test
    fun nonCampusAndVpnUrlsAreNotWrapped() {
        assertNull(WebVpnConverter.wrap("https://www.baidu.com/index.html"))
        assertNull(WebVpnConverter.wrap("https://vpn.jlu.edu.cn/login"))
    }

    @Test
    fun nonDefaultPortBecomesProtocolTag() {
        val wrapped = WebVpnConverter.wrap("https://iedu.jlu.edu.cn:8443/api/test")!!
        assertTrue(wrapped.contains("/https-8443/"))
    }
}
