package cn.jlu.schedule.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 吉大 WebVPN（网瑞达 WRD / ZJUWebVPN 架构）URL 转接器。
 *
 * 加密：AES-128-CFB，明文为原始主机名（用字符 '0' 右补齐到 16 字节倍数），
 * 输出 hex(iv) + hex(密文)[0..主机名长度*2]。
 * 转接格式：https://vpn.jlu.edu.cn/{http|https}[-端口]/{加密主机}{原路径?原查询}
 *
 * 密钥：vpn.jlu.edu.cn 实际使用 "Didida1127Didida"（kaaass/jlu-webvpn-assistant 实测），
 * 部分历史部署使用 "wrdvpnisthebest!"——两把候选密钥都会被自动尝试，
 * 有效密钥会持久化到设置中。
 */
object WebVpnConverter {
    const val VPN_HOST = "vpn.jlu.edu.cn"

    /** 吉大现役密钥（vpn.jlu.edu.cn 登录页内嵌加密脚本实测提取并经服务器验证） */
    const val KEY_JLU = "wrdvpnisawesome!"

    /** 历史密钥：jlu-webvpn-assistant 脚本所用（已失效，保留兜底） */
    const val KEY_DIDIDA = "Didida1127Didida"

    /** WRD 系统历史默认密钥（部分高校部署仍在用） */
    const val KEY_WRD = "wrdvpnisthebest!"

    /** 候选密钥顺序：优先已验证的持久化密钥，其次现役密钥，最后历史密钥 */
    fun keyCandidates(verifiedKey: String?): List<String> {
        val candidates = mutableListOf<String>()
        if (!verifiedKey.isNullOrBlank()) candidates.add(verifiedKey)
        for (k in listOf(KEY_JLU, KEY_DIDIDA, KEY_WRD)) {
            if (!candidates.contains(k)) candidates.add(k)
        }
        return candidates
    }

    internal fun encryptHost(host: String, key: String, iv: String): String {
        val padded = host + "0".repeat(16 - (host.length % 16))
        val data = padded.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        val out = ByteArray(data.size)
        var prev = iv.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < data.size) {
            val stream = cipher.doFinal(prev)
            for (i in 0 until 16) {
                out[offset + i] = (data[offset + i].toInt() xor stream[i].toInt()).toByte()
            }
            prev = out.copyOfRange(offset, offset + 16)
            offset += 16
        }
        val ivHex = iv.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        val cipherHex = out.joinToString("") { "%02x".format(it) }
        return ivHex + cipherHex.take(host.length * 2)
    }

    /** 把吉大局内 URL 转成 WebVPN 转接地址；已包装、CAS 或非吉大局内域名返回 null */
    fun wrap(url: String, key: String = KEY_JLU, iv: String = key): String? = runCatching {
        val httpUrl = url.toHttpUrlOrNull() ?: return@runCatching null
        val host = httpUrl.host
        val isCampusHost = host == "jlu.edu.cn" || host.endsWith(".jlu.edu.cn")
        // CAS 是统一认证本身（公网可达），必须直连以便原生静默登录
        if (!isCampusHost || host == VPN_HOST || host == "cas.jlu.edu.cn") return@runCatching null
        val scheme = httpUrl.scheme
        val defaultPort = if (scheme == "https") 443 else 80
        val portTag = if (httpUrl.port != -1 && httpUrl.port != defaultPort) "-${httpUrl.port}" else ""
        val query = httpUrl.encodedQuery?.let { "?$it" } ?: ""
        "https://$VPN_HOST/$scheme$portTag/${encryptHost(host, key, iv)}${httpUrl.encodedPath}$query"
    }.getOrNull()

    fun isVpnHost(url: String): Boolean = runCatching {
        url.toHttpUrlOrNull()?.host == VPN_HOST
    }.getOrDefault(false)

    /** WebView 显示用地址：校外模式开启时返回 WebVPN 转接地址（使用已验证密钥/IV），其余原样返回 */
    fun displayUrl(context: android.content.Context, url: String): String =
        if (cn.jlu.schedule.data.AppPreferences.isVpnProxyMode(context)) {
            val prefs = cn.jlu.schedule.data.AppPreferences
            wrap(url, prefs.getVpnCryptKey(context), prefs.getVpnCryptIv(context) ?: prefs.getVpnCryptKey(context)) ?: url
        } else {
            url
        }
}
