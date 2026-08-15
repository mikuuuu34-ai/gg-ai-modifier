package com.yl.aigg.ai_gg666.mcp

import android.content.Context
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * MCP 服务配置。存 SharedPreferences，不进仓库。
 *
 * 安全默认值：只绑 127.0.0.1、强制 token、只读模式开启。
 * 这是一个能以 root 读写任意进程内存的 HTTP 接口，默认必须收紧。
 */
object McpConfig {

    private const val PREFS = "mcp_config"
    private const val KEY_PORT = "port"
    private const val KEY_TOKEN = "token"
    private const val KEY_LAN = "lan_enabled"
    private const val KEY_READONLY = "read_only"
    private const val KEY_AUTOSTART = "auto_start"

    const val DEFAULT_PORT = 8788
    const val LOOPBACK = "127.0.0.1"

    @Volatile
    var port: Int = DEFAULT_PORT
        private set

    @Volatile
    var token: String = ""
        private set

    /** 是否监听所有网卡（局域网可达）。默认关闭。 */
    @Volatile
    var lanEnabled: Boolean = false
        private set

    /** 只读模式：拒绝一切写内存/冻结/执行脚本的工具。默认开启。 */
    @Volatile
    var readOnly: Boolean = true
        private set

    @Volatile
    var autoStart: Boolean = false
        private set

    private var appContext: Context? = null

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        port = sp.getInt(KEY_PORT, DEFAULT_PORT)
        lanEnabled = sp.getBoolean(KEY_LAN, false)
        readOnly = sp.getBoolean(KEY_READONLY, true)
        autoStart = sp.getBoolean(KEY_AUTOSTART, false)
        token = sp.getString(KEY_TOKEN, "").orEmpty().ifEmpty {
            generateToken().also { sp.edit().putString(KEY_TOKEN, it).apply() }
        }
    }

    fun update(
        port: Int? = null,
        lanEnabled: Boolean? = null,
        readOnly: Boolean? = null,
        autoStart: Boolean? = null
    ) {
        val ctx = appContext ?: return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = sp.edit()
        port?.let { if (it in 1024..65535) { this.port = it; e.putInt(KEY_PORT, it) } }
        lanEnabled?.let { this.lanEnabled = it; e.putBoolean(KEY_LAN, it) }
        readOnly?.let { this.readOnly = it; e.putBoolean(KEY_READONLY, it) }
        autoStart?.let { this.autoStart = it; e.putBoolean(KEY_AUTOSTART, it) }
        e.apply()
    }

    fun regenerateToken(): String {
        val ctx = appContext ?: return token
        val t = generateToken()
        token = t
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, t).apply()
        return t
    }

    /** 绑定地址：未开局域网时只绑回环 */
    fun bindAddress(): String? = if (lanEnabled) null else LOOPBACK

    fun localEndpoint(): String = "http://$LOOPBACK:$port/mcp"

    fun lanEndpoint(): String? {
        if (!lanEnabled) return null
        val ip = localIpv4() ?: return null
        return "http://$ip:$port/mcp"
    }

    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
