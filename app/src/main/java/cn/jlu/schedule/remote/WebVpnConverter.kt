package cn.jlu.schedule.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 吉大 WebVPN（ZJUWebVPN 架构）URL 转接器。
 *
 * 规则与开源 webvpn-converter 一致：AES-128-CFB，key/iv 默认均为 "wrdvpnisthebest!"，
 * 明文为原始主机名（用字符 '0' 右补齐到 16 字节倍数），密文输出为 hex(iv) + hex(密文)[0..主机名长度*2]。
 * 转接格式：https://vpn.jlu.edu.cn/{http|https}[-端口]/{加密主机}{原路径?原查询}
 */
object WebVpnConverter {
    const val VPN_HOST = "vpn.jlu.edu.cn"
    private const val KEY = "wrdvpnisthebest!"
    private const val IV = "wrdvpnisthebest!"

    internal fun encryptHost(host: String): String {
        val padded = host + "0".repeat(16 - (host.length % 16))
        val data = padded.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"))
        val out = ByteArray(data.size)
        var prev = IV.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < data.size) {
            val stream = cipher.doFinal(prev)
            for (i in 0 until 16) {
                out[offset + i] = (data[offset + i].toInt() xor stream[i].toInt()).toByte()
            }
            prev = out.copyOfRange(offset, offset + 16)
            offset += 16
        }
        val ivHex = IV.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        val cipherHex = out.joinToString("") { "%02x".format(it) }
        return ivHex + cipherHex.take(host.length * 2)
    }

    /** 把吉大局内 URL 转成 WebVPN 转接地址；已包装或非吉大局内域名返回 null */
    fun wrap(url: String): String? = runCatching {
        val httpUrl = url.toHttpUrlOrNull() ?: return@runCatching null
        val host = httpUrl.host
        val isCampusHost = host == "jlu.edu.cn" || host.endsWith(".jlu.edu.cn")
        // CAS 是统一认证本身（公网可达），必须直连以便原生静默登录
        if (!isCampusHost || host == VPN_HOST || host == "cas.jlu.edu.cn") return@runCatching null
        val scheme = httpUrl.scheme
        val defaultPort = if (scheme == "https") 443 else 80
        val portTag = if (httpUrl.port != -1 && httpUrl.port != defaultPort) "-${httpUrl.port}" else ""
        val query = httpUrl.encodedQuery?.let { "?$it" } ?: ""
        "https://$VPN_HOST/$scheme$portTag/${encryptHost(host)}${httpUrl.encodedPath}$query"
    }.getOrNull()

    fun isVpnHost(url: String): Boolean = runCatching {
        url.toHttpUrlOrNull()?.host == VPN_HOST
    }.getOrDefault(false)

    /** WebView 显示用地址：校外模式开启时返回 WebVPN 转接地址，其余原样返回 */
    fun displayUrl(context: android.content.Context, url: String): String =
        if (cn.jlu.schedule.data.AppPreferences.isVpnProxyMode(context)) {
            wrap(url) ?: url
        } else {
            url
        }
}
