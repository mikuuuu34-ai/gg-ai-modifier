package com.yl.aigg.ai_gg666

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * LuaJ GG API 桥接层
 * 在 JVM 中执行 Lua 脚本，提供与 GG 修改器兼容的交互式 API
 *
 * 两处为 MCP 补的能力：
 * - headless 模式：MCP 调用时没有人在看屏幕，gg.choice/prompt/alert 若照常
 *   弹窗并 CountDownLatch.await()，请求线程会永久卡住。headless 下这些调用
 *   立即返回默认值，并在输出日志里注明被跳过，让 agent 知道脚本走了哪条分支。
 * - 执行串行化：searchResults / outputLog 是对象级可变状态，且每次执行都会
 *   clear()，并发调用会互相清空对方的结果。
 */
object LuaEngine {

    /** 有界面时的交互等待上限，避免脚本无限期挂着 */
    private const val DIALOG_TIMEOUT_SEC = 180L

    /** headless 下 gg.sleep 的单次上限，防止脚本长时间占住通道 */
    private const val HEADLESS_MAX_SLEEP_MS = 10_000L

    private val execLock = ReentrantLock()

    private var context: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var searchResults = mutableListOf<Map<String, Any>>()
    private var frozenList = mutableListOf<Map<String, Any>>()
    private val outputLog = StringBuilder()

    @Volatile
    private var headless = false

    fun setContext(ctx: Context?) {
        context = ctx
    }

    fun setActivity(act: android.app.Activity?) {
        context = act
    }

    /**
     * @param headlessMode true 表示无人值守（MCP 调用）：交互式 API 不弹窗，
     *                     直接返回默认值并记录在输出里。
     */
    fun executeScript(scriptContent: String, headlessMode: Boolean = false): String =
        execLock.withLock {
            headless = headlessMode
            outputLog.clear()
            searchResults.clear()
            frozenList.clear()

            try {
                val globals = JsePlatform.standardGlobals()
                val gg = LuaTable()
                registerGgApi(gg)
                globals.set("gg", gg)
                val chunk = globals.load(scriptContent)
                chunk.call()
                outputLog.toString()
            } catch (e: Exception) {
                val errorMsg = "Lua 执行错误: ${e.message}"
                outputLog.appendLine(errorMsg)
                outputLog.toString()
            } finally {
                headless = false
            }
        }

    private fun getOverlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun showDialog(dialog: AlertDialog) {
        val ctx = context
        if (ctx != null && ctx !is android.app.Activity) {
            dialog.window?.setType(getOverlayType())
        }
        dialog.show()
    }

    private fun showChoiceDialog(title: String, items: List<String>): Int {
        if (headless) {
            outputLog.appendLine("  → [无人值守] 未弹窗，按取消处理")
            return -1
        }
        val latch = CountDownLatch(1)
        val selectedIndex = AtomicInteger(-1)
        val ctx = context ?: return -1

        mainHandler.post {
            try {
                val dialog = AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setItems(items.toTypedArray()) { _, which ->
                        selectedIndex.set(which + 1)
                        latch.countDown()
                    }
                    .setCancelable(false)
                    .setNegativeButton("取消") { _, _ ->
                        selectedIndex.set(-1)
                        latch.countDown()
                    }
                    .create()
                showDialog(dialog)
            } catch (e: Exception) {
                outputLog.appendLine("⚠️ 对话框显示失败: ${e.message}")
                selectedIndex.set(-1)
                latch.countDown()
            }
        }

        if (!latch.await(DIALOG_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            outputLog.appendLine("⚠️ 等待选择超时，按取消处理")
            return -1
        }
        return selectedIndex.get()
    }

    private fun showInputDialog(title: String, defaultValue: String): String {
        if (headless) {
            outputLog.appendLine("  → [无人值守] 未弹输入框，采用默认值 \"$defaultValue\"")
            return defaultValue
        }
        val latch = CountDownLatch(1)
        val inputResult = AtomicReference(defaultValue)
        val ctx = context ?: return defaultValue

        mainHandler.post {
            try {
                val editText = android.widget.EditText(ctx).apply {
                    setText(defaultValue)
                    setPadding(50, 30, 50, 30)
                }
                val dialog = AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setView(editText)
                    .setPositiveButton("确定") { _, _ ->
                        inputResult.set(editText.text.toString())
                        latch.countDown()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        inputResult.set(defaultValue)
                        latch.countDown()
                    }
                    .setCancelable(false)
                    .create()
                showDialog(dialog)
            } catch (e: Exception) {
                outputLog.appendLine("⚠️ 输入框显示失败: ${e.message}")
                inputResult.set(defaultValue)
                latch.countDown()
            }
        }

        if (!latch.await(DIALOG_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            outputLog.appendLine("⚠️ 等待输入超时，采用默认值")
            return defaultValue
        }
        return inputResult.get()
    }

    private fun showConfirmDialog(title: String, message: String): Boolean {
        if (headless) {
            outputLog.appendLine("  → [无人值守] 未弹确认框，按确定处理")
            return true
        }
        val latch = CountDownLatch(1)
        val confirmed = AtomicInteger(0)
        val ctx = context ?: return false

        mainHandler.post {
            try {
                val dialog = AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ ->
                        confirmed.set(1)
                        latch.countDown()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        confirmed.set(0)
                        latch.countDown()
                    }
                    .setCancelable(false)
                    .create()
                showDialog(dialog)
            } catch (e: Exception) {
                confirmed.set(0)
                latch.countDown()
            }
        }

        if (!latch.await(DIALOG_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            outputLog.appendLine("⚠️ 等待确认超时，按取消处理")
            return false
        }
        return confirmed.get() == 1
    }

    private fun luaTypeToDataType(type: Int): String {
        return when (type) {
            1 -> "byte"
            2 -> "word"
            4 -> "dword"
            8 -> "qword"
            16 -> "float"
            32 -> "double"
            else -> "dword"
        }
    }

    private fun dataTypeToLuaType(type: String): Int {
        return when (type) {
            "byte" -> 1
            "word" -> 2
            "dword" -> 4
            "qword" -> 8
            "float" -> 16
            "double" -> 32
            else -> 4
        }
    }

    private fun registerGgApi(gg: LuaTable) {
        // gg.toast
        gg.set("toast", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val msg = arg.tojstring()
                outputLog.appendLine("📢 $msg")
                mainHandler.post {
                    try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                }
                return LuaValue.NIL
            }
        })

        // gg.alert
        gg.set("alert", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val msg = arg.tojstring()
                outputLog.appendLine("⚠️ $msg")
                showConfirmDialog("提示", msg)
                return LuaValue.NIL
            }
        })

        // gg.prompt
        gg.set("prompt", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val msg = args.arg(1).tojstring()
                var defaultValue = ""
                if (args.narg() >= 2 && args.arg(2).istable()) {
                    val table = args.arg(2).checktable()
                    if (table.length() > 0) defaultValue = table.get(1).tojstring()
                }
                val result = showInputDialog(msg, defaultValue)
                outputLog.appendLine("📝 $msg → $result")
                val resultTable = LuaTable()
                resultTable.set(1, LuaValue.valueOf(result))
                return resultTable
            }
        })

        // gg.choice
        gg.set("choice", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val items = mutableListOf<String>()
                var title = "选择"
                if (args.arg(1).istable()) {
                    val table = args.arg(1).checktable()
                    for (i in 1..table.length()) items.add(table.get(i).tojstring())
                }
                if (args.narg() >= 3 && !args.arg(3).isnil()) {
                    title = args.arg(3).tojstring()
                } else if (args.narg() >= 2 && args.arg(2).isstring()) {
                    val second = args.arg(2)
                    if (second.isstring() && !second.isnumber()) title = second.tojstring()
                }
                if (items.isEmpty()) return LuaValue.NIL

                outputLog.appendLine("📋 $title")
                for (i in items.indices) outputLog.appendLine("  ${i + 1}. ${items[i]}")

                val selected = showChoiceDialog(title, items)
                if (selected > 0) {
                    outputLog.appendLine("  → 选择了: ${items[selected - 1]}")
                } else {
                    outputLog.appendLine("  → 已取消")
                }
                return if (selected > 0) LuaValue.valueOf(selected) else LuaValue.NIL
            }
        })

        // gg.searchNumber
        gg.set("searchNumber", object : TwoArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
                val value = arg1.tojstring()
                val type = luaTypeToDataType(arg2.toint())
                val numValue: Any = when (type) {
                    "float", "double" -> value.toDoubleOrNull() ?: 0.0
                    else -> value.toLongOrNull() ?: 0
                }
                val results = MemoryEngine.searchExact(numValue, type)
                searchResults.clear()
                searchResults.addAll(results)
                outputLog.appendLine("🔍 搜索 $value ($type): 找到 ${results.size} 个结果")
                return LuaValue.valueOf(results.size)
            }
        })

        // gg.refineNumber
        gg.set("refineNumber", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val value = arg.tojstring()
                val prevAddresses = searchResults.map { (it["addressInt"] as Number).toLong() }
                val numValue: Any = value.toDoubleOrNull() ?: value.toLongOrNull() ?: 0
                val type = searchResults.firstOrNull()?.get("type") as? String ?: "dword"
                val results = MemoryEngine.filterResults(prevAddresses, numValue, type)
                searchResults.clear()
                searchResults.addAll(results)
                outputLog.appendLine("🔍 过滤后: ${results.size} 个结果")
                return LuaValue.valueOf(results.size)
            }
        })

        // gg.getResultsCount / gg.getResultCount
        val getResultsCountFunc = object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(searchResults.size)
        }
        gg.set("getResultsCount", getResultsCountFunc)
        gg.set("getResultCount", getResultsCountFunc)

        // gg.getResults
        gg.set("getResults", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val count = arg.toint()
                val table = LuaTable()
                val takeCount = minOf(count, searchResults.size)
                for (i in 0 until takeCount) {
                    val result = searchResults[i]
                    val item = LuaTable()
                    item.set("address", LuaValue.valueOf(result["address"] as String))
                    item.set("value", LuaValue.valueOf((result["value"] as? Number)?.toDouble() ?: 0.0))
                    item.set("flags", LuaValue.valueOf(dataTypeToLuaType(result["type"] as String)))
                    table.set(i + 1, item)
                }
                return table
            }
        })

        // gg.setValues
        gg.set("setValues", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                if (!arg.istable()) return LuaValue.valueOf(0)
                val table = arg.checktable()
                var count = 0
                for (i in 1..table.length()) {
                    val item = table.get(i)
                    if (item.istable()) {
                        val itemTable = item.checktable()
                        val addr = itemTable.get("address")
                        val value = itemTable.get("value")
                        val flags = itemTable.get("flags")
                        if (!addr.isnil() && !value.isnil()) {
                            val address = addr.tojstring().toLongOrNull(16) ?: continue
                            val type = luaTypeToDataType(flags.toint())
                            val numValue: Any = when (type) {
                                "float", "double" -> value.todouble()
                                else -> value.tolong()
                            }
                            if (MemoryEngine.writeMemory(address, numValue, type)) count++
                        }
                    }
                }
                outputLog.appendLine("✏️ 已修改 $count 个地址")
                return LuaValue.valueOf(count)
            }
        })

        // gg.writeMemory
        gg.set("writeMemory", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val address = args.arg(1).tojstring().toLongOrNull(16) ?: return LuaValue.valueOf(false)
                val value = args.arg(2)
                val type = luaTypeToDataType(args.arg(3).toint())
                val numValue: Any = when (type) {
                    "float", "double" -> value.todouble()
                    else -> value.tolong()
                }
                val success = MemoryEngine.writeMemory(address, numValue, type)
                if (success) outputLog.appendLine("✏️ 写入 0x${address.toString(16).uppercase()} = $numValue")
                return LuaValue.valueOf(success)
            }
        })

        // gg.freeze
        gg.set("freeze", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val address = args.arg(1).tojstring().toLongOrNull(16) ?: return LuaValue.valueOf(false)
                val value = args.arg(2)
                val type = luaTypeToDataType(args.arg(3).toint())
                val numValue: Any = when (type) {
                    "float", "double" -> value.todouble()
                    else -> value.tolong()
                }
                val success = MemoryFreezer.freeze(address, numValue, type)
                if (success) outputLog.appendLine("🔒 冻结 0x${address.toString(16).uppercase()} = $numValue")
                return LuaValue.valueOf(success)
            }
        })

        // gg.addListItems
        gg.set("addListItems", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                if (!arg.istable()) return LuaValue.valueOf(0)
                val table = arg.checktable()
                var count = 0
                for (i in 1..table.length()) {
                    val item = table.get(i)
                    if (item.istable()) {
                        val itemTable = item.checktable()
                        val freeze = itemTable.get("freeze")
                        if (freeze.toboolean()) {
                            val address = itemTable.get("address").tojstring().toLongOrNull(16) ?: continue
                            val value = itemTable.get("value")
                            val flags = itemTable.get("flags")
                            val type = luaTypeToDataType(flags.toint())
                            val numValue: Any = when (type) {
                                "float", "double" -> value.todouble()
                                else -> value.tolong()
                            }
                            if (MemoryFreezer.freeze(address, numValue, type)) count++
                        }
                    }
                }
                outputLog.appendLine("🔒 已冻结 $count 个地址")
                return LuaValue.valueOf(count)
            }
        })

        // gg.clearResults
        gg.set("clearResults", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue { searchResults.clear(); return LuaValue.NIL }
        })

        // gg.clearList
        gg.set("clearList", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue { frozenList.clear(); return LuaValue.NIL }
        })

        // gg.sleep
        gg.set("sleep", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                var ms = arg.tolong()
                if (headless && ms > HEADLESS_MAX_SLEEP_MS) {
                    outputLog.appendLine("  → [无人值守] sleep($ms) 收敛为 ${HEADLESS_MAX_SLEEP_MS}ms")
                    ms = HEADLESS_MAX_SLEEP_MS
                }
                try { Thread.sleep(ms) } catch (_: Exception) {}
                return LuaValue.NIL
            }
        })

        // Constants
        gg.set("TYPE_BYTE", LuaValue.valueOf(1))
        gg.set("TYPE_WORD", LuaValue.valueOf(2))
        gg.set("TYPE_DWORD", LuaValue.valueOf(4))
        gg.set("TYPE_QWORD", LuaValue.valueOf(8))
        gg.set("TYPE_FLOAT", LuaValue.valueOf(16))
        gg.set("TYPE_DOUBLE", LuaValue.valueOf(32))
    }
}
