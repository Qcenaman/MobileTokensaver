package com.qcenaman.mobiletokensaver.mcp

import com.qcenaman.mobiletokensaver.CmdLog
import com.qcenaman.mobiletokensaver.Prefs
import com.qcenaman.mobiletokensaver.appVersion

/** Dispatches one JSON-RPC line and returns the reply line (or null for notifications). */
class McpServer(private val tools: List<Tool>, private val gate: Gate = Gate()) {
    /** Hooks into app state; the default uses Prefs/CmdLog, tests pass their own. */
    class Gate(
        val enabled: (String) -> Boolean = { Prefs.toolEnabled(it) },
        val paused: () -> Boolean = { Prefs.paused },
        val log: (String) -> Unit = { CmdLog.add(it) }
    )
    private val byName = tools.associateBy { it.name }

    val instructions = "MobileTokenSaver runs on this Android phone and returns short results. " +
        "Use it instead of built-in tools for anything on the phone. " +
        "scan/find to look around; read for just the part you need (ranges, text, OCR, image mode); " +
        "write to create or edit files; run/run_job/job for shell commands as background jobs (exit code and a short tail only); " +
        "ui to read the screen as a compact text tree and tap/swipe/type by label; screen for a downscaled screenshot only when asked; " +
        "apps to list/launch/stop/install; notifications to read and reply; system for battery/network/storage; " +
        "web to fetch a page as clean text; local_ai to summarise or classify big output on-device so only the result reaches you; " +
        "media/files to move, compress and push/pull to the PC; saved for named commands and macros."

    fun handle(line: String): String? {
        val req = try { Json.parse(line).asMap() } catch (e: Exception) { return error(null, -32700, "parse error") }
        val id = req["id"]
        val method = req.string("method")
        if (id == null) return null // notification
        return try {
            when (method) {
                "initialize" -> result(id, """{"protocolVersion":${Json.str(protoVer(req))},"capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"mts","version":${Json.str(appVersion)}},"instructions":${Json.str(instructions)}}""")
                "ping" -> result(id, "{}")
                "tools/list" -> result(id, """{"tools":[${tools.filter { gate.enabled(it.name) }.joinToString(",") { toolJson(it) }}]}""")
                "tools/call" -> callTool(id, req["params"].asMap())
                else -> error(id, -32601, "method not found: $method")
            }
        } catch (e: Throwable) {
            error(id, -32603, "internal error: ${e.message}")
        }
    }

    private fun protoVer(req: Map<String, Any?>): String =
        req["params"].asMap().string("protocolVersion", "2025-06-18").ifEmpty { "2025-06-18" }

    private fun toolJson(t: Tool) =
        """{"name":${Json.str(t.name)},"description":${Json.str(t.description)},"inputSchema":${t.schema}}"""

    private fun callTool(id: Any?, params: Map<String, Any?>): String {
        val name = params.string("name")
        val args = params["arguments"].asMap()
        val tool = byName[name] ?: return error(id, -32602, "unknown tool: $name")
        if (!gate.enabled(name)) return callResult(id, ToolResult.err("tool '$name' is turned off in MobileTokenSaver"))
        if (gate.paused()) return callResult(id, ToolResult.err("MobileTokenSaver is paused"))
        gate.log("$name ${argsPreview(args)}")
        val res = try { tool.handler(args) } catch (e: Throwable) { ToolResult.err("${e.javaClass.simpleName}: ${e.message}") }
        return callResult(id, res)
    }

    private fun argsPreview(args: Map<String, Any?>): String =
        args.entries.joinToString(" ") { (k, v) -> "$k=${v.toString().take(60)}" }.take(160)

    private fun callResult(id: Any?, r: ToolResult): String {
        val content = if (r.imageB64 != null)
            """{"type":"image","data":${Json.str(r.imageB64)},"mimeType":${Json.str(r.mime)}}""" +
                if (r.text.isNotEmpty()) ""","""" + """{"type":"text","text":${Json.str(r.text)}}""" else ""
        else """{"type":"text","text":${Json.str(r.text)}}"""
        return result(id, """{"content":[$content],"isError":${r.isError}}""")
    }

    private fun result(id: Any?, raw: String) = """{"jsonrpc":"2.0","id":${idJson(id)},"result":$raw}"""
    private fun error(id: Any?, code: Int, msg: String) =
        """{"jsonrpc":"2.0","id":${idJson(id)},"error":{"code":$code,"message":${Json.str(msg)}}}"""
    private fun idJson(id: Any?): String = when (id) {
        is String -> Json.str(id); is Number -> id.toString().removeSuffix(".0"); else -> "null"
    }
}
