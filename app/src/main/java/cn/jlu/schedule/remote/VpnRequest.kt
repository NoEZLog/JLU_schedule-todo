package cn.jlu.schedule.remote

import android.content.Context
import android.webkit.CookieManager
import cn.jlu.schedule.auth.TpassConfig
import cn.jlu.schedule.data.AppPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 网络请求执行器：在直连与 WebVPN 转接之间自动切换。
 *
 * 转接策略：
 * - 候选密钥逐个尝试（已验证密钥优先），连接失败 / 5xx / PARSE_FAILED 自动换下一把，最后回退直连；
 * - 转接请求移除手写 Cookie 头，由 CookieJar 自动挂 vpn.jlu.edu.cn 会话；
 * - 落到 VPN 登录页时：先从 WebView CookieManager 同步 VPN Cookie（登录多发生在 WebView），
 *   再显式携带 CAS 的 CASTGC 走「CAS → WebVPN SSO」两跳建立会话后重试；
 *   仍失败则把登录页响应回传，由调用方判定会话过期。
 */
object VpnRequest {

    fun execute(context: Context, client: OkHttpClient, request: Request): Response {
        val preferVpn = AppPreferences.isVpnProxyMode(context)
        if (preferVpn) {
            return executeViaVpn(context, client, request)
        }
        try {
            return client.newCall(request).execute()
        } catch (error: IOException) {
            return executeViaVpn(context, client, request)
        }
    }

    private fun executeViaVpn(context: Context, client: OkHttpClient, request: Request): Response {
        var lastError: IOException? = null
        var vpnLoginResponse: Response? = null
        var sessionReestablished = false
        var round = 0

        while (round < 2) {
            round++
        try {
            for (key in WebVpnConverter.keyCandidates(AppPreferences.getVpnCryptKey(context))) {
                val wrappedUrl = WebVpnConverter.wrap(request.url.toString(), key) ?: continue
                val wrappedRequest = request.newBuilder().url(wrappedUrl).removeHeader("Cookie").build()

                val response = try {
                    client.newCall(wrappedRequest).execute()
                } catch (error: IOException) {
                    lastError = error
                    continue
                }

                if (isVpnLoginPage(response)) {
                    if (!sessionReestablished) {
                        sessionReestablished = true
                        response.close()
                        establishVpnSession(context, client)
                        // 会话建立后立刻从门户取真密钥（/user/info 返回 wrdvpnKey/wrdvpnIV），
                        // 持久化后重启候选循环——第一轮用的静态候选全部作废也无妨。
                        fetchPortalKey(context, client)
                        round--
                        break // break 内层 for，外层 while 用刷新后的密钥列表重来
                    }
                    vpnLoginResponse?.close()
                    vpnLoginResponse = response
                    continue
                }

                if (response.code >= 500 || isParseFailed(response)) {
                    response.close()
                    continue
                }

                AppPreferences.setVpnCryptKey(context, key)
                return response
            }
        } catch (error: IOException) {
            lastError = error
        }
        } // while

        vpnLoginResponse?.let { return it }
        try {
            return client.newCall(request).execute()
        } catch (error: IOException) {
            throw lastError ?: error
        }
    }

    private fun isVpnLoginPage(response: Response): Boolean {
        val url = response.request.url
        return url.host == WebVpnConverter.VPN_HOST && url.encodedPath.contains("login")
    }

    private fun isParseFailed(response: Response): Boolean = runCatching {
        val body = response.peekBody(65536).string()
        body.contains("PARSE_FAILED") || body.contains("出错啦")
    }.getOrDefault(false)

    /**
     * 从门户接口 /user/info 读取真实转接密钥（wrdvpnKey/wrdvpnIV）并持久化。
     * 需要已建立的 WebVPN 会话（CookieJar 自动携带）；成功返回密钥。
     */
    private fun fetchPortalKey(context: Context, client: OkHttpClient): String? {
        return runCatching {
            val request = Request.Builder()
                .url("https://${WebVpnConverter.VPN_HOST}/user/info")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string().orEmpty()
                val json = org.json.JSONObject(body)
                val key = json.optString("wrdvpnKey").takeIf { it.length == 16 } ?: return@runCatching null
                val iv = json.optString("wrdvpnIV").takeIf { it.length == 16 } ?: key
                AppPreferences.setVpnCryptKey(context, key)
                AppPreferences.setVpnCryptIv(context, iv)
                key
            }
        }.getOrNull()
    }

    /**
     * 建立 WebVPN 会话：
     * 1) 从 WebView CookieManager 把 vpn 域各路径 Cookie 同步进 CookieJar（登录发生在 WebView）；
     * 2) 若仍无会话，显式携带 CAS 的 CASTGC Cookie 请求 WebVPN 登录页——
     *    WRD 网关检测到统一认证票据后自动 302 建立 VPN 会话（会话 Cookie 由 CookieJar 从响应中持久化）。
     */
    private fun establishVpnSession(context: Context, client: OkHttpClient): Boolean {
        // 1) WebView → CookieJar 同步
        runCatching { JwApiClient.importAllWebViewCookies(context) }
        // 2) 携带 CASTGC 走 SSO
        return runCatching {
            val castgc = CookieManager.getInstance().getCookie(TpassConfig.CAS_LOGIN_URL)
                ?.split(";")
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("CASTGC=") }
            val loginReq = Request.Builder()
                .url("https://${WebVpnConverter.VPN_HOST}/login")
                .apply {
                    if (castgc != null) header("Cookie", castgc)
                }
                .build()
            client.newCall(loginReq).execute().use { response ->
                response.request.url.host == WebVpnConverter.VPN_HOST &&
                    !response.request.url.encodedPath.contains("login")
            }
        }.getOrDefault(false)
    }
}
