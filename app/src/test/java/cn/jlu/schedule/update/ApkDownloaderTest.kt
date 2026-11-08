package cn.jlu.schedule.update

import android.content.Context
import android.content.ContextWrapper
import cn.jlu.schedule.update.download.ApkDownloader
import cn.jlu.schedule.update.model.ApkMetadata
import cn.jlu.schedule.update.model.DownloadProgress
import cn.jlu.schedule.update.model.UpdatePayload
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApkDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server1: MockWebServer
    private lateinit var server2: MockWebServer
    private lateinit var testContext: Context

    @Before
    fun setup() {
        server1 = MockWebServer()
        server1.start()
        server2 = MockWebServer()
        server2.start()

        val cacheDir = tmp.newFolder("test_cache")
        testContext = object : ContextWrapper(null) {
            override fun getCacheDir(): File {
                return cacheDir
            }
        }
    }

    @After
    fun tearDown() {
        server1.shutdown()
        server2.shutdown()
    }

    private fun samplePayload(size: Long, mirrors: List<String>): UpdatePayload {
        return UpdatePayload(
            schema = 1,
            packageName = "cn.jlu.schedule",
            channel = "stable",
            versionCode = 6,
            versionName = "2.2.0",
            minSdk = 26,
            publishedAt = "2026-10-01T00:00:00Z",
            notes = listOf("更新测试"),
            apk = ApkMetadata(
                size = size,
                sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                signerSha256 = "7ed7c719a6abb18d1eb014a18c173532fd4f11fb4bd350f003245549135dd6dc",
                mirrors = mirrors
            )
        )
    }

    @Test
    fun testDownloadApkSuccessFromFirstMirror() = runBlocking {
        val testBytes = ByteArray(1024) { (it % 128).toByte() }
        server1.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(testBytes))
        )

        val url1 = server1.url("/updates/android/versions/6/app.apk").toString()
        val payload = samplePayload(testBytes.size.toLong(), listOf(url1))

        var verifierCalled = false
        val downloader = ApkDownloader(
            verifier = { _, _, _ -> verifierCalled = true }
        )

        val progresses = mutableListOf<DownloadProgress>()
        val result = downloader.downloadApk(
            context = testContext,
            payload = payload,
            onProgress = { progresses.add(it) }
        )

        assertTrue(result.isSuccess)
        val file = result.getOrThrow()
        assertTrue(file.exists())
        assertEquals(testBytes.size.toLong(), file.length())
        assertArrayEquals(testBytes, file.readBytes())
        assertTrue(verifierCalled)
        assertTrue(progresses.isNotEmpty())
        assertTrue(progresses.last().isComplete)
    }

    @Test
    fun testDownloadApkFallbackWhenFirstMirrorFails() = runBlocking {
        val testBytes = ByteArray(512) { (it % 64).toByte() }
        server1.enqueue(MockResponse().setResponseCode(500).setBody("Failed"))
        server2.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(testBytes))
        )

        val url1 = server1.url("/updates/android/versions/6/app.apk").toString()
        val url2 = server2.url("/updates/android/versions/6/app.apk").toString()
        val payload = samplePayload(testBytes.size.toLong(), listOf(url1, url2))

        var verifierCalled = false
        val downloader = ApkDownloader(
            verifier = { _, _, _ -> verifierCalled = true }
        )

        val result = downloader.downloadApk(
            context = testContext,
            payload = payload
        )

        assertTrue(result.isSuccess)
        val file = result.getOrThrow()
        assertEquals(testBytes.size.toLong(), file.length())
        assertArrayEquals(testBytes, file.readBytes())
        assertTrue(verifierCalled)
    }

    @Test
    fun testDownloadApkRangeResume() = runBlocking {
        val fullBytes = ByteArray(1000) { (it % 100).toByte() }
        val prefixBytes = fullBytes.copyOfRange(0, 400)
        val remainingBytes = fullBytes.copyOfRange(400, 1000)

        // 预置 .part 文件已下载 400 字节
        val updatesDir = File(testContext.cacheDir, "updates").apply { mkdirs() }
        val partFile = File(updatesDir, "0000000000000000000000000000000000000000000000000000000000000000.part")
        partFile.writeBytes(prefixBytes)

        server1.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                return if (range == "bytes=400-") {
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 400-999/1000")
                        .setBody(Buffer().write(remainingBytes))
                } else {
                    MockResponse().setResponseCode(400)
                }
            }
        }

        val url1 = server1.url("/updates/android/versions/6/app.apk").toString()
        val payload = samplePayload(1000L, listOf(url1))

        val downloader = ApkDownloader(
            verifier = { _, _, _ -> }
        )

        val result = downloader.downloadApk(testContext, payload)
        assertTrue(result.isSuccess)
        val file = result.getOrThrow()
        assertEquals(1000L, file.length())
        assertArrayEquals(fullBytes, file.readBytes())
    }

    @Test
    fun testDownloadApkRecoversFrom416RangeNotSatisfiable() = runBlocking {
        val fullBytes = ByteArray(500) { (it % 50).toByte() }

        // 预置损坏的 .part 文件
        val updatesDir = File(testContext.cacheDir, "updates").apply { mkdirs() }
        val partFile = File(updatesDir, "0000000000000000000000000000000000000000000000000000000000000000.part")
        partFile.writeBytes(ByteArray(200) { 1 })

        var requestCount = 0
        server1.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestCount++
                val range = request.getHeader("Range")
                return if (range != null) {
                    // 首次断点续传返回 416
                    MockResponse().setResponseCode(416)
                } else {
                    // 重试全量下载返回 200
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(Buffer().write(fullBytes))
                }
            }
        }

        val url1 = server1.url("/updates/android/versions/6/app.apk").toString()
        val payload = samplePayload(500L, listOf(url1))

        val downloader = ApkDownloader(
            verifier = { _, _, _ -> }
        )

        val result = downloader.downloadApk(testContext, payload)
        assertTrue(result.isSuccess)
        val file = result.getOrThrow()
        assertEquals(500L, file.length())
        assertArrayEquals(fullBytes, file.readBytes())
        assertEquals(2, requestCount)
    }

    @Test
    fun testRejectsMoreBytesThanSignedManifestDeclares() = runBlocking {
        server1.enqueue(MockResponse().setBody(Buffer().write(ByteArray(12))))
        val payload = samplePayload(10L, listOf(server1.url("/app.apk").toString()))
        val result = ApkDownloader(verifier = { _, _, _ -> }).downloadApk(testContext, payload)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("超出") == true)
    }
}
