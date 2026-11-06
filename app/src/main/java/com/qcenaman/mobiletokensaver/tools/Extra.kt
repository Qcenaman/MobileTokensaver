package com.qcenaman.mobiletokensaver.tools

import com.qcenaman.mobiletokensaver.App
import com.qcenaman.mobiletokensaver.engine.Shell
import com.qcenaman.mobiletokensaver.mcp.*
import java.io.File

/** saved: named shell commands kept on the phone. */
object Extra {
    private fun savedDir() = File(App.ctx.filesDir, "saved").also { it.mkdirs() }
    private fun safe(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    val saved = Tool("saved", "Named saved shell commands: action list/show/save/remove/run (run returns the last `tail` lines, default 40).",
        """{"type":"object","properties":{"action":{"type":"string","enum":["list","show","save","remove","run"]},"name":{"type":"string"},"command":{"type":"string"},"tail":{"type":"integer"}}}""") { a ->
        val name = safe(a.string("name"))
        val f = File(savedDir(), name)
        when (a.string("action", "list").lowercase()) {
            "list" -> ToolResult.ok(savedDir().listFiles()?.joinToString("\n") { it.name }?.ifEmpty { null } ?: "(none)")
            "show" -> if (f.isFile) ToolResult.ok(f.readText()) else ToolResult.err("not found")
            "save" -> { if (name.isEmpty()) return@Tool ToolResult.err("name required"); f.writeText(a.string("command")); ToolResult.ok("saved $name") }
            "remove" -> { f.delete(); ToolResult.ok("removed $name") }
            "run" -> {
                if (!f.isFile) return@Tool ToolResult.err("not found")
                val (c, o) = Shell.run(App.ctx, f.readText())
                ToolResult("exit=$c\n" + o.lines().takeLast(a.int("tail", 40)).joinToString("\n"), isError = c != 0)
            }
            else -> ToolResult.err("unknown action")
        }
    }

    val all = listOf(saved)
}
