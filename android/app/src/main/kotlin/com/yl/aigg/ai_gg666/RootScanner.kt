package com.yl.aigg.ai_gg666

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Root Scanner —— 与 scanner_root (协议 v2) 的 JSON 行式通道
 *
 * 相比旧版的三处关键变化：
 *
 * 1. 串行化：sendCommand 全程持有 ioLock。旧版「写一行读一行」没有任何同步，
 *    单线程 UI 下侥幸没暴露，MCP 服务器一旦并发就会串包（A 请求拿到 B 的响应）。
 * 2. 读取超时：响应由独立线程泵进队列，poll 超时即判定扫描器失联并自动重启，
 *    不再像旧版那样 readLine() 无限阻塞。
 * 3. 结果集留在扫描器进程里：搜索只回 setId/count，按页取结果，
 *    不再把百万地址塞进一行 JSON。
 */
object RootScanner {

    private const val TAG = "RootScanner"
    private const val SCANNER_NAME = "scanner_root"

    /** 单条命令最长等待。大范围扫描可能耗时数十秒，留足余量 */
    private const val DEFAULT_TIMEOUT_MS = 180_000L
    private const val QUICK_TIMEOUT_MS = 15_000L

    private val ioLock = ReentrantLock()

    private var scannerProcess: Process? = null
    private var scannerWriter: BufferedWriter? = null
    private var responses: LinkedBlockingQueue<String>? = null
    private var pumpThread: Thread? = null
    private var scannerPath: String? = null

    @Volatile
    private var lastError: String? = null

    fun getLastError(): String? = lastError

    fun isRunning(): Boolean = ioLock.withLock { scannerProcess != null }

    // ==================== 数据结构 ====================

    data class SearchResult(
        val setId: Int,
        val count: Int,
        val truncated: Boolean,
        val elapsedMs: Long
    )

    data class ResultItem(
        val address: Long,
        val valueBytes: ByteArray?,
        val machineCode: String?
    )

    data class ResultPage(
        val setId: Int,
        val total: Int,
        val offset: Int,
        val type: String,
        val items: List<ResultItem>
    )

    data class SetInfo(val id: Int, val count: Int, val type: String)

    // ==================== 生命周期 ====================

    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        ioLock.withLock {
            if (scannerProcess != null && pingLocked() != null) return@withLock true
            stopLocked()
            startLocked(context)
        }
    }

    private fun startLocked(context: Context): Boolean {
        try {
            val path = scannerPath ?: extractScanner(context)
            if (path == null) {
                lastError = "无法释放 scanner_root 可执行文件"
                Log.e(TAG, lastError!!)
                return false
            }
            scannerPath = path

            val su = Runtime.getRuntime().exec("su")
            val writer = BufferedWriter(OutputStreamWriter(su.outputStream))
            writer.write("chmod 755 $path\n")
            writer.write("exec $path\n")   // exec 替换 shell，避免多留一层进程
            writer.flush()

            val queue = LinkedBlockingQueue<String>()
            val reader = BufferedReader(InputStreamReader(su.inputStream))
            val pump = Thread {
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) queue.put(line)
                    }
                } catch (_: Exception) {
                    // 进程结束或流关闭，正常退出
                }
            }
            pump.isDaemon = true
            pump.start()

            scannerProcess = su
            scannerWriter = writer
            responses = queue
            pumpThread = pump

            // 握手：确认拿到的是 v2 协议的扫描器，而不是 su 的横幅或旧版二进制
            val pong = pingLocked()
            if (pong == null) {
                lastError = "scanner_root 启动后无响应（root 未授权或二进制不可执行）"
                Log.e(TAG, lastError!!)
                stopLocked()
                return false
            }
            val proto = pong.optInt("proto", 0)
            if (proto != 2) {
                lastError = "scanner_root 协议版本不匹配：期望 2，实际 $proto。APK 内可能是旧的预编译二进制"
                Log.e(TAG, lastError!!)
                stopLocked()
                return false
            }

            Log.i(TAG, "✅ Root Scanner 就绪 @ $path (${pong.optString("version")})")
            return true
        } catch (e: Exception) {
            lastError = "启动 scanner_root 失败: ${e.message}"
            Log.e(TAG, lastError!!, e)
            stopLocked()
            return false
        }
    }

    /** 握手用：吞掉 su 可能打印的非 JSON 前导行 */
    private fun pingLocked(): JSONObject? {
        val w = scannerWriter ?: return null
        val q = responses ?: return null
        return try {
            w.write("{\"cmd\":\"ping\"}\n")
            w.flush()
            val deadline = System.currentTimeMillis() + QUICK_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val line = q.poll(QUICK_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: return null
                val obj = try { JSONObject(line) } catch (_: Exception) { continue }
                if (obj.has("proto")) return obj
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun stopLocked() {
        try { scannerWriter?.close() } catch (_: Exception) {}
        try { scannerProcess?.destroy() } catch (_: Exception) {}
        pumpThread?.interrupt()
        scannerProcess = null
        scannerWriter = null
        responses = null
        pumpThread = null
    }

    fun shutdown() = ioLock.withLock { stopLocked() }

    // ==================== 通道 ====================

    /**
     * 发一条命令收一行响应。全程持锁，超时即重启扫描器。
     * 返回 null 表示通道故障（原因见 getLastError）。
     */
    private fun sendLocked(json: String, timeoutMs: Long): JSONObject? {
        val w = scannerWriter ?: run {
            lastError = "scanner 未启动"
            return null
        }
        val q = responses ?: run {
            lastError = "scanner 未启动"
            return null
        }

        try {
            w.write(json)
            w.write("\n")
            w.flush()
        } catch (e: IOException) {
            lastError = "scanner 通道写入失败: ${e.message}"
            Log.e(TAG, lastError!!)
            stopLocked()
            return null
        }

        val line = q.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (line == null) {
            // 超时后即便迟到的响应也已与请求错位，只能重启，不能继续用
            lastError = "scanner ${timeoutMs}ms 未响应，已重启通道"
            Log.e(TAG, lastError!!)
            stopLocked()
            return null
        }

        return try {
            JSONObject(line)
        } catch (e: Exception) {
            lastError = "scanner 返回非 JSON: ${line.take(200)}"
            Log.e(TAG, lastError!!)
            null
        }
    }

    private suspend fun send(json: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): JSONObject? =
        withContext(Dispatchers.IO) { ioLock.withLock { sendLocked(json, timeoutMs) } }

    /** 检查响应状态；非 ok 时把 msg 记进 lastError 并返回 null */
    private fun ok(obj: JSONObject?): JSONObject? {
        if (obj == null) return null
        if (obj.optString("status") != "ok") {
            lastError = obj.optString("msg", "scanner 返回未知错误")
            return null
        }
        return obj
    }

    private fun regionsJson(regions: List<MemoryEngine.MemRegion>): String =
        regions.joinToString(",") {
            "{\"start\":${it.startAddr},\"size\":${it.endAddr - it.startAddr}}"
        }

    private fun toSearchResult(obj: JSONObject): SearchResult = SearchResult(
        setId = obj.optInt("set", -1),
        count = obj.optInt("count", 0),
        truncated = obj.optBoolean("truncated", false),
        elapsedMs = obj.optLong("elapsed_ms", 0)
    )

    // ==================== 搜索 ====================

    suspend fun searchExact(
        pid: Int,
        regions: List<MemoryEngine.MemRegion>,
        type: String,
        targetHex: String,
        limit: Int = 0,
        align: Int = 0
    ): SearchResult? {
        val sb = StringBuilder()
        sb.append("{\"cmd\":\"search_exact\",\"pid\":").append(pid)
        sb.append(",\"type\":\"").append(type).append('"')
        sb.append(",\"target\":\"").append(targetHex).append('"')
        if (limit > 0) sb.append(",\"limit\":").append(limit)
        if (align > 0) sb.append(",\"align\":").append(align)
        sb.append(",\"regions\":[").append(regionsJson(regions)).append("]}")
        return ok(send(sb.toString()))?.let { toSearchResult(it) }
    }

    suspend fun searchRange(
        pid: Int,
        regions: List<MemoryEngine.MemRegion>,
        type: String,
        low: Double,
        high: Double,
        limit: Int = 0,
        align: Int = 0
    ): SearchResult? {
        val sb = StringBuilder()
        sb.append("{\"cmd\":\"search_range\",\"pid\":").append(pid)
        sb.append(",\"type\":\"").append(type).append('"')
        sb.append(",\"low\":").append(low)
        sb.append(",\"high\":").append(high)
        if (limit > 0) sb.append(",\"limit\":").append(limit)
        if (align > 0) sb.append(",\"align\":").append(align)
        sb.append(",\"regions\":[").append(regionsJson(regions)).append("]}")
        return ok(send(sb.toString()))?.let { toSearchResult(it) }
    }

    suspend fun searchAob(
        pid: Int,
        regions: List<MemoryEngine.MemRegion>,
        patternHex: String,
        maskHex: String,
        limit: Int = 0
    ): SearchResult? {
        val sb = StringBuilder()
        sb.append("{\"cmd\":\"search_aob\",\"pid\":").append(pid)
        sb.append(",\"pattern\":\"").append(patternHex).append('"')
        sb.append(",\"mask\":\"").append(maskHex).append('"')
        if (limit > 0) sb.append(",\"limit\":").append(limit)
        sb.append(",\"regions\":[").append(regionsJson(regions)).append("]}")
        return ok(send(sb.toString()))?.let { toSearchResult(it) }
    }

    // ==================== 二次过滤 ====================

    suspend fun refineValue(pid: Int, setId: Int, type: String, targetHex: String): SearchResult? {
        val json = "{\"cmd\":\"refine_value\",\"pid\":$pid,\"set\":$setId," +
                "\"type\":\"$type\",\"target\":\"$targetHex\"}"
        return ok(send(json))?.let { toSearchResult(it) }
    }

    suspend fun refineRange(pid: Int, setId: Int, type: String, low: Double, high: Double): SearchResult? {
        val json = "{\"cmd\":\"refine_range\",\"pid\":$pid,\"set\":$setId," +
                "\"type\":\"$type\",\"low\":$low,\"high\":$high}"
        return ok(send(json))?.let { toSearchResult(it) }
    }

    /** mode: 0=changed 1=unchanged 2=increased 3=decreased */
    suspend fun refineFuzzy(pid: Int, setId: Int, mode: Int): SearchResult? {
        val json = "{\"cmd\":\"refine_fuzzy\",\"pid\":$pid,\"set\":$setId,\"mode\":$mode}"
        return ok(send(json))?.let { toSearchResult(it) }
    }

    suspend fun snapshot(pid: Int, setId: Int): Int? {
        val json = "{\"cmd\":\"snapshot\",\"pid\":$pid,\"set\":$setId}"
        return ok(send(json))?.optInt("count", 0)
    }

    // ==================== 结果读取 ====================

    suspend fun getResults(
        pid: Int,
        setId: Int,
        offset: Int,
        limit: Int,
        withMachineCode: Boolean = false
    ): ResultPage? {
        val json = "{\"cmd\":\"get_results\",\"pid\":$pid,\"set\":$setId," +
                "\"offset\":$offset,\"limit\":$limit,\"mc\":${if (withMachineCode) 1 else 0}}"
        val obj = ok(send(json)) ?: return null

        val arr = obj.optJSONArray("items")
        val items = ArrayList<ResultItem>(arr?.length() ?: 0)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val addr = it.optString("a").toLongOrNull(16) ?: continue
                val vHex = it.optString("v", "")
                items.add(
                    ResultItem(
                        address = addr,
                        valueBytes = if (vHex.isEmpty()) null else hexToBytes(vHex),
                        machineCode = it.optString("m", "").ifEmpty { null }
                    )
                )
            }
        }
        return ResultPage(
            setId = obj.optInt("set", setId),
            total = obj.optInt("count", 0),
            offset = obj.optInt("offset", offset),
            type = obj.optString("type", "dword"),
            items = items
        )
    }

    suspend fun listSets(): List<SetInfo> {
        val obj = ok(send("{\"cmd\":\"list_sets\"}", QUICK_TIMEOUT_MS)) ?: return emptyList()
        val arr = obj.optJSONArray("sets") ?: return emptyList()
        val out = ArrayList<SetInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(SetInfo(o.optInt("id"), o.optInt("count"), o.optString("type", "dword")))
        }
        return out
    }

    /** setId 传 -1 清空全部 */
    suspend fun clearSet(setId: Int): Boolean =
        ok(send("{\"cmd\":\"clear_set\",\"set\":$setId}", QUICK_TIMEOUT_MS)) != null

    // ==================== 读写 ====================

    suspend fun readMemory(pid: Int, address: Long, size: Int): ByteArray? {
        val json = "{\"cmd\":\"read\",\"pid\":$pid,\"addr\":$address,\"size\":$size}"
        val obj = ok(send(json, QUICK_TIMEOUT_MS)) ?: return null
        val hex = obj.optString("data", "")
        return if (hex.isEmpty()) null else hexToBytes(hex)
    }

    /** 一次读多个等长地址，替代旧版逐地址往返 */
    suspend fun readMany(pid: Int, addresses: List<Long>, size: Int): List<ByteArray?> {
        if (addresses.isEmpty()) return emptyList()
        val sb = StringBuilder()
        sb.append("{\"cmd\":\"read_many\",\"pid\":").append(pid)
        sb.append(",\"size\":").append(size).append(",\"addrs\":[")
        addresses.forEachIndexed { i, a ->
            if (i > 0) sb.append(',')
            sb.append('"').append(java.lang.Long.toHexString(a)).append('"')
        }
        sb.append("]}")

        val obj = ok(send(sb.toString())) ?: return List(addresses.size) { null }
        val arr = obj.optJSONArray("data") ?: return List(addresses.size) { null }
        return (0 until arr.length()).map { i ->
            val hex = arr.optString(i, "")
            if (hex.isEmpty()) null else hexToBytes(hex)
        }
    }

    suspend fun writeMemory(pid: Int, address: Long, data: ByteArray): Boolean {
        val json = "{\"cmd\":\"write\",\"pid\":$pid,\"addr\":$address," +
                "\"data\":\"${bytesToHex(data)}\"}"
        return ok(send(json, QUICK_TIMEOUT_MS)) != null
    }

    // ==================== 工具 ====================

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) {
                ((Character.digit(hex[it * 2], 16) shl 4) or
                        Character.digit(hex[it * 2 + 1], 16)).toByte()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }

    // ==================== 可执行文件释放 ====================

    private fun extractScanner(context: Context): String? {
        val dest = File("/data/local/tmp", SCANNER_NAME)
        val temp = File(context.cacheDir, SCANNER_NAME)
        try {
            val libDir = context.applicationInfo.nativeLibraryDir

            // 1. nativeLibraryDir（jniLibs 打包会被重命名成 lib*.so）
            var src = File(libDir, SCANNER_NAME)
            if (!src.exists()) src = File(libDir, "lib$SCANNER_NAME.so")
            if (src.exists()) {
                RootManager.executeRootCommand(
                    "cp ${src.absolutePath} ${dest.absolutePath} && chmod 755 ${dest.absolutePath}"
                )
                Log.i(TAG, "scanner 来自 nativeLibraryDir: ${src.name}")
                return dest.absolutePath
            }

            // 2. assets
            val abi = android.os.Build.SUPPORTED_ABIS[0]
            val assetPath = "native/$abi/$SCANNER_NAME"
            try {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }
                RootManager.executeRootCommand(
                    "cp ${temp.absolutePath} ${dest.absolutePath} && chmod 755 ${dest.absolutePath}"
                )
                temp.delete()
                Log.i(TAG, "scanner 来自 assets/$assetPath")
                return dest.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "assets 释放失败 $assetPath: ${e.message}")
            }

            // 3. 直接从 APK zip 读（绕过 AssetManager 压缩策略）
            try {
                java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { zip ->
                    val entry = zip.getEntry("assets/$assetPath")
                    if (entry != null) {
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(temp).use { output -> input.copyTo(output) }
                        }
                        RootManager.executeRootCommand(
                            "cp ${temp.absolutePath} ${dest.absolutePath} && chmod 755 ${dest.absolutePath}"
                        )
                        temp.delete()
                        Log.i(TAG, "scanner 来自 APK zip")
                        return dest.absolutePath
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "APK zip 释放失败: ${e.message}")
            }

            Log.e(TAG, "所有位置都找不到 $SCANNER_NAME")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "extractScanner 失败: ${e.message}", e)
            return null
        }
    }
}
