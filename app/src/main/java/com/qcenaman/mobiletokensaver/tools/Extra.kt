package com.qcenaman.mobiletokensaver.tools

import com.qcenaman.mobiletokensaver.App
import com.qcenaman.mobiletokensaver.engine.Shell
import com.qcenaman.mobiletokensaver.mcp.*
import java.io.File

/** local_ai and saved. local_ai runs an on-device first pass; saved stores named commands. */
object Extra {
    val localAi = Tool("local_ai", "On-device first pass (summarise/classify/filter) so only the result reaches Claude. action + text.",
        """{"type":"object","properties":{"action":{"type":"string"},"text":{"type":"string"},"instruction":{"type":"string"}},"required":["action"]}""") { a ->
        val res = LocalAi.run(a.string("action", "summarize"), a.string("text"), a.string("instruction"))
        if (res == null) ToolResult.err("local AI model not loaded yet") else ToolResult.ok(res)
    }

    private fun savedDir() = File(App.ctx.filesDir, "saved").also { it.mkdirs() }

    val saved = Tool("saved", "Named saved commands/macros: action list/show/save/remove/run.",
        """{"type":"object","properties":{"action":{"type":"string"},"name":{"type":"string"},"command":{"type":"string"}}}""") { a ->
        val name = a.string("name")
        when (a.string("action", "list").lowercase()) {
            "list" -> ToolResult.ok(savedDir().listFiles()?.joinToString("\n") { it.name } ?: "(none)")
            "show" -> { val f = File(savedDir(), name); if (f.exists()) ToolResult.ok(f.readText()) else ToolResult.err("not found") }
            "save" -> { File(savedDir(), name).writeText(a.string("command")); ToolResult.ok("saved $name") }
            "remove" -> { File(savedDir(), name).delete(); ToolResult.ok("removed $name") }
            "run" -> { val f = File(savedDir(), name); if (!f.exists()) return@Tool ToolResult.err("not found"); val (c, o) = Shell.run(App.ctx, f.readText()); ToolResult(o.take(4000), isError = c != 0) }
            else -> ToolResult.err("unknown action")
        }
    }

    val pc = Tool("pc", "The user's desktop PC through its TokenSaver (all its tools: scan find read run run_job job system web window browser media office rag local_ai saved check). Pass tool + args to call one; reached over LAN, else Tailscale, else a public address or HTTPS tunnel (no Tailscale needed). action status (probe every route) / wake (Wake-on-LAN) / route (set a route by hand: kind public|tunnel + value, \"\" clears).",
        """{"type":"object","properties":{"action":{"type":"string","enum":["call","status","wake","route"]},"tool":{"type":"string"},"args":{"type":"object"},"kind":{"type":"string","enum":["public","tunnel"]},"value":{"type":"string"}}}""") { a ->
        when (a.string("action", "call").lowercase()) {
            "status" -> {
                val p = com.qcenaman.mobiletokensaver.net.PcLink.pc() ?: return@Tool ToolResult.err("no PC link yet (run `phone pair` on the PC while the phone is on the LAN)")
                val out = ToolResult.ok("pc=${p.name} port=${p.port} ${p.summary()}\n${com.qcenaman.mobiletokensaver.net.PcLink.probeAll()}")
                com.qcenaman.mobiletokensaver.net.PcLink.reportSoon(App.ctx, "pc status")
                out
            }
            "wake" -> ToolResult.ok(com.qcenaman.mobiletokensaver.net.PcLink.wake())
            "route" -> ToolResult.ok(com.qcenaman.mobiletokensaver.net.PcLink.setManualRoute(a.string("kind"), a.string("value")))
            else -> {
                val tool = a.string("tool"); if (tool.isEmpty()) return@Tool ToolResult.err("tool required")
                com.qcenaman.mobiletokensaver.net.PcLink.call(tool, com.qcenaman.mobiletokensaver.mcp.Json.encode(a["args"] ?: emptyMap<String, Any?>()))
            }
        }
    }

    val all = listOf(localAi, saved, pc)
}
