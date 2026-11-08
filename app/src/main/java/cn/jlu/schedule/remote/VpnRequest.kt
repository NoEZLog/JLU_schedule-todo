package cn.jlu.schedule.remote

import android.content.Context
import cn.jlu.schedule.data.AppPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 网络请求执行器：在直连与 WebVPN 转接之间自动切换。
 * - 设置开启「校外模式」时优先走 VPN 转接，失败回退直连；
 * - 关闭时直连优先，直连失败（典型校外场景）自动转接 WebVPN 重试一次。
 *
 * 转接请求会移除手写的 Cookie 头（否则 OkHttp 跳过 CookieJar，VPN 会话带不上），
 * 并在落到 VPN 登录页时用已有 CAS 会话（CASTGC）经 SSO 自动重建 WebVPN 会话后重试。
 */
object VpnRequest {

    fun execute(context: Context, client: OkHttpClient, request: Request): Response {
        val preferVpn = AppPreferences.isVpnProxyMode(context)
        val wrappedUrl = WebVpnConverter.wrap(request.url.toString())
        if (wrappedUrl == null) return client.newCall(request).execute()

        if (preferVpn) {
            var response = client.newCall(wrapRequest(request, wrappedUrl)).execute()
            if (isVpnLoginPage(response)) {
                response.close()
                establishVpnSessionViaCas(client)
                response = client.newCall(wrapRequest(request, wrappedUrl)).execute()
            }
            return response
        }

        try {
            return client.newCall(request).execute()
        } catch (error: IOException) {
            var response = client.newCall(wrapRequest(request, wrappedUrl)).execute()
            if (isVpnLoginPage(response)) {
                response.close()
                if (establishVpnSessionViaCas(client)) {
                    response = client.newCall(wrapRequest(request, wrappedUrl)).execute()
                }
            }
            return response
        }
    }

    private fun wrapRequest(request: Request, wrappedUrl: String): Request =
        request.newBuilder().url(wrappedUrl).removeHeader("Cookie").build()

    private fun isVpnLoginPage(response: Response): Boolean {
        val url = response.request.url
        return url.host == WebVpnConverter.VPN_HOST && url.encodedPath.contains("login")
    }

    /** 用已有的 CAS 会话（CASTGC）经 SSO 建立 WebVPN 会话；成功返回 true */
    private fun establishVpnSessionViaCas(client: OkHttpClient): Boolean {
        return runCatching {
            val loginReq = Request.Builder()
                .url("https://${WebVpnConverter.VPN_HOST}/login")
                .build()
            client.newCall(loginReq).execute().use { response ->
                response.request.url.host == WebVpnConverter.VPN_HOST &&
                    !response.request.url.encodedPath.contains("login")
            }
        }.getOrDefault(false)
    }
}
