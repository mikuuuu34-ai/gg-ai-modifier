package com.yl.aigg.ai_gg666.mcp

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * MCP over Streamable HTTP。
 *
 * 协议层刻意做得很薄：POST /mcp 收 JSON-RPC 2.0，直接回一个 application/json 响应。
 * MCP 规范允许服务端不使用 SSE 而返回单次 JSON，Claude Code / Codex 都能正常工作，
 * 省掉一整套流式实现和心跳维护。
 *
 * 鉴权是必需的而非可选：这个端点能以 root 读写任意进程内存。
 */
class McpServer(bindAddress: String?, port: Int) : NanoHTTPD(bindAddress, port) {

    companion object {
        private const val TAG = "McpServer"
        private const val SERVER_NAME = "gg-ai-modifier"
        private const val SERVER_VERSION = "1.0.0"

        /** 按新到旧排列；客户端请求的版本若在列内就原样回应 */
        private val SUPPORTED_PROTOCOLS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        private val PREFERRED_PROTOCOL = SUPPORTED_PROTOCOLS.first()

        private const val JSON_MIME = "application/json"
    }

    private val sessionId: String = java.util.UUID.randomUUID().toString()

    override fun serve(session: IHTTPSession): Response {
        return try {
            serveInner(session)
        } catch (e: Exception) {
            Log.e(TAG, "请求处理异常", e)
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                rpcError(null, -32603, "服务端内部错误: ${e.message}").toString()
            )
        }
    }

    private fun serveInner(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }

        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, JSON_MIME, "")
        }

        if (uri != "/mcp") {
            return jsonResponse(
                Response.Status.NOT_FOUND,
                JSONObject().put("error", "MCP 端点在 /mcp").toString()
            )
        }

        if (!authorized(session)) {
            Log.w(TAG, "鉴权失败，来源 ${session.remoteIpAddress}")
            return jsonResponse(
                Response.Status.UNAUTHORIZED,
                JSONObject()
                    .put("error", "未授权。请在请求头带上 Authorization: Bearer <token>，token 见 App 的 MCP 设置页")
                    .toString()
            )
        }

        // 我们不主动向客户端推送消息，因此不提供 GET 的 SSE 通道。
        // 规范允许服务端以 405 表示不支持。
        if (session.method == Method.GET) {
            return jsonResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                JSONObject().put("error", "本服务不提供服务端推送流，请用 POST").toString()
            )
        }

        if (session.method == Method.DELETE) {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, JSON_MIME, "")
        }

        if (session.method != Method.POST) {
            return jsonResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                JSONObject().put("error", "仅支持 POST").toString()
            )
        }

        val body = readBody(session)
        if (body.isBlank()) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                rpcError(null, -32700, "请求体为空").toString()
            )
        }

        val parsed: Any = try {
            val t = body.trimStart()
            if (t.startsWith("[")) JSONArray(body) else JSONObject(body)
        } catch (e: Exception) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                rpcError(null, -32700, "JSON 解析失败: ${e.message}").toString()
            )
        }

        // 批量请求：逐条处理，通知不产生响应
        if (parsed is JSONArray) {
            val out = JSONArray()
            for (i in 0 until parsed.length()) {
                val req = parsed.optJSONObject(i) ?: continue
                dispatch(req)?.let { out.put(it) }
            }
            return if (out.length() == 0) acceptedResponse()
            else jsonResponse(Response.Status.OK, out.toString())
        }

        val response = dispatch(parsed as JSONObject)
            ?: return acceptedResponse()   // 通知类消息按规范返回 202 且无正文

        return jsonResponse(Response.Status.OK, response.toString()).apply {
            addHeader("Mcp-Session-Id", sessionId)
        }
    }

    // ==================== JSON-RPC 分发 ====================

    /** 返回 null 表示这是一条通知，不需要响应 */
    private fun dispatch(req: JSONObject): JSONObject? {
        val method = req.optString("method", "")
        val hasId = req.has("id") && !req.isNull("id")
        val id: Any? = if (hasId) req.opt("id") else null
        val params = req.optJSONObject("params") ?: JSONObject()

        if (method.startsWith("notifications/")) return null

        return when (method) {
            "initialize" -> {
                val requested = params.optString("protocolVersion", "")
                val version = if (requested in SUPPORTED_PROTOCOLS) requested else PREFERRED_PROTOCOL
                rpcResult(
                    id,
                    JSONObject()
                        .put("protocolVersion", version)
                        .put("capabilities", JSONObject().put("tools", JSONObject()))
                        .put(
                            "serverInfo",
                            JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION)
                        )
                        .put(
                            "instructions",
                            "这是 Android 游戏内存修改器的 MCP 接口。典型流程：" +
                                    "gg_status 查看状态 → gg_list_processes 找到游戏 → gg_attach 附加 → " +
                                    "gg_search 搜索数值 → 回到游戏让数值变化 → gg_refine 反复收敛 → " +
                                    "gg_write 修改。搜索结果不会整体返回，只给 set_id 和计数，" +
                                    "请靠 gg_refine 收敛而不是翻页。写操作默认被只读模式拦截。"
                        )
                )
            }

            "ping" -> rpcResult(id, JSONObject())

            "tools/list" -> rpcResult(id, JSONObject().put("tools", McpTools.definitions()))

            "tools/call" -> {
                val name = params.optString("name", "")
                if (name.isEmpty()) {
                    return rpcError(id, -32602, "缺少工具名")
                }
                val args = params.optJSONObject("arguments") ?: JSONObject()

                val startedAt = System.currentTimeMillis()
                val result = McpTools.call(name, args)
                val cost = System.currentTimeMillis() - startedAt
                McpCallLog.record(name, !result.isError, cost)
                Log.i(TAG, "tools/call $name -> ${if (result.isError) "error" else "ok"} (${cost}ms)")

                rpcResult(
                    id,
                    JSONObject()
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject().put("type", "text").put("text", result.text)
                            )
                        )
                        .put("isError", result.isError)
                )
            }

            // 未声明这些能力，但有客户端会主动探测，回空列表比回错误干净
            "resources/list" -> rpcResult(id, JSONObject().put("resources", JSONArray()))
            "resources/templates/list" -> rpcResult(id, JSONObject().put("resourceTemplates", JSONArray()))
            "prompts/list" -> rpcResult(id, JSONObject().put("prompts", JSONArray()))

            else -> rpcError(id, -32601, "不支持的方法: $method")
        }
    }

    // ==================== 辅助 ====================

    private fun authorized(session: IHTTPSession): Boolean {
        val expected = McpConfig.token
        if (expected.isEmpty()) return false

        // NanoHTTPD 会把请求头名统一转成小写
        val auth = session.headers["authorization"]
        if (auth != null && auth.startsWith("Bearer ", ignoreCase = true)) {
            if (constantTimeEquals(auth.substring(7).trim(), expected)) return true
        }
        // 部分客户端不便设置请求头，允许退回到查询参数
        val qp = session.parms["token"]
        return qp != null && constantTimeEquals(qp, expected)
    }

    /** 避免用字符串比较泄漏 token 前缀长度 */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "读取请求体失败: ${e.message}")
            ""
        }
    }

    private fun rpcResult(id: Any?, result: JSONObject): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)

    private fun rpcError(id: Any?, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun jsonResponse(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, JSON_MIME, body).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun acceptedResponse(): Response =
        newFixedLengthResponse(Response.Status.ACCEPTED, JSON_MIME, "")
}

/** 最近的工具调用记录，供 App 界面展示「AI 到底做了什么」 */
object McpCallLog {

    data class Entry(val timestamp: Long, val tool: String, val ok: Boolean, val costMs: Long)

    private const val CAPACITY = 100
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(tool: String, ok: Boolean, costMs: Long) {
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(Entry(System.currentTimeMillis(), tool, ok, costMs))
    }

    @Synchronized
    fun recent(limit: Int = 30): List<Map<String, Any>> =
        entries.toList().takeLast(limit).reversed().map {
            mapOf(
                "timestamp" to it.timestamp,
                "tool" to it.tool,
                "ok" to it.ok,
                "costMs" to it.costMs
            )
        }

    @Synchronized
    fun clear() = entries.clear()
}
