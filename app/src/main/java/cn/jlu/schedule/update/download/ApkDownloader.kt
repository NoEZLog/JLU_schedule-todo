package cn.jlu.schedule.update.download

import android.content.Context
import cn.jlu.schedule.update.model.DownloadProgress
import cn.jlu.schedule.update.model.UpdatePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class ApkDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val verifier: (Context, File, UpdatePayload) -> Unit = ApkVerifier::verify
) {

    /**
     * 下载 APK 安装包，支持 HTTP Range 断点续传与多镜像故障转移。
     */
    suspend fun downloadApk(
        context: Context,
        payload: UpdatePayload,
        preferredMirror: String? = null,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val finalApkFile = File(updatesDir, "cn.jlu.schedule_${payload.versionCode}.apk")

            // 1. 若终态 APK 文件已存在且有效，直接返回
            if (finalApkFile.exists() && finalApkFile.length() == payload.apk.size) {
                try {
                    verifier(context, finalApkFile, payload)
                    onProgress(
                        DownloadProgress(
                            bytesDownloaded = payload.apk.size,
                            totalBytes = payload.apk.size,
                            speedBps = 0,
                            isComplete = true
                        )
                    )
                    return@runCatching finalApkFile
                } catch (_: Exception) {
                    finalApkFile.delete()
                }
            }

            val partFile = File(updatesDir, "${payload.apk.sha256}.part")

            // 2. 构建镜像尝试顺序（优先选用清单响应更快的源）
            val preferredHost = preferredMirror?.toHttpUrlOrNull()?.host
            val sortedMirrors = payload.apk.mirrors.sortedWith(compareByDescending { mirror ->
                if (preferredHost != null && mirror.toHttpUrlOrNull()?.host == preferredHost) 1 else 0
            })

            var lastError: Exception? = null

            for (mirrorUrl in sortedMirrors) {
                coroutineContext.ensureActive()
                try {
                    downloadFromSingleMirror(
                        mirrorUrl = mirrorUrl,
                        partFile = partFile,
                        targetSize = payload.apk.size,
                        onProgress = onProgress
                    )

                    // 下载完成后核验文件大小与完整性
                    verifier(context, partFile, payload)

                    // 原子改名为正式安装包名称
                    if (finalApkFile.exists()) finalApkFile.delete()
                    if (!partFile.renameTo(finalApkFile)) {
                        partFile.copyTo(finalApkFile, overwrite = true)
                        partFile.delete()
                    }

                    // 清理目录中的历史遗留临时文件
                    cleanOldCacheFiles(updatesDir, finalApkFile.name)

                    onProgress(
                        DownloadProgress(
                            bytesDownloaded = payload.apk.size,
                            totalBytes = payload.apk.size,
                            speedBps = 0,
                            isComplete = true
                        )
                    )
                    return@runCatching finalApkFile
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    lastError = e
                    // 若当前镜像下载失败，保留部分文件以备下一个镜像续传或由下一个镜像重头处理
                }
            }

            throw lastError ?: IllegalStateException("所有镜像源下载均失败")
        }.also { result ->
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
    }

    private suspend fun downloadFromSingleMirror(
        mirrorUrl: String,
        partFile: File,
        targetSize: Long,
        onProgress: (DownloadProgress) -> Unit
    ) {
        val existingLen = if (partFile.exists()) partFile.length() else 0L

        // 如果本地文件已经大于或等于预期大小但之前验签失败，则从头重新下载
        var startOffset = if (existingLen >= targetSize) {
            partFile.delete()
            0L
        } else {
            existingLen
        }

        val reqBuilder = Request.Builder().url(mirrorUrl)
        if (startOffset > 0) {
            reqBuilder.header("Range", "bytes=$startOffset-")
        }

        val call = client.newCall(reqBuilder.build())
        val resp = call.execute()

        resp.use { response ->
            if (mirrorUrl.startsWith("https://", ignoreCase = true) && !response.request.url.isHttps) {
                throw SecurityException("APK 下载重定向到了非 HTTPS 地址")
            }
            if (response.code == 416) {
                // 416 Range Not Satisfiable: 清除临时文件重新请求
                partFile.delete()
                startOffset = 0L
                val retryCall = client.newCall(Request.Builder().url(mirrorUrl).build())
                retryCall.execute().use { retryResp ->
                    if (mirrorUrl.startsWith("https://", ignoreCase = true) && !retryResp.request.url.isHttps) {
                        throw SecurityException("APK 下载重定向到了非 HTTPS 地址")
                    }
                    if (retryResp.code != 200) throw Exception("HTTP ${retryResp.code} from $mirrorUrl")
                    writeStreamToPart(retryResp.body?.byteStream(), partFile, 0L, targetSize, append = false, onProgress)
                }
                return
            }

            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} from $mirrorUrl")
            }

            if (response.code == 206) {
                val contentRange = response.header("Content-Range")
                val match = contentRange?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) }
                    ?: throw IllegalStateException("断点续传响应缺少有效 Content-Range")
                val rangeStart = match.groupValues[1].toLong()
                val rangeEnd = match.groupValues[2].toLong()
                val rangeTotal = match.groupValues[3].toLong()
                if (rangeStart != startOffset || rangeEnd < rangeStart || rangeTotal != targetSize) {
                    throw IllegalStateException("断点续传响应范围与请求不符")
                }
            }
            val append = (response.code == 206 && startOffset > 0)
            val currentOffset = if (append) startOffset else 0L
            writeStreamToPart(response.body?.byteStream(), partFile, currentOffset, targetSize, append, onProgress)
        }
    }

    private suspend fun writeStreamToPart(
        stream: InputStream?,
        partFile: File,
        initialOffset: Long,
        totalBytes: Long,
        append: Boolean,
        onProgress: (DownloadProgress) -> Unit
    ) {
        if (stream == null) throw Exception("响应体为空")

        var downloaded = initialOffset
        val buf = ByteArray(16384)
        var lastTime = System.currentTimeMillis()
        var bytesSinceLastSample = 0L
        var currentSpeed = 0L

        FileOutputStream(partFile, append).use { out ->
            var read: Int
            while (stream.read(buf).also { read = it } != -1) {
                coroutineContext.ensureActive()
                if (downloaded + read > totalBytes) throw IllegalStateException("APK 下载数据超出清单声明的大小")
                out.write(buf, 0, read)
                downloaded += read
                bytesSinceLastSample += read

                val now = System.currentTimeMillis()
                val elapsed = now - lastTime
                if (elapsed >= 500) {
                    currentSpeed = (bytesSinceLastSample * 1000) / elapsed
                    lastTime = now
                    bytesSinceLastSample = 0L
                    onProgress(
                        DownloadProgress(
                            bytesDownloaded = downloaded,
                            totalBytes = totalBytes,
                            speedBps = currentSpeed,
                            isComplete = false
                        )
                    )
                }
            }
        }
    }

    private fun cleanOldCacheFiles(directory: File, keepFileName: String) {
        runCatching {
            directory.listFiles()?.forEach { file ->
                if (file.name != keepFileName) {
                    file.delete()
                }
            }
        }
    }
}
