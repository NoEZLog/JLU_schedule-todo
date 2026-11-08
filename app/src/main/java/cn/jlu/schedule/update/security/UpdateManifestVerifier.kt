package cn.jlu.schedule.update.security

import cn.jlu.schedule.update.model.UpdateEnvelope
import cn.jlu.schedule.update.model.UpdatePayload
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object UpdateManifestVerifier {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    /**
     * Verifies the envelope's digital signature and parses the payload.
     *
     * @param envelopeJson The raw JSON string of the manifest envelope.
     * @param trustedKeys Map of keyId to Base64 SubjectPublicKeyInfo DER.
     * @param currentVersionCode The current app versionCode (checks versionCode > currentVersionCode if [checkVersionNewer] is true).
     * @param currentSdk The current Android SDK_INT.
     * @param checkVersionNewer Whether to enforce versionCode > currentVersionCode.
     */
    fun verifyAndParse(
        envelopeJson: String,
        trustedKeys: Map<String, String> = UpdateSecurityConfig.TRUSTED_PUBLIC_KEYS,
        currentVersionCode: Int = 0,
        currentSdk: Int = 30,
        checkVersionNewer: Boolean = false
    ): Result<UpdatePayload> = runCatching {
        val envelope = json.decodeFromString<UpdateEnvelope>(envelopeJson)

        if (envelope.schema != 1) {
            throw IllegalArgumentException("不支持的信封 schema: ${envelope.schema}")
        }

        val pubKeyB64 = trustedKeys[envelope.keyId]
            ?: throw SecurityException("未知或不受信任的签名密钥 ID: ${envelope.keyId}")

        // 1. Base64URL 解码 raw payload 原始字节
        val rawPayloadBytes = decodeBase64Url(envelope.payload)

        // 2. Base64 解码签名
        val signatureBytes = decodeBase64(envelope.signature)

        // 3. 使用 SHA256withRSA 验证签名
        val publicKey = parsePublicKey(pubKeyB64)
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(rawPayloadBytes)
        if (!verifier.verify(signatureBytes)) {
            throw SecurityException("清单签名验证失败：数据可能被篡改")
        }

        // 4. 解析业务 payload JSON
        val payloadStr = rawPayloadBytes.toString(Charsets.UTF_8)
        val payload = json.decodeFromString<UpdatePayload>(payloadStr)

        // 5. 业务字段一致性核验
        validatePayload(payload, currentVersionCode, currentSdk, checkVersionNewer)

        payload
    }

    private fun validatePayload(
        payload: UpdatePayload,
        currentVersionCode: Int,
        currentSdk: Int,
        checkVersionNewer: Boolean
    ) {
        if (payload.schema != 1) {
            throw IllegalArgumentException("不支持的清单 schema 版本: ${payload.schema}")
        }
        if (payload.packageName != UpdateSecurityConfig.EXPECTED_PACKAGE_NAME) {
            throw SecurityException("清单包名不匹配: 期望 ${UpdateSecurityConfig.EXPECTED_PACKAGE_NAME}, 实际 ${payload.packageName}")
        }
        if (payload.channel != "stable") {
            throw IllegalArgumentException("不支持的更新通道: ${payload.channel}")
        }
        if (checkVersionNewer && payload.versionCode <= currentVersionCode) {
            throw IllegalArgumentException("清单版本号 (${payload.versionCode}) 未高于当前版本 ($currentVersionCode)")
        }
        if (payload.minSdk > currentSdk) {
            throw IllegalArgumentException("系统版本要求 Android SDK ${payload.minSdk} 以上，当前为 $currentSdk")
        }
        if (payload.apk.size <= 0 || payload.apk.size > 300_000_000L) {
            throw IllegalArgumentException("异常的 APK 文件体积: ${payload.apk.size} bytes")
        }
        val sha256Regex = Regex("^[0-9a-f]{64}$")
        if (!payload.apk.sha256.lowercase().matches(sha256Regex)) {
            throw IllegalArgumentException("无效的 APK SHA-256 格式: ${payload.apk.sha256}")
        }
        if (!payload.apk.signerSha256.lowercase().matches(sha256Regex)) {
            throw IllegalArgumentException("无效的签名证书 SHA-256 格式: ${payload.apk.signerSha256}")
        }
        if (!payload.apk.signerSha256.equals(UpdateSecurityConfig.OFFICIAL_SIGNER_SHA256, ignoreCase = true)) {
            throw SecurityException("清单中的 APK 签名证书不是官方发布证书")
        }
        if (payload.apk.mirrors.isEmpty()) {
            throw IllegalArgumentException("下载镜像地址列表不可为空")
        }
        payload.apk.mirrors.forEach { mirror ->
            if (!mirror.startsWith("https://", ignoreCase = true)) {
                throw SecurityException("下载镜像必须使用安全传输协议 (HTTPS): $mirror")
            }
        }
        if (payload.notes.isEmpty()) {
            throw IllegalArgumentException("更新说明不可为空")
        }
        val totalNotesLen = payload.notes.sumOf { it.length }
        if (totalNotesLen > 5000) {
            throw IllegalArgumentException("更新说明文本长度超出安全限制")
        }
    }

    private fun parsePublicKey(base64Der: String): PublicKey {
        val keyBytes = Base64.getDecoder().decode(base64Der.replace("\n", "").replace("\r", "").trim())
        val keySpec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        return keyFactory.generatePublic(keySpec)
    }

    private fun decodeBase64Url(source: String): ByteArray {
        val clean = source.trim()
        return try {
            Base64.getUrlDecoder().decode(clean)
        } catch (_: Exception) {
            // 兼容可能补 padding 或未补 padding 的情况
            val padded = when (clean.length % 4) {
                2 -> "$clean=="
                3 -> "$clean="
                else -> clean
            }
            Base64.getUrlDecoder().decode(padded)
        }
    }

    private fun decodeBase64(source: String): ByteArray {
        val clean = source.trim()
        return try {
            Base64.getDecoder().decode(clean)
        } catch (_: Exception) {
            Base64.getUrlDecoder().decode(clean)
        }
    }
}
