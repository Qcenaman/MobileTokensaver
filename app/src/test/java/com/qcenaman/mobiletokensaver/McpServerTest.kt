package com.qcenaman.mobiletokensaver

import com.qcenaman.mobiletokensaver.mcp.Json
import com.qcenaman.mobiletokensaver.mcp.McpServer
import com.qcenaman.mobiletokensaver.mcp.Tool
import com.qcenaman.mobiletokensaver.mcp.ToolResult
import com.qcenaman.mobiletokensaver.mcp.asMap
import com.qcenaman.mobiletokensaver.mcp.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerTest {
    private val echo = Tool("echo", "echo", """{"type":"object","properties":{"text":{"type":"string"}}}""") { a -> ToolResult.ok("got " + a.string("text")) }
    private val srv = McpServer(listOf(echo), gate = McpServer.Gate(enabled = { true }, paused = { false }, log = {}))

    @Test fun initializeAndList() {
        val init = Json.parse(srv.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")!!).asMap()
        assertEquals("2025-06-18", init["result"].asMap().string("protocolVersion"))
        val list = srv.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!
        assertTrue(list.contains("\"name\":\"echo\""))
    }

    @Test fun callAndErrors() {
        val r = Json.parse(srv.handle("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"x"}}}""")!!).asMap()
        val content = (r["result"].asMap()["content"] as List<*>)[0].asMap()
        assertEquals("got x", content.string("text"))
        assertTrue(srv.handle("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope"}}""")!!.contains("unknown tool"))
        assertTrue(srv.handle("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"echo","arguments":}}""")!!.contains("-32700"))
        assertNull(srv.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }
}
