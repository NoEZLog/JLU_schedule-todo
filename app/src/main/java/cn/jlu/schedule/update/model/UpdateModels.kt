package cn.jlu.schedule.update.model

import kotlinx.serialization.Serializable

@Serializable
data class UpdateEnvelope(
    val schema: Int = 1,
    val keyId: String,
    val payload: String,
    val signature: String
)

@Serializable
data class UpdatePayload(
    val schema: Int,
    val packageName: String,
    val channel: String,
    val versionCode: Int,
    val versionName: String,
    val minSdk: Int = 26,
    val publishedAt: String,
    val notes: List<String>,
    val apk: ApkMetadata
)

@Serializable
data class ApkMetadata(
    val size: Long,
    val sha256: String,
    val signerSha256: String,
    val mirrors: List<String>
)

sealed class UpdateCheckResult {
    data class NoUpdate(val reason: String = "") : UpdateCheckResult()
    data class UpdateAvailable(
        val payload: UpdatePayload,
        val preferredMirror: String? = null
    ) : UpdateCheckResult()
    data class MirrorMismatch(val message: String) : UpdateCheckResult()
    data class Error(val message: String, val cause: Throwable? = null) : UpdateCheckResult()
}

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBps: Long,
    val isComplete: Boolean = false
) {
    val percent: Int
        get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
}
