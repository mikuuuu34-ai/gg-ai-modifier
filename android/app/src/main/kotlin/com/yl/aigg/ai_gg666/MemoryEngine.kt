package com.yl.aigg.ai_gg666

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 内存引擎 v21 —— 结果集驻留扫描器版
 *
 * 与旧版的根本差别：搜索结果不再整体搬进 JVM。扫描器进程持有结果集，
 * 这里只保存 setId 和计数，需要展示时按页取。由此消除了旧版两个瓶颈：
 *   - 逐条 enrichWithMachineCode + saveSnapshot 造成的 2N 次 IPC 往返
 *   - 把百万地址塞进一行 JSON 再由 readLine() 读回
 *
 * 另外给附加状态加了锁：MCP 服务器会并发调用，attachedPid / activeRegions /
 * 当前结果集必须整体一致，否则会出现「用 A 进程的段去搜 B 进程」这类错乱。
 */
object MemoryEngine {

    private const val TAG = "MemoryEngine"

    /** UI 一次最多展示多少条（完整结果仍在扫描器里） */
    private const val UI_PAGE_SIZE = 500

    private val stateLock = ReentrantLock()

    private var attachedPid: Int? = null
    private var activeRegions: List<MemRegion> = emptyList()
    private var appContext: Context? = null

    // UI 流程的「当前结果集」
    private var currentSetId: Int = -1
    private var currentType: String = "dword"
    private var currentCount: Int = 0
    private var currentTruncated: Boolean = false

    private val aobDatabase = mutableMapOf<Long, AobSignature>()

    @Volatile
    private var lastError: String? = null

    fun getLastError(): String? = lastError ?: RootScanner.getLastError()

    fun isNativeAvailable(): Boolean = true

    fun setContext(context: Context) {
        appContext = context
    }

    data class MemRegion(val startAddr: Long, val endAddr: Long, val priority: Int)

    data class AobSignature(
        val address: Long,
        val pattern: String,
        val contextBytes: ByteArray,
        val contextOffset: Int
    )

    /** 搜索/过滤的结果描述，供 MCP 层使用 */
    data class SearchOutcome(
        val setId: Int,
        val count: Int,
        val truncated: Boolean,
        val elapsedMs: Long,
        val type: String
    )

    // ==================== 进程管理 ====================

    fun attachProcess(pid: Int): Boolean = stateLock.withLock {
        try {
            if (!RootManager.checkRootAccess()) {
                lastError = "未获取 Root 权限"
                Log.e(TAG, lastError!!)
                return@withLock false
            }

            val ctx = appContext
            if (ctx == null) {
                lastError = "MemoryEngine 未设置 Context"
                Log.e(TAG, lastError!!)
                return@withLock false
            }

            // 切换目标前清掉旧扫描器和旧结果集，避免串到别的进程
            if (attachedPid != null && attachedPid != pid) {
                runBlocking { RootScanner.clearSet(-1) }
            }

            val ready = runBlocking { RootScanner.initialize(ctx) }
            if (!ready) {
                lastError = RootScanner.getLastError() ?: "扫描器启动失败"
                Log.e(TAG, lastError!!)
                return@withLock false
            }

            val regions = getRegions(pid)
            if (regions.isEmpty()) {
                lastError = "进程 $pid 没有可扫描的内存段（进程可能已退出）"
                Log.e(TAG, lastError!!)
                return@withLock false
            }

            activeRegions = regions
            attachedPid = pid
            currentSetId = -1
            currentCount = 0
            currentTruncated = false
            aobDatabase.clear()
            lastError = null

            val totalMB = regions.sumOf { it.endAddr - it.startAddr } / 1024 / 1024
            Log.i(TAG, "✅ 已附加进程 $pid（${regions.size} 段，${totalMB}MB）")
            true
        } catch (e: Exception) {
            lastError = "附加进程失败: ${e.message}"
            Log.e(TAG, lastError!!, e)
            false
        }
    }

    fun detachProcess() = stateLock.withLock {
        attachedPid = null
        activeRegions = emptyList()
        currentSetId = -1
        currentCount = 0
        aobDatabase.clear()
        RootScanner.shutdown()
    }

    fun getAttachedPid(): Int? = stateLock.withLock { attachedPid }

    fun getActiveRegions(): List<MemRegion> = stateLock.withLock { activeRegions }

    // ==================== 内存段 ====================

    private fun getRegions(pid: Int): List<MemRegion> {
        val mapsResult = RootManager.executeRootCommand("cat /proc/$pid/maps 2>/dev/null")
            ?: return emptyList()

        val regions = mutableListOf<MemRegion>()
        for (line in mapsResult.lines()) {
            if (line.isBlank()) continue
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 2) continue
            val addrRange = parts[0].split("-")
            if (addrRange.size != 2) continue

            val startAddr = addrRange[0].toLongOrNull(16) ?: continue
            val endAddr = addrRange[1].toLongOrNull(16) ?: continue
            val permissions = parts[1]
            val name = if (parts.size > 5) parts.subList(5, parts.size).joinToString(" ") else ""
            val regionSize = endAddr - startAddr

            if (regionSize <= 0) continue
            if (!permissions.contains('r') || !permissions.contains('w')) continue
            if (name.contains("/dev/ashmem") || name.contains("[anon:vulkan]")) continue
            if (regionSize > 100 * 1024 * 1024) continue

            var priority = 0
            if (permissions.contains('r')) priority += 10
            if (permissions.contains('w')) priority += 20
            when {
                name.contains("[heap]") -> priority += 70
                name.contains("[anon:") -> priority += 50
                name.isEmpty() -> priority += 40
            }

            regions.add(MemRegion(startAddr, endAddr, priority))
        }

        return regions.sortedByDescending { it.priority }
    }

    fun getMemoryRegions(): List<Map<String, Any>> = stateLock.withLock {
        activeRegions.map { r ->
            mapOf(
                "startAddress" to r.startAddr,
                "endAddress" to r.endAddr,
                "size" to (r.endAddr - r.startAddr),
                "priority" to r.priority
            )
        }
    }

    // ==================== 面向 MCP 的结果集接口 ====================

    fun searchExactSet(value: Any, type: String, limit: Int = 0, align: Int = 0): SearchOutcome? =
        stateLock.withLock {
            val pid = requirePid() ?: return@withLock null
            val hex = valueToHex(value, type)
            if (hex == null) {
                lastError = "无法把 $value 解释为 $type"
                return@withLock null
            }
            val r = runBlocking {
                RootScanner.searchExact(pid, activeRegions, type, hex, limit, align)
            }
            if (r == null) {
                lastError = RootScanner.getLastError()
                return@withLock null
            }
            adopt(r, type)
        }

    fun searchRangeSet(low: Double, high: Double, type: String, limit: Int = 0, align: Int = 0): SearchOutcome? =
        stateLock.withLock {
            val pid = requirePid() ?: return@withLock null
            val r = runBlocking {
                RootScanner.searchRange(pid, activeRegions, type, low, high, limit, align)
            }
            if (r == null) {
                lastError = RootScanner.getLastError()
                return@withLock null
            }
            adopt(r, type)
        }

    fun searchAobSet(pattern: String, mask: String? = null, limit: Int = 0): SearchOutcome? =
        stateLock.withLock {
            val pid = requirePid() ?: return@withLock null
            val (patternBytes, maskBytes) = parseAobPattern(pattern)
            if (patternBytes.isEmpty()) {
                lastError = "特征码为空或格式非法"
                return@withLock null
            }
            val finalMask = if (mask != null) {
                ByteArray(patternBytes.size) { i ->
                    if (i < mask.length && mask[i] == '?') 0 else maskBytes[i]
                }
            } else maskBytes

            val r = runBlocking {
                RootScanner.searchAob(
                    pid, activeRegions,
                    bytesToHex(patternBytes), bytesToHex(finalMask), limit
                )
            }
            if (r == null) {
                lastError = RootScanner.getLastError()
                return@withLock null
            }
            adopt(r, "byte")
        }

    fun refineValueSet(setId: Int, value: Any, type: String): SearchOutcome? = stateLock.withLock {
        val pid = requirePid() ?: return@withLock null
        val hex = valueToHex(value, type)
        if (hex == null) {
            lastError = "无法把 $value 解释为 $type"
            return@withLock null
        }
        val r = runBlocking { RootScanner.refineValue(pid, setId, type, hex) }
        if (r == null) {
            lastError = RootScanner.getLastError()
            return@withLock null
        }
        adopt(r, type)
    }

    fun refineRangeSet(setId: Int, low: Double, high: Double, type: String): SearchOutcome? =
        stateLock.withLock {
            val pid = requirePid() ?: return@withLock null
            val r = runBlocking { RootScanner.refineRange(pid, setId, type, low, high) }
            if (r == null) {
                lastError = RootScanner.getLastError()
                return@withLock null
            }
            adopt(r, type)
        }

    /** comparison: changed / unchanged / increased / decreased */
    fun refineFuzzySet(setId: Int, comparison: String): SearchOutcome? = stateLock.withLock {
        val pid = requirePid() ?: return@withLock null
        val mode = fuzzyMode(comparison)
        if (mode == null) {
            lastError = "未知的比较方式 $comparison（可用 changed/unchanged/increased/decreased）"
            return@withLock null
        }
        val r = runBlocking { RootScanner.refineFuzzy(pid, setId, mode) }
        if (r == null) {
            lastError = RootScanner.getLastError()
            return@withLock null
        }
        adopt(r, currentType)
    }

    fun snapshotSet(setId: Int): Int? = stateLock.withLock {
        val pid = requirePid() ?: return@withLock null
        runBlocking { RootScanner.snapshot(pid, setId) }
    }

    /** 取一页结果，转成 UI/MCP 通用的 Map 结构 */
    fun getResultPage(
        setId: Int,
        offset: Int,
        limit: Int,
        withMachineCode: Boolean = true
    ): List<Map<String, Any>> = stateLock.withLock {
        val pid = requirePid() ?: return@withLock emptyList()
        val page = runBlocking { RootScanner.getResults(pid, setId, offset, limit, withMachineCode) }
            ?: return@withLock emptyList<Map<String, Any>>().also { lastError = RootScanner.getLastError() }
        page.items.map { item ->
            val v = item.valueBytes?.let { bytesToValue(it, page.type) } ?: 0
            createResultMap(item.address, v, page.type, item.machineCode)
        }
    }

    fun getSetTotal(setId: Int): Int = stateLock.withLock {
        val pid = requirePid() ?: return@withLock 0
        runBlocking { RootScanner.getResults(pid, setId, 0, 1, false) }?.total ?: 0
    }

    fun listSets(): List<RootScanner.SetInfo> = runBlocking { RootScanner.listSets() }

    fun clearSet(setId: Int): Boolean = stateLock.withLock {
        val okResult = runBlocking { RootScanner.clearSet(setId) }
        if (okResult && (setId < 0 || setId == currentSetId)) {
            currentSetId = -1
            currentCount = 0
        }
        okResult
    }

    fun getCurrentSet(): SearchOutcome? = stateLock.withLock {
        if (currentSetId < 0) null
        else SearchOutcome(currentSetId, currentCount, currentTruncated, 0, currentType)
    }

    private fun requirePid(): Int? {
        val pid = attachedPid
        if (pid == null) lastError = "尚未附加到任何进程，请先调用 attach"
        return pid
    }

    private fun adopt(r: RootScanner.SearchResult, type: String): SearchOutcome {
        currentSetId = r.setId
        currentType = type
        currentCount = r.count
        currentTruncated = r.truncated
        lastError = null
        return SearchOutcome(r.setId, r.count, r.truncated, r.elapsedMs, type)
    }

    private fun fuzzyMode(comparison: String): Int? = when (comparison.lowercase()) {
        "changed" -> 0
        "unchanged" -> 1
        "increased" -> 2
        "decreased" -> 3
        else -> null
    }

    // ==================== 兼容旧 UI 的同步接口 ====================
    // 这些方法返回首页结果（最多 UI_PAGE_SIZE 条），完整结果集仍留在扫描器里。

    fun searchExact(value: Any, type: String): List<Map<String, Any>> {
        val outcome = searchExactSet(value, type) ?: return emptyList()
        return getResultPage(outcome.setId, 0, UI_PAGE_SIZE)
    }

    fun searchByRange(minValue: Long, maxValue: Long, type: String): List<Map<String, Any>> {
        val outcome = searchRangeSet(minValue.toDouble(), maxValue.toDouble(), type) ?: return emptyList()
        return getResultPage(outcome.setId, 0, UI_PAGE_SIZE)
    }

    fun searchAob(pattern: String, mask: String? = null): List<Map<String, Any>> {
        // 兼容旧行为：传入的是纯地址时，直接读该地址的多种类型值
        val addrLong = parseAddress(pattern)
        if (addrLong != null) return readAddressValues(addrLong)

        val outcome = searchAobSet(pattern, mask) ?: return emptyList()
        val page = getResultPage(outcome.setId, 0, UI_PAGE_SIZE)

        // 记录特征码上下文，供后续重定位
        val pid = getAttachedPid()
        if (pid != null) {
            for (row in page) {
                val addr = row["addressInt"] as? Long ?: continue
                val ctx = runBlocking { RootScanner.readMemory(pid, addr - 16, 48) }
                if (ctx != null) aobDatabase[addr] = AobSignature(addr, pattern, ctx, 16)
            }
        }
        return page.map { it + ("type" to "aob") }
    }

    /**
     * 在给定地址集合里过滤出等于 value 的项。
     * 走 read_many 批量读，不再像旧版那样每个地址一次 IPC。
     */
    fun filterResults(previousAddresses: List<Long>, value: Any, type: String): List<Map<String, Any>> {
        val pid = getAttachedPid() ?: return emptyList()
        val target = valueToBytes(value, type) ?: return emptyList()
        val typeSize = getTypeSize(type)

        val hits = mutableListOf<Map<String, Any>>()
        previousAddresses.chunked(4000).forEach { chunk ->
            val values = runBlocking { RootScanner.readMany(pid, chunk, typeSize) }
            chunk.forEachIndexed { i, addr ->
                val bytes = values.getOrNull(i) ?: return@forEachIndexed
                if (bytes.contentEquals(target)) {
                    hits.add(createResultMap(addr, value, type, null))
                }
            }
        }
        return hits
    }

    fun searchFuzzy(comparison: String, type: String): List<Map<String, Any>> {
        val existing = getCurrentSet()
        val outcome = if (existing == null) {
            // 首次模糊搜索：先用类型全量程建立候选集
            val (lo, hi) = fullRangeOf(type)
            searchRangeSet(lo, hi, type)
        } else {
            refineFuzzySet(existing.setId, comparison)
        } ?: return emptyList()
        return getResultPage(outcome.setId, 0, UI_PAGE_SIZE)
    }

    /**
     * 用记录的上下文字节重新定位特征码。
     * 游戏重启后基址变化，但特征码周围的字节模式不变。
     */
    fun relocateAobSignatures(): List<Map<String, Any>> {
        val pid = getAttachedPid() ?: return emptyList()
        val snapshot = stateLock.withLock { aobDatabase.toMap() }
        if (snapshot.isEmpty()) return emptyList()

        val results = mutableListOf<Map<String, Any>>()
        for ((oldAddress, sig) in snapshot) {
            // 上下文按精确字节匹配，parseAobPattern 会给出全 1 掩码
            val patternText = bytesToHex(sig.contextBytes, " ")
            val outcome = searchAobSet(patternText, null, 8) ?: continue
            val page = runBlocking { RootScanner.getResults(pid, outcome.setId, 0, 1, false) } ?: continue
            val first = page.items.firstOrNull() ?: continue

            val newAddr = first.address + sig.contextOffset
            val mc = runBlocking { RootScanner.readMemory(pid, newAddr, 8) }
            val valBytes = runBlocking { RootScanner.readMemory(pid, newAddr, 4) }
            results.add(
                createResultMap(
                    newAddr,
                    valBytes?.let { bytesToValue(it, "dword") } ?: 0,
                    "aob",
                    mc?.let { bytesToHex(it, " ") }
                ) + mapOf(
                    "relocated" to true,
                    "oldAddress" to "0x${oldAddress.toString(16).uppercase()}"
                )
            )
        }
        return results
    }

    // ==================== 读写 ====================

    fun readMemory(address: Long, type: String): Any? {
        val pid = getAttachedPid() ?: return null
        return try {
            val bytes = runBlocking { RootScanner.readMemory(pid, address, getTypeSize(type)) } ?: return null
            bytesToValue(bytes, type)
        } catch (e: Exception) {
            Log.e(TAG, "readMemory 失败: ${e.message}")
            null
        }
    }

    fun readMemory(address: Int, type: String): Any? = readMemory(address.toLong(), type)

    /** 读原始字节，供 hex dump 使用 */
    fun readBytes(address: Long, size: Int): ByteArray? {
        val pid = getAttachedPid() ?: return null
        return runBlocking { RootScanner.readMemory(pid, address, size) }
    }

    fun writeMemory(address: Long, value: Any, type: String): Boolean {
        val pid = getAttachedPid() ?: return false
        return try {
            val bytes = valueToBytes(value, type) ?: return false
            runBlocking { RootScanner.writeMemory(pid, address, bytes) }
        } catch (e: Exception) {
            Log.e(TAG, "writeMemory 失败: ${e.message}")
            false
        }
    }

    fun writeMemory(address: Int, value: Any, type: String): Boolean =
        writeMemory(address.toLong(), value, type)

    fun writeBatch(requests: List<Map<String, Any>>): Boolean {
        var ok = true
        for (req in requests) {
            val addr = (req["address"] as? Number)?.toLong() ?: continue
            val v = req["value"] ?: continue
            val t = req["type"] as? String ?: "dword"
            if (!writeMemory(addr, v, t)) ok = false
        }
        return ok
    }

    fun analyzeMemoryRegion(address: Long, range: Int): Map<String, Any> {
        val pid = getAttachedPid() ?: return emptyMap()
        return try {
            val start = (address - range).coerceAtLeast(0)
            val data = runBlocking { RootScanner.readMemory(pid, start, range * 2) } ?: return emptyMap()
            mapOf(
                "address" to address,
                "range" to range,
                "data" to bytesToHex(data),
                "size" to data.size
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun analyzeMemoryRegion(address: Int, range: Int): Map<String, Any> =
        analyzeMemoryRegion(address.toLong(), range)

    private fun readAddressValues(address: Long): List<Map<String, Any>> {
        val pid = getAttachedPid() ?: return emptyList()
        val mc = runBlocking { RootScanner.readMemory(pid, address, 8) }
        val mcStr = mc?.let { bytesToHex(it, " ") }

        val results = mutableListOf<Map<String, Any>>()
        for ((type, size) in listOf("dword" to 4, "float" to 4, "double" to 8, "word" to 2, "byte" to 1)) {
            try {
                val bytes = runBlocking { RootScanner.readMemory(pid, address, size) } ?: continue
                val value = bytesToValue(bytes, type) ?: continue
                results.add(createResultMap(address, value, type, mcStr))
            } catch (_: Exception) {
            }
        }
        return results
    }

    // ==================== 工具函数 ====================

    private fun parseAddress(input: String): Long? {
        val s = input.trim()
        return when {
            s.startsWith("0x", ignoreCase = true) -> s.substring(2).toLongOrNull(16)
            s.length >= 6 && s.all { it.isDigit() || it in "abcdefABCDEF" } -> s.toLongOrNull(16)
            else -> null
        }
    }

    /** 解析 AOB 特征码，返回 (pattern, mask)，mask 1=精确 0=通配 */
    fun parseAobPattern(input: String): Pair<ByteArray, ByteArray> {
        var raw = input.trim()
        if (raw.startsWith("0x", ignoreCase = true)) raw = raw.substring(2)

        val tokens: List<String> = if (raw.contains(" ")) {
            raw.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        } else {
            val result = mutableListOf<String>()
            var j = 0
            while (j < raw.length) {
                if (j + 1 < raw.length && raw[j] == '?' && raw[j + 1] == '?') {
                    result.add("??"); j += 2
                } else if (j + 1 < raw.length) {
                    result.add(raw.substring(j, j + 2)); j += 2
                } else j++
            }
            result
        }

        val patternBytes = mutableListOf<Byte>()
        val maskBytes = mutableListOf<Byte>()
        for (token in tokens) {
            if (token == "??" || token == "?") {
                patternBytes.add(0); maskBytes.add(0)
            } else {
                val b = token.toIntOrNull(16) ?: return Pair(ByteArray(0), ByteArray(0))
                patternBytes.add(b.toByte()); maskBytes.add(1)
            }
        }
        return Pair(patternBytes.toByteArray(), maskBytes.toByteArray())
    }

    fun getTypeSize(type: String): Int = when (type) {
        "byte" -> 1; "word" -> 2; "dword" -> 4
        "qword" -> 8; "float" -> 4; "double" -> 8
        else -> 4
    }

    fun isValidType(type: String): Boolean =
        type in setOf("byte", "word", "dword", "qword", "float", "double")

    private fun fullRangeOf(type: String): Pair<Double, Double> = when (type) {
        "byte" -> -128.0 to 127.0
        "word" -> -32768.0 to 32767.0
        "dword" -> Int.MIN_VALUE.toDouble() to Int.MAX_VALUE.toDouble()
        "qword" -> Long.MIN_VALUE.toDouble() to Long.MAX_VALUE.toDouble()
        else -> -3.4e38 to 3.4e38
    }

    fun valueToBytes(value: Any, type: String): ByteArray? {
        return try {
            val num = value as? Number ?: (value as? String)?.let {
                if (type == "float" || type == "double") it.toDoubleOrNull() else it.toLongOrNull()
            } ?: return null
            when (type) {
                "byte" -> byteArrayOf(num.toInt().toByte())
                "word" -> ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(num.toShort()).array()
                "dword" -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(num.toInt()).array()
                "qword" -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(num.toLong()).array()
                "float" -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(num.toFloat()).array()
                "double" -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(num.toDouble()).array()
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun valueToHex(value: Any, type: String): String? = valueToBytes(value, type)?.let { bytesToHex(it) }

    fun bytesToValue(bytes: ByteArray, type: String): Any? {
        return try {
            if (bytes.size < getTypeSize(type)) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            when (type) {
                "byte" -> bytes[0].toInt()
                "word" -> buf.short.toInt()
                "dword" -> buf.int
                "qword" -> buf.long
                "float" -> buf.float
                "double" -> buf.double
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun bytesToHex(bytes: ByteArray, separator: String = ""): String =
        bytes.joinToString(separator) { "%02x".format(it) }

    private fun createResultMap(
        address: Long,
        value: Any,
        type: String,
        machineCode: String?
    ): MutableMap<String, Any> {
        val m = mutableMapOf<String, Any>(
            "address" to "0x${address.toString(16).uppercase()}",
            "addressInt" to address,
            "value" to value,
            "type" to type,
            "isFavorite" to false,
            "isFrozen" to MemoryFreezer.isFrozen(address)
        )
        if (machineCode != null) m["machineCode"] = machineCode.uppercase()
        return m
    }
}
