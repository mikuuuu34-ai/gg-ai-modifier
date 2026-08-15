package com.yl.aigg.ai_gg666

import java.util.concurrent.ConcurrentHashMap

/**
 * 内存冻结器
 * 后台线程持续写入目标值，防止游戏自动修改数据
 *
 * 两处修正：
 * - 地址改用 Long。原先是 Int，arm64 上游戏堆地址普遍超过 2^31
 *   （形如 0x7x_xxxx_xxxx），截断后会写到完全无关的地方。
 * - 改用 ConcurrentHashMap。冻结线程在遍历时，UI 或 MCP 线程随时可能
 *   增删条目，普通 mutableMap 会抛 ConcurrentModificationException。
 */
object MemoryFreezer {

    private val frozenAddresses = ConcurrentHashMap<Long, Map<String, Any>>()

    @Volatile
    private var freezeThread: Thread? = null

    @Volatile
    private var isRunning = false

    /**
     * 冻结内存地址
     */
    fun freeze(address: Long, value: Any, type: String): Boolean {
        frozenAddresses[address] = mapOf(
            "address" to address,
            "addressHex" to "0x${address.toString(16).uppercase()}",
            "value" to value,
            "type" to type
        )
        startFreezingIfNeeded()
        return true
    }

    /**
     * 解除冻结
     */
    fun unfreeze(address: Long): Boolean {
        frozenAddresses.remove(address)
        if (frozenAddresses.isEmpty()) {
            stopFreezing()
        }
        return true
    }

    /**
     * 解除全部冻结
     */
    fun unfreezeAll(): Int {
        val n = frozenAddresses.size
        frozenAddresses.clear()
        stopFreezing()
        return n
    }

    fun isFrozen(address: Long): Boolean = frozenAddresses.containsKey(address)

    /**
     * 获取所有冻结地址
     */
    fun getFrozenAddresses(): List<Map<String, Any>> {
        return frozenAddresses.values.toList()
    }

    /**
     * 启动冻结守护线程
     */
    @Synchronized
    private fun startFreezingIfNeeded() {
        if (isRunning) return

        isRunning = true
        val t = Thread {
            while (isRunning && frozenAddresses.isNotEmpty()) {
                // ConcurrentHashMap 的迭代器是弱一致的，遍历期间增删不会抛异常
                for ((address, info) in frozenAddresses) {
                    val value = info["value"] ?: continue
                    val type = info["type"] as? String ?: "dword"
                    try {
                        MemoryEngine.writeMemory(address, value, type)
                    } catch (e: Exception) {
                        // 写入失败，跳过
                    }
                }
                try {
                    Thread.sleep(100) // 每 100ms 写入一次
                } catch (e: InterruptedException) {
                    break
                }
            }
            isRunning = false
        }
        t.isDaemon = true
        freezeThread = t
        t.start()
    }

    /**
     * 停止冻结
     */
    @Synchronized
    private fun stopFreezing() {
        isRunning = false
        freezeThread?.interrupt()
        freezeThread = null
    }
}
