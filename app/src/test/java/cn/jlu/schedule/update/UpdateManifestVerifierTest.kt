package cn.jlu.schedule.update

import cn.jlu.schedule.update.model.UpdateEnvelope
import cn.jlu.schedule.update.security.UpdateManifestVerifier
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdateManifestVerifierTest {

    private lateinit var keyPair: KeyPair
    private lateinit var pubKeyB64: String
    private val keyId = "test-key-1"

    @Before
    fun setup() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        keyPair = kpg.generateKeyPair()
        pubKeyB64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    }

    private fun createSignedEnvelope(
        rawPayloadJson: String,
        useKeyId: String = keyId,
        tamperSignature: Boolean = false,
        tamperPayload: Boolean = false
    ): String {
        val rawBytes = rawPayloadJson.toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(rawBytes)
        val sigBytes = signer.sign()

        if (tamperSignature) {
            sigBytes[sigBytes.size - 1] = (sigBytes[sigBytes.size - 1].toInt() xor 0xFF).toByte()
        }

        var payloadB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes)
        if (tamperPayload) {
            val tamperedBytes = rawPayloadJson.replace("2.2.0", "9.9.9").toByteArray(Charsets.UTF_8)
            payloadB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(tamperedBytes)
        }

        val envelope = UpdateEnvelope(
            schema = 1,
            keyId = useKeyId,
            payload = payloadB64Url,
            signature = Base64.getEncoder().encodeToString(sigBytes)
        )
        return Json.encodeToString(envelope)
    }

    private val validPayloadJson = """
        {
          "schema": 1,
          "packageName": "cn.jlu.schedule",
          "channel": "stable",
          "versionCode": 6,
          "versionName": "2.2.0",
          "minSdk": 26,
          "publishedAt": "2026-10-01T00:00:00Z",
          "notes": ["修复若干问题", "改进设置页"],
          "apk": {
            "size": 10240000,
            "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "signerSha256": "7ed7c719a6abb18d1eb014a18c173532fd4f11fb4bd350f003245549135dd6dc",
            "mirrors": [
              "https://jfyuhong.top/updates/android/versions/6/JLU_schedule.apk",
              "https://github.com/JFyuhong/JLU_schedule/releases/download/v2.2.0/JLU_schedule.apk"
            ]
          }
        }
    """.trimIndent()

    @Test
    fun testVerifyAndParseSuccess() {
        val envelopeJson = createSignedEnvelope(validPayloadJson)
        val result = UpdateManifestVerifier.verifyAndParse(
            envelopeJson = envelopeJson,
            trustedKeys = mapOf(keyId to pubKeyB64),
            currentVersionCode = 5,
            currentSdk = 33,
            checkVersionNewer = true
        )
        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertEquals(6, payload.versionCode)
        assertEquals("2.2.0", payload.versionName)
        assertEquals("cn.jlu.schedule", payload.packageName)
        assertEquals(2, payload.apk.mirrors.size)
    }

    @Test
    fun testTamperedPayloadFails() {
        val envelopeJson = createSignedEnvelope(validPayloadJson, tamperPayload = true)
        val result = UpdateManifestVerifier.verifyAndParse(
            envelopeJson = envelopeJson,
            trustedKeys = mapOf(keyId to pubKeyB64)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun testTamperedSignatureFails() {
        val envelopeJson = createSignedEnvelope(validPayloadJson, tamperSignature = true)
        val result = UpdateManifestVerifier.verifyAndParse(
            envelopeJson = envelopeJson,
            trustedKeys = mapOf(keyId to pubKeyB64)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun testUntrustedKeyIdFails() {
        val envelopeJson = createSignedEnvelope(validPayloadJson, useKeyId = "unknown-key-999")
        val result = UpdateManifestVerifier.verifyAndParse(
            envelopeJson = envelopeJson,
            trustedKeys = mapOf(keyId to pubKeyB64)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("未知或不受信任") == true)
    }

    @Test
    fun testPackageNameMismatchFails() {
        val badPackagePayload = validPayloadJson.replace("cn.jlu.schedule", "com.fake.app")
        val envelopeJson = createSignedEnvelope(badPackagePayload)
        val result = UpdateManifestVerifier.verifyAndParse(
            envelopeJson = envelopeJson,
            trustedKeys = mapOf(keyId to pubKeyB64)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("包名不匹配") == true)
    }

    @Test
    fun testOlderVersionCodeFailsWhenCheckingNewer() {
        val envelopeJson = createSignedEnvelope(validPayloadJson)
        val result = UpdateManifestVerifier.verifyAndParse(
            envelopeJson = envelopeJson,
            trustedKeys = mapOf(keyId to pubKeyB64),
            currentVersionCode = 10,
            checkVersionNewer = true
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("未高于当前版本") == true)
    }

    @Test
    fun testNonHttpsMirrorFails() {
        val httpMirrorPayload = validPayloadJson.replace("https://jfyuhong.top", "http://jfyuhong.top")
        val envelopeJson = createSignedEnvelope(httpMirrorPayload)
        val result = UpdateManifestVerifier.verifyAndParse(
            envelopeJson = envelopeJson,
            trustedKeys = mapOf(keyId to pubKeyB64)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTPS") == true)
    }

    @Test
    fun testInvalidSha256Fails() {
        val badShaPayload = validPayloadJson.replace("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "invalid-sha")
        val envelopeJson = createSignedEnvelope(badShaPayload)
        val result = UpdateManifestVerifier.verifyAndParse(
            envelopeJson = envelopeJson,
            trustedKeys = mapOf(keyId to pubKeyB64)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("SHA-256") == true)
    }

    @Test
    fun testNonOfficialApkSignerFailsEvenWithValidManifestSignature() {
        val otherSigner = "a".repeat(64)
        val payload = validPayloadJson.replace(
            "7ed7c719a6abb18d1eb014a18c173532fd4f11fb4bd350f003245549135dd6dc",
            otherSigner
        )
        val result = UpdateManifestVerifier.verifyAndParse(
            createSignedEnvelope(payload),
            trustedKeys = mapOf(keyId to pubKeyB64)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }
}
