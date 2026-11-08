package cn.jlu.schedule.update

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import cn.jlu.schedule.data.AppPreferences
import cn.jlu.schedule.update.model.ApkMetadata
import cn.jlu.schedule.update.model.UpdateCheckResult
import cn.jlu.schedule.update.model.UpdateEnvelope
import cn.jlu.schedule.update.model.UpdatePayload
import cn.jlu.schedule.update.repository.UpdateRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.TimeUnit

class UpdateRepositoryTest {

    private lateinit var server1: MockWebServer
    private lateinit var server2: MockWebServer
    private lateinit var keyPair: KeyPair
    private lateinit var pubKeyB64: String
    private val keyId = "test-key-repo"

    private lateinit var inMemoryPrefs: InMemorySharedPreferences
    private lateinit var testContext: Context

    @Before
    fun setup() {
        server1 = MockWebServer()
        server1.start()
        server2 = MockWebServer()
        server2.start()

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        keyPair = kpg.generateKeyPair()
        pubKeyB64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        inMemoryPrefs = InMemorySharedPreferences()
        testContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return inMemoryPrefs
            }
            override fun getPackageName(): String {
                return "cn.jlu.schedule"
            }
        }
    }

    @After
    fun tearDown() {
        server1.shutdown()
        server2.shutdown()
    }

    private fun createSignedEnvelopeJson(
        versionCode: Int = 6,
        versionName: String = "2.2.0",
        sha256: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    ): String {
        val payload = UpdatePayload(
            schema = 1,
            packageName = "cn.jlu.schedule",
            channel = "stable",
            versionCode = versionCode,
            versionName = versionName,
            minSdk = 26,
            publishedAt = "2026-10-01T00:00:00Z",
            notes = listOf("修复已知问题", "性能优化"),
            apk = ApkMetadata(
                size = 10485760L,
                sha256 = sha256,
                signerSha256 = "7ed7c719a6abb18d1eb014a18c173532fd4f11fb4bd350f003245549135dd6dc",
                mirrors = listOf(
                    "https://jfyuhong.top/updates/android/versions/$versionCode/JLU_schedule.apk",
                    "https://github.com/JFyuhong/JLU_schedule/releases/download/v$versionName/JLU_schedule.apk"
                )
            )
        )
        val payloadJson = Json.encodeToString(payload)
        val rawBytes = payloadJson.toByteArray(Charsets.UTF_8)

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(rawBytes)
        val sig = signer.sign()

        val envelope = UpdateEnvelope(
            schema = 1,
            keyId = keyId,
            payload = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes),
            signature = Base64.getEncoder().encodeToString(sig)
        )
        return Json.encodeToString(envelope)
    }

    @Test
    fun testCheckUpdateDualMirrorPicksFastest() = runBlocking {
        val envelopeJson = createSignedEnvelopeJson(versionCode = 6)
        // Server 1 延迟 100ms
        server1.enqueue(
            MockResponse()
                .setBody(envelopeJson)
                .setBodyDelay(100, TimeUnit.MILLISECONDS)
        )
        // Server 2 立即返回
        server2.enqueue(
            MockResponse()
                .setBody(envelopeJson)
        )

        val repo = UpdateRepository(
            trustedKeys = mapOf(keyId to pubKeyB64),
            appVersionCodeOverride = 5,
            isDebugOverride = false
        )

        val url1 = server1.url("/updates/android/stable.json").toString()
        val url2 = server2.url("/updates/android/stable.json").toString()

        val result = repo.checkUpdate(testContext, force = true, endpoints = listOf(url1, url2))
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val available = result as UpdateCheckResult.UpdateAvailable
        assertEquals(6, available.payload.versionCode)
        assertEquals(url2, available.preferredMirror)
    }

    @Test
    fun testCheckUpdateDualMirrorMismatchDetected() = runBlocking {
        val env1 = createSignedEnvelopeJson(
            versionCode = 6,
            sha256 = "1111111111111111111111111111111111111111111111111111111111111111"
        )
        val env2 = createSignedEnvelopeJson(
            versionCode = 6,
            sha256 = "2222222222222222222222222222222222222222222222222222222222222222"
        )

        server1.enqueue(MockResponse().setBody(env1))
        server2.enqueue(MockResponse().setBody(env2))

        val repo = UpdateRepository(
            trustedKeys = mapOf(keyId to pubKeyB64),
            appVersionCodeOverride = 5,
            isDebugOverride = false
        )

        val url1 = server1.url("/updates/android/stable.json").toString()
        val url2 = server2.url("/updates/android/stable.json").toString()

        val result = repo.checkUpdate(testContext, force = true, endpoints = listOf(url1, url2))
        assertTrue(result is UpdateCheckResult.MirrorMismatch)
    }

    @Test
    fun testCheckUpdateFallbackWhenDomesticFails() = runBlocking {
        server1.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))
        server2.enqueue(MockResponse().setBody(createSignedEnvelopeJson(versionCode = 6)))

        val repo = UpdateRepository(
            trustedKeys = mapOf(keyId to pubKeyB64),
            appVersionCodeOverride = 5,
            isDebugOverride = false
        )

        val url1 = server1.url("/updates/android/stable.json").toString()
        val url2 = server2.url("/updates/android/stable.json").toString()

        val result = repo.checkUpdate(testContext, force = true, endpoints = listOf(url1, url2))
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val available = result as UpdateCheckResult.UpdateAvailable
        assertEquals(url2, available.preferredMirror)
    }

    @Test
    fun testCheckUpdateNoUpdateWhenCurrentIsSameOrHigher() = runBlocking {
        server1.enqueue(MockResponse().setBody(createSignedEnvelopeJson(versionCode = 5)))

        val repo = UpdateRepository(
            trustedKeys = mapOf(keyId to pubKeyB64),
            appVersionCodeOverride = 5,
            isDebugOverride = false
        )

        val url1 = server1.url("/updates/android/stable.json").toString()
        val result = repo.checkUpdate(testContext, force = true, endpoints = listOf(url1))
        assertTrue(result is UpdateCheckResult.NoUpdate)
        assertEquals("当前已是最新版本", (result as UpdateCheckResult.NoUpdate).reason)
    }

    @Test
    fun testSameVersionWithOtherMirrorUnavailableIsStillLatest() = runBlocking {
        server1.enqueue(MockResponse().setResponseCode(503))
        server2.enqueue(MockResponse().setBody(createSignedEnvelopeJson(versionCode = 6)))

        val repo = UpdateRepository(
            trustedKeys = mapOf(keyId to pubKeyB64),
            appVersionCodeOverride = 6,
            isDebugOverride = false
        )

        val urls = listOf(
            server1.url("/updates/android/stable.json").toString(),
            server2.url("/updates/android/stable.json").toString()
        )
        val result = repo.checkUpdate(testContext, force = true, endpoints = urls)
        assertTrue(result is UpdateCheckResult.NoUpdate)
        assertEquals("当前已是最新版本", (result as UpdateCheckResult.NoUpdate).reason)
    }

    @Test
    fun testCheckAutoUpdateThrottledWithin24Hours() = runBlocking {
        // 设置上次检查时间为 1 小时前
        AppPreferences.setLastUpdateCheckTime(testContext, System.currentTimeMillis() - 3600 * 1000L)

        val repo = UpdateRepository(
            trustedKeys = mapOf(keyId to pubKeyB64),
            appVersionCodeOverride = 5,
            isDebugOverride = false
        )

        val url1 = server1.url("/updates/android/stable.json").toString()
        val result = repo.checkAutoUpdate(testContext, endpoints = listOf(url1))

        assertTrue(result is UpdateCheckResult.NoUpdate)
        assertEquals("距离上次检查更新不足24小时", (result as UpdateCheckResult.NoUpdate).reason)
        assertEquals(0, server1.requestCount)
    }

    @Test
    fun testCheckUpdateIgnoredVersionFilteredUnlessForced() = runBlocking {
        AppPreferences.setIgnoredUpdateVersion(testContext, 6)

        val repo = UpdateRepository(
            trustedKeys = mapOf(keyId to pubKeyB64),
            appVersionCodeOverride = 5,
            isDebugOverride = false
        )

        val url1 = server1.url("/updates/android/stable.json").toString()

        // 1. 非强制检查，命中忽略版本 -> NoUpdate
        server1.enqueue(MockResponse().setBody(createSignedEnvelopeJson(versionCode = 6)))
        val resultIgnored = repo.checkUpdate(testContext, force = false, endpoints = listOf(url1), allowInDebug = true)
        assertTrue(resultIgnored is UpdateCheckResult.NoUpdate)
        assertTrue((resultIgnored as UpdateCheckResult.NoUpdate).reason.contains("已忽略"))

        // 2. 强制检查（用户手动检查）-> 绕过忽略标记
        server1.enqueue(MockResponse().setBody(createSignedEnvelopeJson(versionCode = 6)))
        val resultForced = repo.checkUpdate(testContext, force = true, endpoints = listOf(url1))
        assertTrue(resultForced is UpdateCheckResult.UpdateAvailable)
    }

    @Test
    fun testCheckUpdateDebugBuildFilteredEvenWhenForcedUnlessExplicitlyAllowed() = runBlocking {
        val repo = UpdateRepository(
            trustedKeys = mapOf(keyId to pubKeyB64),
            appVersionCodeOverride = 5,
            isDebugOverride = true
        )

        val url1 = server1.url("/updates/android/stable.json").toString()

        // Debug 下且未 force 未 allowInDebug -> NoUpdate 无请求
        val result = repo.checkUpdate(testContext, force = true, endpoints = listOf(url1), allowInDebug = false)
        assertTrue(result is UpdateCheckResult.NoUpdate)
        assertEquals(0, server1.requestCount)

        // allowInDebug = true -> 发起检查
        server1.enqueue(MockResponse().setBody(createSignedEnvelopeJson(versionCode = 6)))
        val resultAllowed = repo.checkUpdate(testContext, force = false, endpoints = listOf(url1), allowInDebug = true)
        assertTrue(resultAllowed is UpdateCheckResult.UpdateAvailable)
    }

    @Test
    fun testFailedAutoCheckDoesNotThrottleRetry() = runBlocking {
        server1.enqueue(MockResponse().setResponseCode(503))
        server1.enqueue(MockResponse().setBody(createSignedEnvelopeJson()))
        val repo = UpdateRepository(
            trustedKeys = mapOf(keyId to pubKeyB64),
            appVersionCodeOverride = 5,
            isDebugOverride = false
        )
        val url = server1.url("/updates/android/stable.json").toString()
        assertTrue(repo.checkAutoUpdate(testContext, listOf(url)) is UpdateCheckResult.Error)
        assertEquals(0L, AppPreferences.getLastUpdateCheckTime(testContext))
        assertTrue(repo.checkAutoUpdate(testContext, listOf(url)) is UpdateCheckResult.UpdateAvailable)
    }
}

class InMemorySharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = EditorImpl()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class EditorImpl : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String?, value: String?) = apply { temp[key ?: ""] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { temp[key ?: ""] = values }
        override fun putInt(key: String?, value: Int) = apply { temp[key ?: ""] = value }
        override fun putLong(key: String?, value: Long) = apply { temp[key ?: ""] = value }
        override fun putFloat(key: String?, value: Float) = apply { temp[key ?: ""] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { temp[key ?: ""] = value }
        override fun remove(key: String?) = apply { temp[key ?: ""] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) map.clear()
            temp.forEach { (k, v) ->
                if (v == null) map.remove(k) else map[k] = v
            }
        }
    }
}
