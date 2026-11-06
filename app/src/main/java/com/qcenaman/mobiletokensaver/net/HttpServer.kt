package com.qcenaman.mobiletokensaver.net

import android.content.Context
import com.qcenaman.mobiletokensaver.Prefs
import com.qcenaman.mobiletokensaver.mcp.McpServer
import com.qcenaman.mobiletokensaver.tools.Registry
import com.qcenaman.mobiletokensaver.mcp.asMap
import fi.iki.elonen.NanoHTTPD

/**
 * Serves MCP over HTTP (JSON-RPC POST at /mcp), token-authenticated.
 * Bound to all interfaces so LAN and tunnel reach it; auth gates every call.
 */
class HttpServer(private val ctx: Context, port: Int) : NanoHTTPD(port) {
    private val mcp = McpServer(Registry.tools)

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.uri == "/health" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
            session.uri == "/mcp" && session.method == Method.POST -> handleMcp(session)
            session.uri == "/link" && session.method == Method.POST -> handleLink(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    }

    /** The PC's `phone pair` posts its link details here (token, addresses, MAC). */
    private fun handleLink(session: IHTTPSession): Response {
        if (!authed(session)) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "unauthorized")
        val body = HashMap<String, String>(); session.parseBody(body)
        val json = body["postData"] ?: ""
        if (!json.contains("\"token\"")) {
            // No token: a route refresh from an already-paired PC (e.g. a new tunnel URL).
            val m = runCatching { com.qcenaman.mobiletokensaver.mcp.Json.parse(json).asMap() }.getOrNull()
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad json")
            // Not paired yet: the PC must send the full link (token, port, lan_ip, tailscale_ip, mac, name) over any route it
            // has — this needs no ADB, only the phone's own access token from the app screen in the Authorization header.
            if (PcLink.pc() == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "no PC link yet: POST the full link (with token) to /link")
            PcLink.merge(m["routes"]?.asMap() ?: m)
            PcLink.reportSoon(ctx, "link", 1500)
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
        }
        PcLink.store(json)
        PcLink.reportSoon(ctx, "pair", 1500)
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
    }

    private fun authed(session: IHTTPSession): Boolean {
        val auth = session.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: session.headers["x-mts-token"]
        return auth == Prefs.token
    }

    private fun handleMcp(session: IHTTPSession): Response {
        if (!authed(session)) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "unauthorized")
        val body = HashMap<String, String>()
        session.parseBody(body)
        val line = body["postData"] ?: ""
        val reply = mcp.handle(line) ?: return newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json", "")
        return newFixedLengthResponse(Response.Status.OK, "application/json", reply)
    }
}
