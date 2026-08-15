package com.yl.aigg.ai_gg666.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.yl.aigg.ai_gg666.MemoryEngine

/**
 * 承载 MCP HTTP 服务的前台服务。
 *
 * 必须是前台服务：App 切到后台后，普通服务的 socket 会随进程被回收，
 * 而使用场景恰恰是「用户在玩游戏、App 在后台、Claude Code 在另一侧驱动」。
 */
class McpService : Service() {

    companion object {
        private const val TAG = "McpService"
        private const val CHANNEL_ID = "mcp_channel"
        private const val NOTIFICATION_ID = 20260815

        const val ACTION_START = "com.yl.aigg.mcp.START"
        const val ACTION_STOP = "com.yl.aigg.mcp.STOP"

        @Volatile
        private var server: McpServer? = null

        @Volatile
        private var lastError: String? = null

        fun isRunning(): Boolean = server?.wasStarted() == true

        fun getLastError(): String? = lastError

        fun start(context: Context) {
            val intent = Intent(context, McpService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, McpService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        McpConfig.init(this)
        McpTools.init(this)
        MemoryEngine.setContext(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("正在启动…"))
                val ok = startServer()
                updateNotification(
                    if (ok) "监听 ${McpConfig.localEndpoint()}"
                    else "启动失败：${lastError ?: "未知原因"}"
                )
                if (!ok) {
                    stopForeground(false)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    // ==================== 服务器生命周期 ====================

    @Synchronized
    private fun startServer(): Boolean {
        stopServer()
        return try {
            val s = McpServer(McpConfig.bindAddress(), McpConfig.port)
            // 读超时给足：一次全量内存扫描可能持续数十秒，
            // 这里的超时只作用于读取请求本身，但设小了在慢客户端上会误断。
            s.start(60_000, false)
            server = s
            lastError = null
            Log.i(
                TAG,
                "✅ MCP 服务已启动 ${McpConfig.bindAddress() ?: "0.0.0.0"}:${McpConfig.port}" +
                        "（只读模式=${McpConfig.readOnly}）"
            )
            true
        } catch (e: Exception) {
            lastError = when {
                e.message?.contains("EADDRINUSE", true) == true ||
                        e.message?.contains("Address already in use", true) == true ->
                    "端口 ${McpConfig.port} 已被占用，请在设置里换一个端口"

                else -> "${e.javaClass.simpleName}: ${e.message}"
            }
            Log.e(TAG, "MCP 服务启动失败: $lastError", e)
            server = null
            false
        }
    }

    @Synchronized
    private fun stopServer() {
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止 MCP 服务出错: ${e.message}")
        }
        server = null
    }

    // ==================== 通知 ====================

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "GG-AI MCP 服务", NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "对外提供内存操作的 MCP 接口"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val title = if (McpConfig.readOnly) "GG-AI MCP（只读）" else "GG-AI MCP（可写）"
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "更新通知失败: ${e.message}")
        }
    }
}
