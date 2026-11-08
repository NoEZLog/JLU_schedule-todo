package cn.jlu.schedule.update.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import cn.jlu.schedule.data.AppPreferences
import cn.jlu.schedule.update.model.UpdateCheckResult
import cn.jlu.schedule.update.model.UpdatePayload
import cn.jlu.schedule.update.security.UpdateManifestVerifier
import cn.jlu.schedule.update.security.UpdateSecurityConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class UpdateRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val trustedKeys: Map<String, String> = UpdateSecurityConfig.TRUSTED_PUBLIC_KEYS,
    private val appVersionCodeOverride: Int? = null,
    private val isDebugOverride: Boolean? = null
) {

    private data class FetchOutcome(
        val url: String,
        val result: Result<UpdatePayload>,
        val durationMs: Long
    )

    /**
     * 自动静默检查更新（受 24 小时频次限制）。
     */
    suspend fun checkAutoUpdate(
        context: Context,
        endpoints: List<String> = UpdateSecurityConfig.DEFAULT_ENDPOINTS
    ): UpdateCheckResult {
        val lastCheck = AppPreferences.getLastUpdateCheckTime(context)
        val now = System.currentTimeMillis()
        if (lastCheck <= now && now - lastCheck < 24 * 3600 * 1000L) {
            return UpdateCheckResult.NoUpdate("距离上次检查更新不足24小时")
        }
        return checkUpdate(context, force = false, endpoints = endpoints)
    }

    /**
     * 检查新版本（支持手动强制刷新与自定义镜像入口测试）。
     */
    suspend fun checkUpdate(
        context: Context,
        force: Boolean = false,
        endpoints: List<String> = UpdateSecurityConfig.DEFAULT_ENDPOINTS,
        allowInDebug: Boolean = false
    ): UpdateCheckResult {
        val isDebug = isDebugApp(context)
        if (!allowInDebug && isDebug) {
            return UpdateCheckResult.NoUpdate("Debug 构建默认不接入稳定版更新清单")
        }

        val currentVersionCode = getAppVersionCode(context)
        val currentSdk = if (android.os.Build.VERSION.SDK_INT > 0) android.os.Build.VERSION.SDK_INT else 34

        val outcomes = coroutineScope {
            endpoints.map { url ->
                async(Dispatchers.IO) {
                    fetchSingleManifest(url, currentVersionCode, currentSdk)
                }
            }.awaitAll()
        }

        val successes = outcomes.filter { it.result.isSuccess }
        if (successes.isEmpty()) {
            val allErrors = outcomes.mapNotNull { it.result.exceptionOrNull()?.message }
            val errorSummary = if (allErrors.isNotEmpty()) allErrors.distinct().joinToString("; ") else "网络连接异常"
            return UpdateCheckResult.Error("所有更新源均不可用: $errorSummary")
        }

        // 网络失败不计入 24 小时间隔，避免断网后长期错过更新。
        AppPreferences.setLastUpdateCheckTime(context, System.currentTimeMillis())

        // 双端一致性检测：两端若返回相同的版本号，必须提供完全相同的 APK SHA-256
        if (successes.size >= 2) {
            val p1 = successes[0].result.getOrThrow()
            val p2 = successes[1].result.getOrThrow()
            if (p1.versionCode == p2.versionCode && !p1.apk.sha256.equals(p2.apk.sha256, ignoreCase = true)) {
                return UpdateCheckResult.MirrorMismatch("多端镜像返回的 APK 哈希不一致，为安全起见已终止更新")
            }
        }

        // 选择最高的有效 versionCode；若版本号相同则选用响应延迟更低的镜像源
        val best = successes.sortedWith(
            compareByDescending<FetchOutcome> { it.result.getOrThrow().versionCode }
                .thenBy { it.durationMs }
        ).first()

        val payload = best.result.getOrThrow()

        // 检查版本是否高于当前版本
        if (payload.versionCode <= currentVersionCode) {
            return UpdateCheckResult.NoUpdate("当前已是最新版本")
        }

        // 检查是否已被用户忽略
        if (!force && AppPreferences.getIgnoredUpdateVersion(context) == payload.versionCode) {
            return UpdateCheckResult.NoUpdate("用户已忽略此版本 (${payload.versionName})")
        }

        return UpdateCheckResult.UpdateAvailable(
            payload = payload,
            preferredMirror = best.url
        )
    }

    private fun fetchSingleManifest(url: String, currentVersionCode: Int, currentSdk: Int): FetchOutcome {
        val start = System.currentTimeMillis()
        return try {
            val req = Request.Builder()
                .url(url)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("User-Agent", "JLU-Schedule-Updater")
                .build()

            val call = client.newCall(req)
            val resp = call.execute()
            resp.use { response ->
                if (url.startsWith("https://", ignoreCase = true) && !response.request.url.isHttps) {
                    return FetchOutcome(url, Result.failure(SecurityException("清单重定向到了非 HTTPS 地址")), System.currentTimeMillis() - start)
                }
                if (!response.isSuccessful) {
                    return FetchOutcome(url, Result.failure(Exception("HTTP ${response.code}")), System.currentTimeMillis() - start)
                }
                val body = response.body?.string()
                    ?: return FetchOutcome(url, Result.failure(Exception("响应体为空")), System.currentTimeMillis() - start)

                val verified = UpdateManifestVerifier.verifyAndParse(
                    envelopeJson = body,
                    trustedKeys = trustedKeys,
                    currentVersionCode = currentVersionCode,
                    currentSdk = currentSdk,
                    checkVersionNewer = false
                )
                FetchOutcome(url, verified, System.currentTimeMillis() - start)
            }
        } catch (e: Exception) {
            FetchOutcome(url, Result.failure(e), System.currentTimeMillis() - start)
        }
    }

    private fun isDebugApp(context: Context): Boolean {
        return isDebugOverride ?: ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }

    private fun getAppVersionCode(context: Context): Int {
        if (appVersionCodeOverride != null) return appVersionCodeOverride
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            1
        }
    }
}
