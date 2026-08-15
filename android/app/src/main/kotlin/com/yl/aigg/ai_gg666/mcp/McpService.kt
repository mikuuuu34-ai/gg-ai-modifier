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
        const val ACTION_RESTART = "com.yl.aigg.mcp.RESTART"

        /**
         * 静态锁。不能用 @Synchronized —— 那锁的是 Service 实例，
         * 而重启时新旧实例会同时存在，锁不同对象等于没锁，
         * 会出现「新实例刚绑好端口、旧实例的 onDestroy 把它停掉」这类竞态。
         */
        private val serverLock = Any()

        @Volatile
        private var server: McpServer? = null

        @Volatile
        private var lastError: String? = null

        fun isRunning(): Boolean = server?.wasStarted() == true

        fun getLastError(): String? = lastError

        private fun send(context: Context, action: String) {
            val intent = Intent(context, McpService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun start(context: Context) = send(context, ACTION_START)

        fun restart(context: Context) = send(context, ACTION_RESTART)

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
                // 停止动作可能阻塞（见 stopServerLocked），不能占着主线程
                Thread {
                    synchronized(serverLock) { stopServerLocked() }
                }.also { it.isDaemon = true }.start()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }

            // ACTION_START 与 ACTION_RESTART 走同一条路：
            // startServerLocked 内部会先停掉旧实例，重启即「再启动一次」
            else -> {
                // startForeground 必须在主线程上尽快调用，否则会被系统判定为超时
                startForeground(NOTIFICATION_ID, buildNotification("正在启动…"))
                // 绑定端口与停旧实例都可能阻塞，放后台做
                Thread {
                    val ok = synchronized(serverLock) { startServerLocked() }
                    if (ok) {
                        updateNotification("监听 ${McpConfig.localEndpoint()}")
                    } else {
                        // 启动失败就别赖着不走，否则留下一条撤不掉的失败通知
                        Log.e(TAG, "启动失败，服务退出: $lastError")
                        stopForeground(true)
                        stopSelf()
                    }
                }.also { it.isDaemon = true }.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // 注意：不在这里停服务器。重启时新实例可能已经绑好端口，
        // 旧实例的 onDestroy 再去 stop 会把新的一起停掉。
        // 停止统一由 ACTION_STOP 处理。
        super.onDestroy()
    }

    // ==================== 服务器生命周期 ====================

    /** 调用方必须持有 serverLock */
    private fun startServerLocked(): Boolean {
        stopServerLocked()

        // 端口刚被释放时可能仍处于关闭中，首次绑定失败重试一次
        repeat(2) { attempt ->
            try {
                val s = McpServer(McpConfig.bindAddress(), McpConfig.port)
                // 这里的超时只作用于读取请求本身；一次全量扫描可能持续数十秒，
                // 但那发生在响应阶段，不受此值影响。
                s.start(60_000, false)
                server = s
                lastError = null
                Log.i(
                    TAG,
                    "✅ MCP 服务已启动 ${McpConfig.bindAddress() ?: "0.0.0.0"}:${McpConfig.port}" +
                            "（只读模式=${McpConfig.readOnly}）"
                )
                return true
            } catch (e: Exception) {
                val inUse = e.message?.contains("EADDRINUSE", true) == true ||
                        e.message?.contains("Address already in use", true) == true
                lastError = if (inUse) {
                    "端口 ${McpConfig.port} 已被占用，请在设置里换一个端口"
                } else {
                    "${e.javaClass.simpleName}: ${e.message}"
                }
                Log.e(TAG, "MCP 服务启动失败(第 ${attempt + 1} 次): $lastError")
                server = null
                if (attempt == 0) {
                    try { Thread.sleep(600) } catch (_: InterruptedException) { return false }
                }
            }
        }
        return false
    }

    /** 调用方必须持有 serverLock */
    private fun stopServerLocked() {
        val s = server ?: return
        server = null
        // NanoHTTPD.stop() 内部会 join accept 线程。若那个线程卡住，join 会一直等下去，
        // 之前正是这一点在主线程上把服务拖死（socket 停在 LISTEN 但无人 accept）。
        // 这里限时等待，超时就放弃，至少不拖垮调用方。
        val t = Thread {
            try {
                s.stop()
            } catch (e: Exception) {
                Log.w(TAG, "停止 MCP 服务出错: ${e.message}")
            }
        }
        t.isDaemon = true
        t.start()
        try {
            t.join(3000)
        } catch (_: InterruptedException) {
        }
        if (t.isAlive) Log.w(TAG, "NanoHTTPD.stop() 超过 3s 未返回，已放弃等待")
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
