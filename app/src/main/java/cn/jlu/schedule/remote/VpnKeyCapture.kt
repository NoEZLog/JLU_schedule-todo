package cn.jlu.schedule.remote

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import cn.jlu.schedule.data.AppPreferences

/**
 * 从 WebVPN 门户页面（已登录态）读取真实转接密钥：
 * 页面上下文里 fetch('/user/info') 返回 wrdvpnKey / wrdvpnIV，
 * 经 JS 桥回传并持久化——WebView 加载的包装页因此能立即用上真密钥。
 */
object VpnKeyCapture {
    const val BRIDGE_NAME = "ClassCueVpnKeyBridge"

    val CAPTURE_JS = """
        (function(){
          try {
            fetch('/user/info', {credentials:'same-origin'})
              .then(function(r){ return r.ok ? r.json() : null; })
              .then(function(j){
                if (j && j.wrdvpnKey && window.$BRIDGE_NAME) {
                  window.$BRIDGE_NAME.onKey(String(j.wrdvpnKey), String(j.wrdvpnIV || j.wrdvpnKey));
                }
              }).catch(function(e){});
          } catch(e) {}
        })();
    """.trimIndent()

    /** 给 WebView 挂上 JS 桥（在设置 WebViewClient 前后均可） */
    fun attach(webView: WebView) {
        webView.addJavascriptInterface(Bridge(webView.context.applicationContext), BRIDGE_NAME)
    }

    /** onPageFinished 时调用：vpn 门户页（非登录页）注入抓取脚本 */
    fun onPageFinished(view: WebView, url: String) {
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
        if (host == WebVpnConverter.VPN_HOST && !url.contains("/login")) {
            view.evaluateJavascript(CAPTURE_JS, null)
        }
    }

    class Bridge(private val appContext: Context) {
        @JavascriptInterface
        fun onKey(key: String, iv: String) {
            if (key.length != 16 || iv.length != 16) return
            AppPreferences.setVpnCryptKey(appContext, key)
            AppPreferences.setVpnCryptIv(appContext, iv)
        }
    }
}
