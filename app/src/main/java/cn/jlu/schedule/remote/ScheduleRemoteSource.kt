package cn.jlu.schedule.remote

import android.content.Context
import android.util.Log
import cn.jlu.schedule.auth.TpassConfig
import cn.jlu.schedule.parser.ScheduleImportCacheParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** 课表 JSON 拉取结果 */
data class ScheduleFetch(
    val json: String,
    val finalUrl: String
)

/**
 * 从教务平台直接拉取课表 JSON（复用现有解析链路的输入格式）。
 * 会话失效的判定标准：请求被重定向回统一认证域名，或响应体里出现登录表单。
 */
object ScheduleRemoteSource {
    private const val TAG = "ScheduleRemoteSource"

    sealed class FetchError(message: String) : Exception(message) {
        class SessionExpired : FetchError("登录已过期")
        class NotSchedulePayload(message: String) : FetchError(message)
        class Network(message: String) : FetchError(message)
    }

    /**
     * 原生直接抓取完整课表多批次数据（整学期 + 本周视图，共 2+ 载荷），无需慢速 WebView。
     * 若会话过期则尝试静默重登并重试。
     */
    suspend fun fetchScheduleNative(context: Context): Result<List<ScheduleFetch>> =
        withContext(Dispatchers.IO) {
            runCatching {
                JwApiClient.importAllWebViewCookies(context)
                if (JwApiClient.ensureSession(context)) {
                    JwApiClient.syncJarToWebView(context)
                }
                var list = fetchScheduleInternal(context)
                if (list.isEmpty()) {
                    Log.i(TAG, "initial schedule fetch empty, attempting ensureSession retry...")
                    if (JwApiClient.ensureSession(context)) {
                        JwApiClient.syncJarToWebView(context)
                        list = fetchScheduleInternal(context)
                    }
                }
                if (list.isEmpty()) {
                    throw FetchError.NotSchedulePayload("未能获取到有效课表数据")
                }
                list
            }
        }

    private fun fetchScheduleInternal(context: Context): List<ScheduleFetch> {
        val client = JwApiClient.get(context)

        // 1. 访问 wdkb 首页以激活应用上下文并建立业务会话
        val indexReq = Request.Builder()
            .url("https://iedu.jlu.edu.cn/jwapp/sys/wdkb/*default/index.do")
            .header("Referer", TpassConfig.IEDU_PORTAL_URL)
            .header("User-Agent", TpassConfig.USER_AGENT)
            .build()
        val indexOk = runCatching {
            VpnRequest.execute(context, client, indexReq).use { resp ->
                val body = resp.body?.string().orEmpty()
                val onCas = resp.request.url.host == TpassConfig.CAS_HOST
                if (onCas || body.contains("id=\"loginForm\"")) {
                    return emptyList()
                }
                resp.isSuccessful
            }
        }.getOrDefault(false)
        if (!indexOk) {
            Log.w(TAG, "wdkb app index activation returned false")
            return emptyList()
        }

        // 2. 查询当前学期代码 (dqxnxq.do)
        val dqReq = Request.Builder()
            .url("https://iedu.jlu.edu.cn/jwapp/sys/wdkb/modules/jshkcb/dqxnxq.do")
            .header("Referer", "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/*default/index.do")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", TpassConfig.USER_AGENT)
            .post(FormBody.Builder().build())
            .build()
        var xnxqdm = ""
        runCatching {
            VpnRequest.execute(context, client, dqReq).use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.contains("\"DM\"")) {
                    val obj = JSONObject(body)
                    xnxqdm = obj.getJSONObject("datas")
                        .getJSONObject("dqxnxq")
                        .getJSONArray("rows")
                        .getJSONObject(0)
                        .optString("DM", "")
                }
            }
        }.onFailure { Log.w(TAG, "dqxnxq query failed: ${it.message}") }

        if (xnxqdm.isBlank()) {
            Log.w(TAG, "current semester code unavailable; leaving native fetch to WebView fallback")
            return emptyList()
        }
        Log.i(TAG, "using semester code: $xnxqdm")

        val results = mutableListOf<ScheduleFetch>()

        // 3. 查询整学期综合课表 (cxxszhxqkb.do 带 XNXQDM)
        val fullUrl = "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do"
        val fullReq = Request.Builder()
            .url(fullUrl)
            .header("Referer", "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/*default/index.do")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", TpassConfig.USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(FormBody.Builder().add("XNXQDM", xnxqdm).build())
            .build()
        runCatching {
            VpnRequest.execute(context, client, fullReq).use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && JwEndpoints.looksLikeSchedulePayload(body)) {
                    Log.i(TAG, "cxxszhxqkb full semester fetched: ${body.length} bytes")
                    results.add(ScheduleFetch(json = body, finalUrl = fullUrl))
                } else {
                    Log.w(TAG, "cxxszhxqkb full failed: code=${resp.code}, len=${body.length}")
                }
            }
        }.onFailure { Log.e(TAG, "cxxszhxqkb full request error", it) }

        // 4. 查询当前周课表 (cxxszhxqkb.do 带 XNXQDM + SKZC=1)
        val weekReq = Request.Builder()
            .url(fullUrl)
            .header("Referer", "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/*default/index.do")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", TpassConfig.USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(FormBody.Builder().add("XNXQDM", xnxqdm).add("SKZC", "1").build())
            .build()
        runCatching {
            VpnRequest.execute(context, client, weekReq).use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && JwEndpoints.looksLikeSchedulePayload(body)) {
                    Log.i(TAG, "cxxszhxqkb week 1 fetched: ${body.length} bytes")
                    results.add(ScheduleFetch(json = body, finalUrl = "$fullUrl?SKZC=1"))
                }
            }
        }.onFailure { Log.w(TAG, "cxxszhxqkb week 1 request error", it) }

        return results
    }

    suspend fun fetch(context: Context, endpointUrl: String): Result<ScheduleFetch> =
        fetch(JwApiClient.get(context), endpointUrl)

    /** 可注入 client 的核心实现（供单元测试使用 MockWebServer） */
    suspend fun fetch(client: OkHttpClient, endpointUrl: String): Result<ScheduleFetch> =
        fetch(null, client, endpointUrl)

    /** 可空 context：为 null 时不做 VPN 转接（单元测试路径） */
    suspend fun fetch(context: Context?, client: OkHttpClient, endpointUrl: String): Result<ScheduleFetch> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(endpointUrl)
                    .header("Referer", TpassConfig.IEDU_PORTAL_URL)
                    .build()
                val response = if (context != null) {
                    VpnRequest.execute(context, client, request)
                } else {
                    client.newCall(request).execute()
                }
                response.use { response ->
                    val finalUrl = response.request.url.toString()
                    val body = response.body?.string().orEmpty()
                    val onCas = response.request.url.host == TpassConfig.CAS_HOST
                    if (onCas || body.contains("id=\"loginForm\"")) {
                        throw FetchError.SessionExpired()
                    }
                    if (!response.isSuccessful) {
                        throw FetchError.Network("课表接口返回 ${response.code}")
                    }
                    if (!JwEndpoints.looksLikeSchedulePayload(body) ||
                        !ScheduleImportCacheParser.isLikelyScheduleUrl(finalUrl)
                    ) {
                        throw FetchError.NotSchedulePayload("响应不是课表数据（接口地址可能已变更）")
                    }
                    ScheduleFetch(json = body, finalUrl = finalUrl)
                }
            }.recoverCatching { error ->
                throw if (error is FetchError) error else FetchError.Network(error.message ?: "网络异常")
            }
        }
}
