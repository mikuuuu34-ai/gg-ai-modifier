package com.yl.aigg.ai_gg666.mcp

import android.content.Context
import com.yl.aigg.ai_gg666.LuaEngine
import com.yl.aigg.ai_gg666.MemoryEngine
import com.yl.aigg.ai_gg666.MemoryFreezer
import com.yl.aigg.ai_gg666.ProcessManager
import com.yl.aigg.ai_gg666.RootManager
import com.yl.aigg.ai_gg666.RootScanner
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 工具面。
 *
 * 两条贯穿始终的设计约束：
 *
 * 1. 任何可能返回大量数据的工具一律分页。搜索**从不**把地址列表交给客户端，
 *    只回 set_id + 计数 + 少量样本；完整结果留在扫描器进程里，
 *    需要时用 gg_list_results 按页取。一次搜索几十万个地址是常态，
 *    灌进上下文会直接废掉对话。
 * 2. 每个返回都带 next_step，告诉 agent 下一步该调什么。
 *    内存搜索是多轮收敛过程，给出路径比给出数据更重要。
 */
object McpTools {

    data class ToolResult(val text: String, val isError: Boolean = false)

    private const val MAX_PAGE = 200
    private const val SAMPLE_SIZE = 8

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val VALUE_TYPES = listOf("byte", "word", "dword", "qword", "float", "double")

    // ==================== 工具定义 ====================

    private fun p(type: String, desc: String): JSONObject =
        JSONObject().put("type", type).put("description", desc)

    private fun pEnum(values: List<String>, desc: String, default: String? = null): JSONObject {
        val o = JSONObject().put("type", "string").put("description", desc)
            .put("enum", JSONArray(values))
        if (default != null) o.put("default", default)
        return o
    }

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", desc)
            .put(
                "inputSchema",
                JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", JSONArray(required))
            )

    private val ADDRESS_DESC =
        "内存地址。可以是十六进制字符串（\"0x7f1234abcd\" 或 \"7f1234abcd\"）或十进制整数。"

    fun definitions(): JSONArray {
        val tools = JSONArray()

        tools.put(
            tool(
                "gg_status",
                "查看服务与目标状态：root 是否可用、当前附加的进程、可扫描内存段数量、" +
                        "只读模式是否开启、当前结果集。不需要 root 也能调用，可用来确认链路是否打通。",
                JSONObject(), emptyList()
            )
        )

        tools.put(
            tool(
                "gg_list_processes",
                "列出设备上正在运行的应用进程。需要 root。先用这个找到目标游戏的 pid 或包名。",
                JSONObject()
                    .put("filter", p("string", "按包名或应用名做子串过滤，不区分大小写"))
                    .put("include_system", p("boolean", "是否包含系统进程，默认 false"))
                    .put("limit", p("integer", "最多返回多少条，默认 60")),
                emptyList()
            )
        )

        tools.put(
            tool(
                "gg_attach",
                "附加到目标进程，解析其可读写内存段。后续所有搜索和读写都作用于这个进程。" +
                        "pid 与 package 提供其一即可。",
                JSONObject()
                    .put("pid", p("integer", "目标进程 pid"))
                    .put("package", p("string", "目标应用包名，会自动解析成 pid")),
                emptyList()
            )
        )

        tools.put(
            tool(
                "gg_detach",
                "解除对当前进程的附加，释放扫描器与全部结果集。" +
                        "换目标游戏、或目标进程已退出导致读取持续失败时调用。",
                JSONObject(), emptyList()
            )
        )

        tools.put(
            tool(
                "gg_list_regions",
                "列出当前进程的可扫描内存段，按优先级排序（[heap] 与 [anon:*] 优先）。" +
                        "用于判断搜索范围是否合理。",
                JSONObject()
                    .put("offset", p("integer", "起始下标，默认 0"))
                    .put("limit", p("integer", "返回条数，默认 40，最大 200")),
                emptyList()
            )
        )

        tools.put(
            tool(
                "gg_search",
                "在目标进程内存中搜索，建立一个结果集并返回 set_id。" +
                        "**不会返回完整地址列表**——命中数常达数十万，只回计数和少量样本。" +
                        "拿到 set_id 后，用 gg_refine 收敛，收敛到几十条以内再用 gg_list_results 查看。\n" +
                        "典型流程：搜当前数值 → 回到游戏让该数值变化 → gg_refine 按新值过滤 → 重复直到剩下少数几个。\n" +
                        "若目标数值未知，用 mode=\"range\" 先圈一个合理区间（例如血量 0~10000），" +
                        "再配合 gg_refine 的 fuzzy 模式按增减收敛，比全量模糊搜索高效得多。",
                JSONObject()
                    .put(
                        "mode",
                        pEnum(
                            listOf("exact", "range", "aob"),
                            "exact=精确数值；range=数值区间；aob=字节特征码", "exact"
                        )
                    )
                    .put("type", pEnum(VALUE_TYPES, "数据类型，默认 dword", "dword"))
                    .put("value", p("string", "mode=exact 时要搜索的数值"))
                    .put("low", p("string", "mode=range 时的下界（含）"))
                    .put("high", p("string", "mode=range 时的上界（含）"))
                    .put("pattern", p("string", "mode=aob 时的特征码，如 \"48 8B ?? 24\"，?? 为通配"))
                    .put("mask", p("string", "mode=aob 时的可选掩码，? 表示该位通配"))
                    .put("limit", p("integer", "命中上限，默认 100 万。达到上限会在返回里标记 truncated"))
                    .put("align", p("integer", "扫描对齐字节数，默认等于类型宽度。设为 1 可查找非对齐数据，但会慢很多")),
                emptyList()
            )
        )

        tools.put(
            tool(
                "gg_refine",
                "在已有结果集上二次过滤，产生一个新的结果集。这是收敛的主力工具。\n" +
                        "mode=value：保留当前值等于给定值的地址（最常用）。\n" +
                        "mode=range：保留当前值落在区间内的地址。\n" +
                        "mode=fuzzy：与该结果集建立时记录的快照比较，按 增大/减小/变化/不变 过滤，" +
                        "适合数值未知但知道变化方向的场景（例如受伤后血量减少）。",
                JSONObject()
                    .put("set_id", p("integer", "要过滤的结果集，省略则用当前结果集"))
                    .put("mode", pEnum(listOf("value", "range", "fuzzy"), "过滤方式", "value"))
                    .put("type", p("string", "数据类型，省略则沿用结果集自身类型"))
                    .put("value", p("string", "mode=value 时的目标数值"))
                    .put("low", p("string", "mode=range 时的下界"))
                    .put("high", p("string", "mode=range 时的上界"))
                    .put(
                        "comparison",
                        pEnum(
                            listOf("changed", "unchanged", "increased", "decreased"),
                            "mode=fuzzy 时的比较方式"
                        )
                    ),
                emptyList()
            )
        )

        tools.put(
            tool(
                "gg_list_results",
                "分页查看结果集里的地址及其当前值。结果集很大时不要盲目翻页，" +
                        "先用 gg_refine 收敛到几十条以内。",
                JSONObject()
                    .put("set_id", p("integer", "结果集 id，省略则用当前结果集"))
                    .put("offset", p("integer", "起始下标，默认 0"))
                    .put("limit", p("integer", "返回条数，默认 30，最大 $MAX_PAGE"))
                    .put("machine_code", p("boolean", "是否附带该地址处的 8 字节原始数据，默认 true")),
                emptyList()
            )
        )

        tools.put(
            tool(
                "gg_clear_results",
                "释放结果集。set_id 传 -1 清空全部。",
                JSONObject().put("set_id", p("integer", "结果集 id，-1 表示全部，默认 -1")),
                emptyList()
            )
        )

        tools.put(
            tool(
                "gg_read",
                "读取单个地址的值。不指定 type 时会按全部六种类型各解释一遍，" +
                        "便于判断该地址到底存的是什么。",
                JSONObject()
                    .put("address", p("string", ADDRESS_DESC))
                    .put("type", pEnum(VALUE_TYPES, "数据类型，省略则返回全部类型的解释")),
                listOf("address")
            )
        )

        tools.put(
            tool(
                "gg_read_many",
                "一次读取多个地址的当前值。适合同时盯住若干候选地址，观察它们在游戏里的变化。",
                JSONObject()
                    .put(
                        "addresses",
                        JSONObject().put("type", "array").put("description", "地址列表，最多 500 个")
                            .put("items", JSONObject().put("type", "string"))
                    )
                    .put("type", pEnum(VALUE_TYPES, "数据类型，默认 dword", "dword")),
                listOf("addresses")
            )
        )

        tools.put(
            tool(
                "gg_read_bytes",
                "读取一段原始字节并以十六进制 + ASCII 呈现，用于查看结构体布局或字符串。",
                JSONObject()
                    .put("address", p("string", ADDRESS_DESC))
                    .put("size", p("integer", "读取字节数，默认 64，最大 4096")),
                listOf("address")
            )
        )

        tools.put(
            tool(
                "gg_write",
                "向指定地址写入数值，并自动回读校验。返回写入前后的值。" +
                        "只读模式下会被拒绝。",
                JSONObject()
                    .put("address", p("string", ADDRESS_DESC))
                    .put("value", p("string", "要写入的数值"))
                    .put("type", pEnum(VALUE_TYPES, "数据类型，默认 dword", "dword")),
                listOf("address", "value")
            )
        )

        tools.put(
            tool(
                "gg_write_batch",
                "批量写入多个地址，逐条回读校验。只读模式下会被拒绝。",
                JSONObject().put(
                    "items",
                    JSONObject().put("type", "array")
                        .put("description", "每项形如 {\"address\":\"0x...\",\"value\":\"999\",\"type\":\"dword\"}")
                        .put(
                            "items",
                            JSONObject().put("type", "object").put(
                                "properties",
                                JSONObject()
                                    .put("address", p("string", ADDRESS_DESC))
                                    .put("value", p("string", "数值"))
                                    .put("type", p("string", "数据类型，默认 dword"))
                            )
                        )
                ),
                listOf("items")
            )
        )

        tools.put(
            tool(
                "gg_freeze",
                "冻结地址：后台线程每 100ms 把目标值写回，防止游戏改回去。" +
                        "action=list 只读，不受只读模式限制。",
                JSONObject()
                    .put("action", pEnum(listOf("add", "remove", "remove_all", "list"), "操作", "list"))
                    .put("address", p("string", ADDRESS_DESC))
                    .put("value", p("string", "action=add 时要锁定的数值"))
                    .put("type", pEnum(VALUE_TYPES, "数据类型，默认 dword", "dword")),
                emptyList()
            )
        )

        tools.put(
            tool(
                "gg_run_lua",
                "执行一段 GG 兼容的 Lua 脚本（gg.searchNumber / gg.getResults / gg.setValues 等）。" +
                        "以无人值守方式运行：gg.choice / gg.prompt / gg.alert 不会弹窗，" +
                        "而是立即返回默认值并在输出里标注，因此依赖交互分支的脚本行为会与手动执行不同。" +
                        "**注意 gg.searchNumber 最多只能看到 500 条命中**（Lua 桥接层的固有上限，" +
                        "不是真实命中数），后续 gg.refineNumber 也只在这 500 条里过滤；" +
                        "需要完整结果请改用 gg_search + gg_refine。只读模式下会被拒绝。",
                JSONObject().put("script", p("string", "Lua 脚本内容")),
                listOf("script")
            )
        )

        return tools
    }

    // ==================== 分发 ====================

    fun call(name: String, args: JSONObject): ToolResult = try {
        when (name) {
            "gg_status" -> status()
            "gg_list_processes" -> listProcesses(args)
            "gg_attach" -> attach(args)
            "gg_detach" -> detach()
            "gg_list_regions" -> listRegions(args)
            "gg_search" -> search(args)
            "gg_refine" -> refine(args)
            "gg_list_results" -> listResults(args)
            "gg_clear_results" -> clearResults(args)
            "gg_read" -> read(args)
            "gg_read_many" -> readMany(args)
            "gg_read_bytes" -> readBytes(args)
            "gg_write" -> write(args)
            "gg_write_batch" -> writeBatch(args)
            "gg_freeze" -> freeze(args)
            "gg_run_lua" -> runLua(args)
            else -> err("未知工具 $name")
        }
    } catch (e: Exception) {
        err("工具 $name 执行异常: ${e.javaClass.simpleName}: ${e.message}")
    }

    // ==================== 各工具实现 ====================

    private fun status(): ToolResult {
        val pid = MemoryEngine.getAttachedPid()
        val cur = MemoryEngine.getCurrentSet()
        val o = JSONObject()
            .put("server", "running")
            .put("read_only", McpConfig.readOnly)
            .put("root", RootManager.getRootStatus())
            .put("scanner_running", RootScanner.isRunning())
            .put("attached_pid", pid ?: JSONObject.NULL)
            .put("regions", MemoryEngine.getActiveRegions().size)
            .put("frozen_count", MemoryFreezer.getFrozenAddresses().size)

        if (cur != null) {
            o.put(
                "current_set",
                JSONObject().put("set_id", cur.setId).put("count", cur.count)
                    .put("type", cur.type).put("truncated", cur.truncated)
            )
        } else {
            o.put("current_set", JSONObject.NULL)
        }
        MemoryEngine.getLastError()?.let { o.put("last_error", it) }

        o.put(
            "next_step", when {
                pid == null -> "先调用 gg_list_processes 找到目标游戏，再用 gg_attach 附加"
                cur == null -> "已附加。用 gg_search 开始搜索"
                else -> "当前结果集 ${cur.count} 条。用 gg_refine 继续收敛，或 gg_list_results 查看"
            }
        )
        if (McpConfig.readOnly) {
            o.put("note", "只读模式开启中，gg_write / gg_freeze / gg_run_lua 会被拒绝。需在 App 的 MCP 设置里关闭。")
        }
        return okJson(o)
    }

    private fun listProcesses(args: JSONObject): ToolResult {
        val ctx = appContext ?: return err("服务未初始化")
        if (!RootManager.checkRootAccess()) return err("未获取 Root 权限，无法列出进程")

        val filter = args.optString("filter", "").lowercase()
        val includeSystem = args.optBoolean("include_system", false)
        val limit = args.optInt("limit", 60).coerceIn(1, 500)

        var list = ProcessManager.getProcessList(ctx)
        if (!includeSystem) list = list.filter { it["isSystem"] != true }
        if (filter.isNotEmpty()) {
            list = list.filter {
                (it["packageName"] as? String)?.lowercase()?.contains(filter) == true ||
                        (it["processName"] as? String)?.lowercase()?.contains(filter) == true
            }
        }

        val total = list.size
        val arr = JSONArray()
        list.take(limit).forEach {
            arr.put(
                JSONObject()
                    .put("pid", it["pid"])
                    .put("package", it["packageName"])
                    .put("name", it["processName"])
                    .put("system", it["isSystem"])
            )
        }

        return okJson(
            JSONObject()
                .put("total", total)
                .put("returned", arr.length())
                .put("processes", arr)
                .put("next_step", "用 gg_attach 附加到目标进程（传 pid 或 package）")
        )
    }

    private fun attach(args: JSONObject): ToolResult {
        val ctx = appContext ?: return err("服务未初始化")
        var pid = if (args.has("pid")) args.optInt("pid", -1) else -1
        val pkg = args.optString("package", "")

        if (pid <= 0 && pkg.isNotEmpty()) {
            if (!RootManager.checkRootAccess()) return err("未获取 Root 权限")
            val match = ProcessManager.getProcessList(ctx)
                .firstOrNull { it["packageName"] == pkg }
                ?: ProcessManager.getProcessList(ctx)
                    .firstOrNull { (it["packageName"] as? String)?.contains(pkg) == true }
            if (match == null) return err("找不到包名匹配 \"$pkg\" 的运行中进程。先用 gg_list_processes 确认目标正在运行")
            pid = (match["pid"] as? Number)?.toInt() ?: -1
        }

        if (pid <= 0) return err("需要提供 pid 或 package")

        if (!MemoryEngine.attachProcess(pid)) {
            return err(MemoryEngine.getLastError() ?: "附加进程 $pid 失败")
        }

        val regions = MemoryEngine.getActiveRegions()
        val totalMb = regions.sumOf { it.endAddr - it.startAddr } / 1024 / 1024
        return okJson(
            JSONObject()
                .put("attached_pid", pid)
                .put("regions", regions.size)
                .put("scannable_mb", totalMb)
                .put("next_step", "用 gg_search 搜索目标数值。若数值未知，先用 mode=range 圈定区间")
        )
    }

    private fun detach(): ToolResult {
        MemoryEngine.detachProcess()
        return okJson(JSONObject().put("detached", true))
    }

    private fun listRegions(args: JSONObject): ToolResult {
        val regions = MemoryEngine.getActiveRegions()
        if (regions.isEmpty()) return err("尚未附加进程，或该进程没有可扫描的内存段")

        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val limit = args.optInt("limit", 40).coerceIn(1, MAX_PAGE)
        val arr = JSONArray()
        regions.drop(offset).take(limit).forEach { r ->
            arr.put(
                JSONObject()
                    .put("start", "0x${r.startAddr.toString(16)}")
                    .put("end", "0x${r.endAddr.toString(16)}")
                    .put("size_kb", (r.endAddr - r.startAddr) / 1024)
                    .put("priority", r.priority)
            )
        }
        return okJson(
            JSONObject()
                .put("total", regions.size)
                .put("offset", offset)
                .put("regions", arr)
                .put("note", "priority 越高越可能是游戏数据所在（[heap] / [anon:*] 优先）")
        )
    }

    private fun search(args: JSONObject): ToolResult {
        if (MemoryEngine.getAttachedPid() == null) return err("尚未附加进程，先调用 gg_attach")

        val mode = args.optString("mode", "exact")
        val type = args.optString("type", "dword")
        if (mode != "aob" && !MemoryEngine.isValidType(type)) {
            return err("非法数据类型 \"$type\"，可用: ${VALUE_TYPES.joinToString("/")}")
        }
        val limit = args.optInt("limit", 0)
        val align = args.optInt("align", 0)

        val outcome = when (mode) {
            "exact" -> {
                val raw = args.opt("value") ?: return err("mode=exact 需要提供 value")
                val v = parseNumber(raw, type) ?: return err("无法把 \"$raw\" 解析为 $type")
                MemoryEngine.searchExactSet(v, type, limit, align)
            }

            "range" -> {
                val lo = parseDouble(args.opt("low")) ?: return err("mode=range 需要提供 low")
                val hi = parseDouble(args.opt("high")) ?: return err("mode=range 需要提供 high")
                if (lo > hi) return err("low 不能大于 high")
                MemoryEngine.searchRangeSet(lo, hi, type, limit, align)
            }

            "aob" -> {
                val pattern = args.optString("pattern", "")
                if (pattern.isEmpty()) return err("mode=aob 需要提供 pattern")
                val mask = args.optString("mask", "").ifEmpty { null }
                MemoryEngine.searchAobSet(pattern, mask, limit)
            }

            else -> return err("未知 mode \"$mode\"，可用 exact / range / aob")
        } ?: return err(MemoryEngine.getLastError() ?: "搜索失败")

        return okJson(searchOutcomeJson(outcome, "搜索"))
    }

    private fun refine(args: JSONObject): ToolResult {
        if (MemoryEngine.getAttachedPid() == null) return err("尚未附加进程，先调用 gg_attach")

        val current = MemoryEngine.getCurrentSet()
        val setId = if (args.has("set_id")) args.optInt("set_id") else current?.setId
            ?: return err("没有可用的结果集，先调用 gg_search")

        val mode = args.optString("mode", "value")
        val type = args.optString("type", current?.type ?: "dword")
        if (!MemoryEngine.isValidType(type)) {
            return err("非法数据类型 \"$type\"，可用: ${VALUE_TYPES.joinToString("/")}")
        }

        val outcome = when (mode) {
            "value" -> {
                val raw = args.opt("value") ?: return err("mode=value 需要提供 value")
                val v = parseNumber(raw, type) ?: return err("无法把 \"$raw\" 解析为 $type")
                MemoryEngine.refineValueSet(setId, v, type)
            }

            "range" -> {
                val lo = parseDouble(args.opt("low")) ?: return err("mode=range 需要提供 low")
                val hi = parseDouble(args.opt("high")) ?: return err("mode=range 需要提供 high")
                MemoryEngine.refineRangeSet(setId, lo, hi, type)
            }

            "fuzzy" -> {
                val cmp = args.optString("comparison", "")
                if (cmp.isEmpty()) return err("mode=fuzzy 需要提供 comparison（changed/unchanged/increased/decreased）")
                MemoryEngine.refineFuzzySet(setId, cmp)
            }

            else -> return err("未知 mode \"$mode\"，可用 value / range / fuzzy")
        } ?: return err(MemoryEngine.getLastError() ?: "过滤失败")

        return okJson(searchOutcomeJson(outcome, "过滤"))
    }

    private fun searchOutcomeJson(outcome: MemoryEngine.SearchOutcome, verb: String): JSONObject {
        val o = JSONObject()
            .put("set_id", outcome.setId)
            .put("count", outcome.count)
            .put("truncated", outcome.truncated)
            .put("elapsed_ms", outcome.elapsedMs)
            .put("type", outcome.type)

        if (outcome.count > 0) {
            o.put("sample", itemsArray(MemoryEngine.getResultPage(outcome.setId, 0, SAMPLE_SIZE, false)))
            if (outcome.count > SAMPLE_SIZE) {
                o.put("sample_note", "仅前 $SAMPLE_SIZE 条，完整结果留在扫描器进程里，用 gg_list_results 翻页")
            }
        }

        o.put(
            "next_step", when {
                outcome.count == 0 ->
                    "$verb 没有命中。检查数据类型是否正确（血量常见 dword，坐标常见 float），" +
                            "或用 gg_search mode=range 放宽条件"

                outcome.count == 1 ->
                    "只剩 1 个地址，基本可以确认。用 gg_read 核对，再用 gg_write 修改"

                outcome.count <= 30 ->
                    "已收敛到 ${outcome.count} 条，用 gg_list_results 查看全部，" +
                            "或直接 gg_write 逐个试探"

                else ->
                    "还有 ${outcome.count} 条。回到游戏让目标数值发生变化，" +
                            "再用 gg_refine（mode=value 传新值，或 mode=fuzzy 传变化方向）继续收敛"
            }
        )

        if (outcome.truncated) {
            o.put(
                "warning",
                "命中数达到上限，扫描并未覆盖全部内存，目标可能不在结果集中。" +
                        "建议缩小搜索条件，或调大 limit 重搜"
            )
        }
        return o
    }

    private fun listResults(args: JSONObject): ToolResult {
        val current = MemoryEngine.getCurrentSet()
        val setId = if (args.has("set_id")) args.optInt("set_id") else current?.setId
            ?: return err("没有可用的结果集，先调用 gg_search")

        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val limit = args.optInt("limit", 30).coerceIn(1, MAX_PAGE)
        val withMc = args.optBoolean("machine_code", true)

        val page = MemoryEngine.getResultPage(setId, offset, limit, withMc)
        val total = MemoryEngine.getSetTotal(setId)
        if (page.isEmpty() && total == 0) {
            return err(MemoryEngine.getLastError() ?: "结果集 $setId 不存在或为空")
        }

        val o = JSONObject()
            .put("set_id", setId)
            .put("total", total)
            .put("offset", offset)
            .put("items", itemsArray(page))
        if (offset + page.size < total) {
            o.put("next_step", "还有更多，用 offset=${offset + page.size} 继续翻页；" +
                    "结果太多时优先用 gg_refine 收敛而不是翻页")
        }
        return okJson(o)
    }

    private fun clearResults(args: JSONObject): ToolResult {
        val setId = args.optInt("set_id", -1)
        val ok = MemoryEngine.clearSet(setId)
        return if (ok) okJson(JSONObject().put("cleared", if (setId < 0) "all" else setId))
        else err(MemoryEngine.getLastError() ?: "清理结果集失败")
    }

    private fun read(args: JSONObject): ToolResult {
        if (MemoryEngine.getAttachedPid() == null) return err("尚未附加进程，先调用 gg_attach")
        val addr = parseAddress(args.opt("address")) ?: return err("无法解析 address")

        val type = args.optString("type", "")
        if (type.isNotEmpty()) {
            if (!MemoryEngine.isValidType(type)) return err("非法数据类型 \"$type\"")
            val v = MemoryEngine.readMemory(addr, type)
                ?: return err("读取 0x${addr.toString(16)} 失败（地址可能不可读或进程已退出）")
            return okJson(
                JSONObject()
                    .put("address", "0x${addr.toString(16)}")
                    .put("type", type)
                    .put("value", v)
            )
        }

        val values = JSONObject()
        for (t in VALUE_TYPES) {
            MemoryEngine.readMemory(addr, t)?.let { values.put(t, it) }
        }
        if (values.length() == 0) return err("读取 0x${addr.toString(16)} 失败")
        return okJson(
            JSONObject()
                .put("address", "0x${addr.toString(16)}")
                .put("values_by_type", values)
                .put("note", "同一段字节按不同类型的解释。结合游戏里显示的数值判断实际类型")
        )
    }

    private fun readMany(args: JSONObject): ToolResult {
        if (MemoryEngine.getAttachedPid() == null) return err("尚未附加进程，先调用 gg_attach")
        val arr = args.optJSONArray("addresses") ?: return err("需要提供 addresses 数组")
        if (arr.length() == 0) return err("addresses 为空")
        if (arr.length() > 500) return err("一次最多读 500 个地址，当前 ${arr.length()} 个")

        val type = args.optString("type", "dword")
        if (!MemoryEngine.isValidType(type)) return err("非法数据类型 \"$type\"")

        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val a = parseAddress(arr.opt(i)) ?: continue
            val v = MemoryEngine.readMemory(a, type)
            out.put(
                JSONObject()
                    .put("address", "0x${a.toString(16)}")
                    .put("value", v ?: JSONObject.NULL)
            )
        }
        return okJson(JSONObject().put("type", type).put("count", out.length()).put("items", out))
    }

    private fun readBytes(args: JSONObject): ToolResult {
        if (MemoryEngine.getAttachedPid() == null) return err("尚未附加进程，先调用 gg_attach")
        val addr = parseAddress(args.opt("address")) ?: return err("无法解析 address")
        val size = args.optInt("size", 64).coerceIn(1, 4096)

        val bytes = MemoryEngine.readBytes(addr, size)
            ?: return err("读取 0x${addr.toString(16)} 失败")

        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val lineAddr = addr + i
            sb.append("0x%012x  ".format(lineAddr))
            val ascii = StringBuilder()
            for (j in 0 until 16) {
                if (i + j < bytes.size) {
                    val b = bytes[i + j].toInt() and 0xFF
                    sb.append("%02x ".format(b))
                    ascii.append(if (b in 32..126) b.toChar() else '.')
                } else {
                    sb.append("   ")
                }
            }
            sb.append(" |").append(ascii).append("|\n")
            i += 16
        }

        return okJson(
            JSONObject()
                .put("address", "0x${addr.toString(16)}")
                .put("size", bytes.size)
                .put("hexdump", sb.toString().trimEnd())
        )
    }

    private fun write(args: JSONObject): ToolResult {
        readOnlyGate()?.let { return it }
        if (MemoryEngine.getAttachedPid() == null) return err("尚未附加进程，先调用 gg_attach")

        val addr = parseAddress(args.opt("address")) ?: return err("无法解析 address")
        val type = args.optString("type", "dword")
        if (!MemoryEngine.isValidType(type)) return err("非法数据类型 \"$type\"")
        val raw = args.opt("value") ?: return err("需要提供 value")
        val v = parseNumber(raw, type) ?: return err("无法把 \"$raw\" 解析为 $type")

        val before = MemoryEngine.readMemory(addr, type)
        if (!MemoryEngine.writeMemory(addr, v, type)) {
            return err("写入 0x${addr.toString(16)} 失败（地址可能不可写）")
        }
        val after = MemoryEngine.readMemory(addr, type)

        val o = JSONObject()
            .put("address", "0x${addr.toString(16)}")
            .put("type", type)
            .put("before", before ?: JSONObject.NULL)
            .put("written", v)
            .put("after", after ?: JSONObject.NULL)
            .put("verified", after != null && numericEquals(after, v))

        if (after != null && !numericEquals(after, v)) {
            o.put(
                "warning",
                "回读值与写入值不一致，游戏可能立刻把它改了回去。可用 gg_freeze 持续锁定该地址"
            )
        }
        return okJson(o)
    }

    private fun writeBatch(args: JSONObject): ToolResult {
        readOnlyGate()?.let { return it }
        if (MemoryEngine.getAttachedPid() == null) return err("尚未附加进程，先调用 gg_attach")

        val items = args.optJSONArray("items") ?: return err("需要提供 items 数组")
        if (items.length() == 0) return err("items 为空")
        if (items.length() > 500) return err("一次最多写 500 项")

        val results = JSONArray()
        var okCount = 0
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val addr = parseAddress(it.opt("address"))
            val type = it.optString("type", "dword")
            val v = addr?.let { _ -> it.opt("value")?.let { r -> parseNumber(r, type) } }

            if (addr == null || v == null || !MemoryEngine.isValidType(type)) {
                results.put(JSONObject().put("index", i).put("ok", false).put("error", "参数非法"))
                continue
            }
            val done = MemoryEngine.writeMemory(addr, v, type)
            val after = if (done) MemoryEngine.readMemory(addr, type) else null
            if (done) okCount++
            results.put(
                JSONObject()
                    .put("address", "0x${addr.toString(16)}")
                    .put("ok", done)
                    .put("after", after ?: JSONObject.NULL)
            )
        }
        return okJson(
            JSONObject().put("total", items.length()).put("succeeded", okCount).put("results", results)
        )
    }

    private fun freeze(args: JSONObject): ToolResult {
        val action = args.optString("action", "list")

        if (action == "list") {
            val arr = JSONArray()
            MemoryFreezer.getFrozenAddresses().forEach {
                arr.put(
                    JSONObject()
                        .put("address", it["addressHex"] ?: it["address"])
                        .put("value", it["value"])
                        .put("type", it["type"])
                )
            }
            return okJson(JSONObject().put("count", arr.length()).put("frozen", arr))
        }

        readOnlyGate()?.let { return it }

        return when (action) {
            "add" -> {
                if (MemoryEngine.getAttachedPid() == null) return err("尚未附加进程，先调用 gg_attach")
                val addr = parseAddress(args.opt("address")) ?: return err("无法解析 address")
                val type = args.optString("type", "dword")
                if (!MemoryEngine.isValidType(type)) return err("非法数据类型 \"$type\"")
                val raw = args.opt("value") ?: return err("action=add 需要提供 value")
                val v = parseNumber(raw, type) ?: return err("无法把 \"$raw\" 解析为 $type")
                MemoryFreezer.freeze(addr, v, type)
                okJson(
                    JSONObject()
                        .put("frozen", "0x${addr.toString(16)}")
                        .put("value", v)
                        .put("type", type)
                        .put("note", "后台每 100ms 写回一次，直到 remove 或 detach")
                )
            }

            "remove" -> {
                val addr = parseAddress(args.opt("address")) ?: return err("无法解析 address")
                MemoryFreezer.unfreeze(addr)
                okJson(JSONObject().put("unfrozen", "0x${addr.toString(16)}"))
            }

            "remove_all" -> okJson(JSONObject().put("unfrozen_count", MemoryFreezer.unfreezeAll()))

            else -> err("未知 action \"$action\"，可用 add / remove / remove_all / list")
        }
    }

    private fun runLua(args: JSONObject): ToolResult {
        readOnlyGate()?.let { return it }
        val script = args.optString("script", "")
        if (script.isBlank()) return err("需要提供 script")

        val output = LuaEngine.executeScript(script, headlessMode = true)
        return okJson(
            JSONObject()
                .put("output", output)
                .put(
                    "note",
                    "以无人值守方式执行：gg.choice/prompt/alert 未弹窗，走的是默认分支（详见 output 中的标注）"
                )
        )
    }

    // ==================== 辅助 ====================

    private fun readOnlyGate(): ToolResult? =
        if (McpConfig.readOnly) {
            err(
                "只读模式已开启，写内存/冻结/执行脚本被拒绝。" +
                        "如需修改，请在 App 的「MCP 服务」设置里关闭只读模式。"
            )
        } else null

    private fun itemsArray(rows: List<Map<String, Any>>): JSONArray {
        val arr = JSONArray()
        rows.forEach { r ->
            val o = JSONObject()
                .put("address", r["address"])
                .put("value", r["value"])
                .put("type", r["type"])
            (r["machineCode"] as? String)?.let { o.put("bytes", it) }
            if (r["isFrozen"] == true) o.put("frozen", true)
            arr.put(o)
        }
        return arr
    }

    /** 接受 "0x7f..." / "7f..." 十六进制串，或十进制数字 */
    fun parseAddress(raw: Any?): Long? {
        if (raw == null || raw == JSONObject.NULL) return null
        if (raw is Number) return raw.toLong()
        val s = raw.toString().trim()
        if (s.isEmpty()) return null
        return when {
            s.startsWith("0x", true) -> s.substring(2).toLongOrNull(16)
            s.all { it.isDigit() } && s.length < 19 -> s.toLongOrNull()
            else -> s.toLongOrNull(16)
        }
    }

    private fun parseNumber(raw: Any, type: String): Any? {
        val s = raw.toString().trim()
        return if (type == "float" || type == "double") {
            (raw as? Number)?.toDouble() ?: s.toDoubleOrNull()
        } else {
            (raw as? Number)?.toLong() ?: s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
        }
    }

    private fun parseDouble(raw: Any?): Double? {
        if (raw == null || raw == JSONObject.NULL) return null
        return (raw as? Number)?.toDouble() ?: raw.toString().trim().toDoubleOrNull()
    }

    private fun numericEquals(a: Any, b: Any): Boolean {
        val x = (a as? Number)?.toDouble() ?: return a == b
        val y = (b as? Number)?.toDouble() ?: return a == b
        return Math.abs(x - y) < 1e-6
    }

    private fun okJson(o: JSONObject) = ToolResult(o.toString(2), false)

    private fun err(msg: String) = ToolResult(
        JSONObject().put("error", msg).toString(2), true
    )
}
