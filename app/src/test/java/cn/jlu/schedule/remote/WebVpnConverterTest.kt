package cn.jlu.schedule.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVpnConverterTest {

    /** 基准向量：wrdvpnisawesome!（vpn.jlu.edu.cn 现役）与 wrdvpnisthebest!（WRD 默认） */
    @Test
    fun encryptHostMatchesReferenceVectors() {
        assertEquals(
            "77726476706e6973617765736f6d6521ef6ddf75d551823f9ed17c36cd9fa3",
            WebVpnConverter.encryptHost("iedu.jlu.edu.cn", WebVpnConverter.KEY_JLU, WebVpnConverter.KEY_JLU)
        )
        assertEquals(
            "77726476706e6973617765736f6d6521e569c82e91579b64d5d06d6d8092",
            WebVpnConverter.encryptHost("cas.jlu.edu.cn", WebVpnConverter.KEY_JLU, WebVpnConverter.KEY_JLU)
        )
        assertEquals(
            "77726476706e69737468656265737421f9f24589693a6445300d8db9d6562d",
            WebVpnConverter.encryptHost("iedu.jlu.edu.cn", WebVpnConverter.KEY_WRD, WebVpnConverter.KEY_WRD)
        )
    }

    @Test
    fun wrapsCampusUrlsWithEncryptedHost() {
        val wrapped = WebVpnConverter.wrap(
            "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/*default/index.do",
            WebVpnConverter.KEY_JLU
        )!!
        assertTrue(wrapped.startsWith(
            "https://vpn.jlu.edu.cn/https/77726476706e6973617765736f6d6521ef6ddf75d551823f9ed17c36cd9fa3/"
        ))
        assertTrue(wrapped.endsWith("/jwapp/sys/wdkb/*default/index.do"))
    }

    @Test
    fun nonCampusAndVpnUrlsAreNotWrapped() {
        assertNull(WebVpnConverter.wrap("https://www.baidu.com/index.html", WebVpnConverter.KEY_JLU))
        assertNull(WebVpnConverter.wrap("https://vpn.jlu.edu.cn/login", WebVpnConverter.KEY_JLU))
        assertNull(WebVpnConverter.wrap("https://cas.jlu.edu.cn/tpass/login", WebVpnConverter.KEY_JLU))
    }

    @Test
    fun nonDefaultPortBecomesProtocolTag() {
        val wrapped = WebVpnConverter.wrap("https://iedu.jlu.edu.cn:8443/api/test", WebVpnConverter.KEY_JLU)!!
        assertTrue(wrapped.contains("/https-8443/"))
    }

    @Test
    fun keyCandidatesDeduplicateAndPreferVerified() {
        val candidates = WebVpnConverter.keyCandidates(WebVpnConverter.KEY_WRD)
        assertEquals(listOf(WebVpnConverter.KEY_WRD, WebVpnConverter.KEY_JLU, WebVpnConverter.KEY_DIDIDA), candidates)
        assertEquals(
            listOf(WebVpnConverter.KEY_JLU, WebVpnConverter.KEY_DIDIDA, WebVpnConverter.KEY_WRD),
            WebVpnConverter.keyCandidates(null)
        )
    }
}
