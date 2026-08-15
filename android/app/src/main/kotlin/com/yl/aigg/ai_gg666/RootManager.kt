package com.yl.aigg.ai_gg666

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Root 权限管理器
 * 使用持久化 su shell，只建立一次连接，避免重复弹窗
 *
 * 注意：executeCommandInternal 是「写命令 + 读到标记为止」的有状态往返，
 * 必须串行。旧版没有任何同步，单线程 UI 下侥幸没暴露；MCP 服务器会并发调用
 * （进程列表、内存段解析等），不加锁会让两个命令的输出互相串进对方的结果里。
 */
object RootManager {

    private val shellLock = ReentrantLock()

    @Volatile
    private var hasRootAccess: Boolean? = null
    private var suProcess: Process? = null
    private var suOutputStream: DataOutputStream? = null
    private var suReader: BufferedReader? = null

    /**
     * 检查并请求 Root 权限
     * 只在第一次调用时触发 Magisk 授权弹窗
     */
    fun checkRootAccess(): Boolean {
        if (hasRootAccess == true) return true
        return shellLock.withLock { initSuShellLocked() }
    }

    /**
     * 初始化 su shell（只执行一次）
     */
    private fun initSuShellLocked(): Boolean {
        if (hasRootAccess == true) return true

        try {
            suProcess = Runtime.getRuntime().exec("su")
            suOutputStream = DataOutputStream(suProcess!!.outputStream)
            suReader = BufferedReader(InputStreamReader(suProcess!!.inputStream))

            // 测试 root 权限
            val result = executeCommandInternalLocked("id")
            hasRootAccess = result?.contains("uid=0") == true

            if (!hasRootAccess!!) {
                closeSuShellLocked()
            }

            return hasRootAccess!!
        } catch (e: Exception) {
            hasRootAccess = false
            closeSuShellLocked()
            return false
        }
    }

    /**
     * 请求 Root 权限
     */
    fun requestRootAccess(): Boolean {
        return checkRootAccess()
    }

    /**
     * 执行 root 命令（使用持久化 shell，串行）
     */
    fun executeRootCommand(command: String): String? = shellLock.withLock {
        if (hasRootAccess != true) {
            if (!initSuShellLocked()) return@withLock null
        }
        executeCommandInternalLocked(command)
    }

    /**
     * 内部命令执行。调用方必须已持有 shellLock。
     */
    private fun executeCommandInternalLocked(command: String): String? {
        try {
            val os = suOutputStream ?: return null
            val reader = suReader ?: return null

            // 使用唯一标记分隔输出
            val marker = "CMD_DONE_${System.nanoTime()}"

            os.writeBytes("$command\n")
            os.writeBytes("echo $marker\n")
            os.flush()

            val output = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.contains(marker)) break
                output.appendLine(line)
            }

            return output.toString().trim()
        } catch (e: Exception) {
            // 连接断开，重新初始化
            closeSuShellLocked()
            return null
        }
    }

    /**
     * 关闭 su shell。调用方必须已持有 shellLock。
     */
    private fun closeSuShellLocked() {
        try {
            suOutputStream?.writeBytes("exit\n")
            suOutputStream?.flush()
            suOutputStream?.close()
            suReader?.close()
            suProcess?.destroy()
        } catch (_: Exception) {}
        suProcess = null
        suOutputStream = null
        suReader = null
    }

    /**
     * 获取当前 Root 状态
     */
    fun getRootStatus(): String {
        return when (hasRootAccess) {
            true -> "已获取 Root 权限"
            false -> "未获取 Root 权限"
            null -> "未检测"
        }
    }

    /**
     * 重置 Root 状态
     */
    fun resetRootStatus() = shellLock.withLock {
        closeSuShellLocked()
        hasRootAccess = null
    }

    /**
     * 通过 Root Shell 批量读取多个内存块（优化版）
     * 一次 su 调用读取多个地址
     */
    fun batchReadMemoryViaRoot(pid: Int, requests: List<Pair<Long, Int>>): List<ByteArray?> {
        if (requests.isEmpty()) return emptyList()
        
        try {
            // 构建批量读取命令
            val commands = requests.mapIndexed { index, (address, size) ->
                "dd if=/proc/$pid/mem bs=1 skip=$address count=$size 2>/dev/null | xxd -p > /data/local/tmp/mem_$index.hex"
            }
            
            // 一次性执行所有命令
            val batchCmd = commands.joinToString(" && ")
            executeRootCommand(batchCmd)
            
            // 读取所有结果
            return requests.indices.map { index ->
                val hexResult = executeRootCommand("cat /data/local/tmp/mem_$index.hex 2>/dev/null")
                if (hexResult.isNullOrEmpty()) {
                    null
                } else {
                    val hexClean = hexResult.replace("\\s".toRegex(), "")
                    val bytes = ByteArray(hexClean.length / 2)
                    for (i in bytes.indices) {
                        val hex = hexClean.substring(i * 2, i * 2 + 2)
                        bytes[i] = hex.toInt(16).toByte()
                    }
                    bytes
                }
            }.also {
                // 清理临时文件
                executeRootCommand("rm -f /data/local/tmp/mem_*.hex 2>/dev/null")
            }
        } catch (e: Exception) {
            android.util.Log.e("RootManager", "batchReadMemoryViaRoot failed: ${e.message}")
            return List(requests.size) { null }
        }
    }

    /**
     * 通过 Root Shell 读取内存（优化版：直接读取，不经过临时文件）
     * 返回二进制数据
     */
    fun readMemoryViaRoot(pid: Int, address: Long, size: Int): ByteArray? {
        try {
            // 使用 xxd 读取并转为十六进制
            val cmd = "dd if=/proc/$pid/mem bs=1 skip=$address count=$size 2>/dev/null | xxd -p"
            val hexResult = executeRootCommand(cmd) ?: return null
            
            if (hexResult.isEmpty()) return null
            
            // 移除所有空白字符
            val hexClean = hexResult.replace("\\s".toRegex(), "")
            
            if (hexClean.isEmpty()) return null
            
            // 将十六进制字符串转为字节数组
            val bytes = ByteArray(hexClean.length / 2)
            for (i in bytes.indices) {
                val hex = hexClean.substring(i * 2, i * 2 + 2)
                bytes[i] = hex.toInt(16).toByte()
            }
            
            return bytes
        } catch (e: Exception) {
            android.util.Log.e("RootManager", "readMemoryViaRoot failed: ${e.message}")
            return null
        }
    }

    /**
     * 通过 Root Shell 写入内存（优化版：使用 xxd）
     */
    fun writeMemoryViaRoot(pid: Int, address: Long, data: ByteArray): Boolean {
        try {
            // 将字节数组转为十六进制字符串
            val hexString = data.joinToString("") { "%02x".format(it) }
            
            // 通过 xxd 解码并写入
            val cmd = "echo '$hexString' | xxd -r -p | dd of=/proc/$pid/mem bs=1 seek=$address count=${data.size} 2>/dev/null"
            val result = executeRootCommand(cmd)
            
            return result != null
        } catch (e: Exception) {
            android.util.Log.e("RootManager", "writeMemoryViaRoot failed: ${e.message}")
            return false
        }
    }
}
