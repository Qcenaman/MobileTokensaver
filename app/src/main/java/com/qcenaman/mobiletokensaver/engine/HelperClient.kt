package com.qcenaman.mobiletokensaver.engine

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket
import com.qcenaman.mobiletokensaver.CmdLog
import com.qcenaman.mobiletokensaver.Prefs
import com.qcenaman.mobiletokensaver.adb.AdbManager
import com.qcenaman.mobiletokensaver.helper.Main
import java.io.DataInputStream
import java.io.DataOutputStream

/** Talks to the shell-user helper process; starts it over ADB when it is not running. */
object HelperClient {
    /** How long to wait for the helper's first frame (its pid) before treating it as hung. */
    const val FIRST_FRAME_MS = 15_000
    private fun connect(): Socket? = try {
        Socket().also { it.connect(InetSocketAddress("127.0.0.1", Main.PORT), 1500); it.soTimeout = 0 }
    } catch (_: Exception) { null }

    fun alive(): Boolean {
        val s = connect() ?: return false
        return try {
            val out = DataOutputStream(s.getOutputStream()); val inp = DataInputStream(s.getInputStream())
            out.write((Prefs.token + "\n").toByteArray()); val c = "__ping__".toByteArray(); out.writeInt(c.size); out.write(c); out.flush()
            s.soTimeout = 3000
            inp.readByte().toInt() == 3
        } catch (_: Exception) { false } finally { try { s.close() } catch (_: Exception) {} }
    }

    /** Streams merged output; returns exit code (-1 if the helper vanished). */
    fun exec(cmd: String, onPid: (Int) -> Unit = {}, onData: (ByteArray, Int) -> Unit): Int {
        val s = connect() ?: return -1
        try {
            val out = DataOutputStream(s.getOutputStream()); val inp = DataInputStream(s.getInputStream())
            out.write((Prefs.token + "\n").toByteArray()); val c = cmd.toByteArray(); out.writeInt(c.size); out.write(c); out.flush()
            // The helper answers with the pid frame at once; a helper that accepted but never answers is dead, not slow.
            s.soTimeout = FIRST_FRAME_MS
            while (true) {
                val type = inp.read(); if (type < 0) return -1
                val n = inp.readInt(); val payload = ByteArray(n).also { inp.readFully(it) }
                s.soTimeout = 0
                when (type) {
                    1 -> onData(payload, n)
                    2 -> onPid(int(payload))
                    3 -> return int(payload)
                }
            }
        } catch (_: Exception) { return -1 } finally { try { s.close() } catch (_: Exception) {} }
    }

    /** Ask the running helper to exit; its supervisor loop starts a fresh one from the current APK (used after an update). */
    fun restart(): Boolean {
        val s = connect() ?: return false
        return try {
            val out = DataOutputStream(s.getOutputStream())
            out.write((Prefs.token + "\n").toByteArray()); val c = "__exit__".toByteArray(); out.writeInt(c.size); out.write(c); out.flush()
            CmdLog.add("shell helper asked to restart from the new APK"); true
        } catch (_: Exception) { false } finally { try { s.close() } catch (_: Exception) {} }
    }

    private fun int(b: ByteArray) = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)

    /** Launch the helper as the shell user over the ADB channel; detached so it survives adbd. */
    @Synchronized
    fun ensureStarted(ctx: Context): Boolean {
        if (alive()) return true
        val adb = AdbManager.get(ctx)
        if (!adb.ensureConnected(ctx)) return false
        // A supervisor loop (shell user, detached from adbd) restarts the helper within 5 s if it ever dies, and looks the
        // APK path up each time, so after an app update the helper comes back from the new APK — all without Wi-Fi.
        val cmd = "nohup setsid sh -c 'while :; do CLASSPATH=\$(pm path ${ctx.packageName} | head -1 | cut -d: -f2) app_process /system/bin com.qcenaman.mobiletokensaver.helper.Main ${Prefs.token}; sleep 5; done' >/dev/null 2>&1 &"
        return try {
            // A helper from an older build (or one the app cannot reach) would block the port: replace it.
            adb.capture("for p in \$(pgrep -f com.qcenaman.mobiletokensaver.helper.Main); do [ \$p != \$\$ ] && kill -9 \$p; done 2>/dev/null; sleep 0.3\n$cmd\nsleep 1; echo started")
            val ok = (1..6).any { Thread.sleep(500); alive() }
            CmdLog.add(if (ok) "shell helper started (uid 2000, survives Wi-Fi loss)" else "shell helper did not come up")
            ok
        } catch (e: Exception) { CmdLog.add("helper start failed: ${e.message}"); false }
    }
}
