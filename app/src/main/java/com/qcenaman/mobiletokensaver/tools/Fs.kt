package com.qcenaman.mobiletokensaver.tools

import android.os.Environment
import com.qcenaman.mobiletokensaver.mcp.*
import java.io.File

/** Phone-storage tools: find / read / write / scan. Paths default to shared storage. */
object Fs {
    private val shared: String get() = Environment.getExternalStorageDirectory().path

    private fun resolve(p: String): File {
        var s = p.trim()
        if (s.startsWith("~")) s = shared + s.drop(1)
        if (!s.startsWith("/")) s = "$shared/$s"
        return File(s)
    }

    val find = Tool("find", "Find files by name/text under one or more paths.",
        """{"type":"object","properties":{"path":{"type":"array","items":{"type":"string"}},"name":{"type":"string"},"text":{"type":"string"},"limit":{"type":"integer"},"recursive":{"type":"boolean"}},"required":["path"]}""") { a ->
        val roots = a.list("path").map { it.toString() }.ifEmpty { listOf(shared) }
        val name = a.string("name"); val text = a.string("text")
        val limit = a.int("limit", 200); val rec = a.bool("recursive", true)
        val hits = ArrayList<String>()
        for (r in roots) walk(resolve(r), rec) { f ->
            if (hits.size >= limit) return@walk
            if (name.isNotEmpty() && !f.name.contains(name.trim('*'), true)) return@walk
            if (text.isNotEmpty()) {
                val m = runCatching { f.length() < 2_000_000 && f.readText().contains(text, true) }.getOrDefault(false)
                if (!m) return@walk
            }
            hits.add("${f.path}  ${f.length()}")
        }
        ToolResult.ok(if (hits.isEmpty()) "no matches" else hits.joinToString("\n"))
    }

    val read = Tool("read", "Read part of a file: mode head/tail/lines/grep/info/text.",
        """{"type":"object","properties":{"path":{"type":"string"},"mode":{"type":"string"},"from_line":{"type":"integer"},"lines":{"type":"integer"},"pattern":{"type":"string"},"max_chars":{"type":"integer"}},"required":["path"]}""") { a ->
        val f = resolve(a.string("path"))
        if (!f.exists()) return@Tool ToolResult.err("not found: ${f.path}")
        when (a.string("mode", "head").lowercase()) {
            "info" -> ToolResult.ok("${f.path}\nsize=${f.length()} dir=${f.isDirectory} modified=${f.lastModified()}")
            "tail" -> ToolResult.ok(f.readLines().takeLast(a.int("lines", 40)).joinToString("\n"))
            "lines" -> { val from = a.int("from_line", 1); ToolResult.ok(f.readLines().drop(from - 1).take(a.int("lines", 40)).joinToString("\n")) }
            "grep" -> { val p = a.string("pattern"); ToolResult.ok(f.readLines().filter { it.contains(p, true) }.take(a.int("lines", 60)).joinToString("\n")) }
            "text" -> ToolResult.ok(f.readText().take(a.int("max_chars", 8000)))
            else -> ToolResult.ok(f.readLines().take(a.int("lines", 40)).joinToString("\n"))
        }
    }

    val write = Tool("write", "Create or edit a file: op write/append/edit.",
        """{"type":"object","properties":{"path":{"type":"string"},"op":{"type":"string"},"content":{"type":"string"},"find":{"type":"string"},"replace":{"type":"string"}},"required":["path"]}""") { a ->
        val f = resolve(a.string("path")); f.parentFile?.mkdirs()
        when (a.string("op", "write").lowercase()) {
            "append" -> { f.appendText(a.string("content")); ToolResult.ok("appended ${a.string("content").length} chars") }
            "edit" -> {
                val s = f.readText(); val find = a.string("find")
                if (!s.contains(find)) return@Tool ToolResult.err("find text not present")
                f.writeText(s.replace(find, a.string("replace"))); ToolResult.ok("edited")
            }
            else -> { f.writeText(a.string("content")); ToolResult.ok("wrote ${f.length()} bytes to ${f.path}") }
        }
    }

    val scan = Tool("scan", "List a folder's immediate entries with sizes.",
        """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""") { a ->
        val d = resolve(a.string("path"))
        val kids = d.listFiles()?.sortedBy { it.name } ?: return@Tool ToolResult.err("cannot list ${d.path}")
        ToolResult.ok(kids.joinToString("\n") { (if (it.isDirectory) "d " else "  ") + "${it.name}  ${it.length()}" })
    }

    private fun walk(f: File, rec: Boolean, cb: (File) -> Unit) {
        if (f.isDirectory) { f.listFiles()?.forEach { if (it.isDirectory) { if (rec) walk(it, true, cb) } else cb(it) } }
        else cb(f)
    }

    val all = listOf(find, read, write, scan)
}
