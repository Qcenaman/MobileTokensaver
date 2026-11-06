package com.qcenaman.mobiletokensaver.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.qcenaman.mobiletokensaver.App
import com.qcenaman.mobiletokensaver.engine.Shell
import com.qcenaman.mobiletokensaver.mcp.*
import java.io.ByteArrayOutputStream

/** screen / apps / notifications / system / web — device-level tools. */
object Device {
    private val ctx: Context get() = App.ctx

    val screen = Tool("screen", "Screenshot as a downscaled JPEG (only when asked). Optional max_px.",
        """{"type":"object","properties":{"max_px":{"type":"integer"}}}""") { a ->
        val png = Shell.runBytes(ctx, "screencap -p 2>/dev/null")
        if (png.isEmpty()) return@Tool ToolResult.err("screencap failed (shell channel down)")
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@Tool ToolResult.err("decode failed")
        val max = a.int("max_px", 900)
        val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val bos = ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        ToolResult.image(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), caption = "${out.width}x${out.height}")
    }

    val apps = Tool("apps", "List/launch/stop/install/uninstall apps. action + package. install: url (http/https, downloaded on the phone) or path, optional sha256, size, test (-t), replace (default true).",
        """{"type":"object","properties":{"action":{"type":"string","enum":["list","launch","stop","install","uninstall"]},"package":{"type":"string"},"filter":{"type":"string"},"url":{"type":"string","description":"http(s) URL of the APK to download and install"},"path":{"type":"string","description":"APK path on the phone"},"sha256":{"type":"string","description":"expected hex sha256 of the APK"},"size":{"type":"integer","description":"expected size in bytes"},"test":{"type":"boolean","description":"allow test-only APKs (pm install -t)"},"replace":{"type":"boolean","description":"reinstall keeping data (pm install -r), default true"}}}""") { a ->
        val pkg = a.string("package")
        when (a.string("action", "list").lowercase()) {
            "list" -> { val (_, o) = Shell.run(ctx, "pm list packages -3"); val f = a.string("filter"); ToolResult.ok(o.lines().map { it.removePrefix("package:") }.filter { f.isEmpty() || it.contains(f, true) }.joinToString("\n")) }
            "launch" -> if (pkg.isEmpty()) ToolResult.err("package required") else ToolResult.ok(Shell.run(ctx, ApkInstall.launchCmd(pkg)).second.ifBlank { "launched $pkg" })
            "stop" -> if (pkg.isEmpty()) ToolResult.err("package required") else ToolResult.ok(Shell.run(ctx, ApkInstall.stopCmd(pkg)).let { "stopped $pkg" })
            "install" -> install(a)
            "uninstall" -> if (pkg.isEmpty()) ToolResult.err("package required") else ToolResult.ok(Shell.run(ctx, ApkInstall.uninstallCmd(pkg)).second)
            else -> ToolResult.err("unknown action")
        }
    }

    /** Get the APK into the app's external cache (shell can read it), check it, stage into /data/local/tmp, pm install, clean up. */
    private fun install(a: Map<String, Any?>): ToolResult {
        val url = a.string("url").trim(); val path = a.string("path").trim()
        if (url.isEmpty() && path.isEmpty()) return ToolResult.err("install needs url or path")
        if (url.isNotEmpty() && !ApkInstall.isHttpUrl(url)) return ToolResult.err("url must be http or https")
        val dir = ctx.externalCacheDir ?: return ToolResult.err("external cache dir unavailable")
        dir.mkdirs()
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val local = java.io.File(dir, "install-$token.apk")
        val staged = ApkInstall.stagedPath(token)
        try {
            if (url.isNotEmpty()) {
                download(url, local)?.let { return ToolResult.err(it) }
            } else {
                val (code, out) = Shell.run(ctx, "cat ${ApkInstall.q(path)} > ${ApkInstall.q(local.absolutePath)}")
                if (code != 0 || !local.exists()) return ToolResult.err("cannot read $path: ${out.trim().ifEmpty { "exit $code" }}")
            }
            val size = (a["size"] as? Number)?.toLong()
            ApkInstall.verify(local, size, a.string("sha256"))?.let { return ToolResult.err(it) }

            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getPackageArchiveInfo(local.absolutePath, 0)
                ?: return ToolResult.err("not a valid APK (could not parse ${local.length()} bytes)")

            val (sc, so) = Shell.run(ctx, ApkInstall.stageCmd(local.absolutePath, staged))
            val stagedSize = so.trim().lines().lastOrNull()?.trim()?.toLongOrNull()
            if (sc != 0 || stagedSize != local.length())
                return ToolResult.err("staging to $staged failed: ${so.trim().ifEmpty { "exit $sc" }}")

            val (pc, po) = Shell.run(ctx, ApkInstall.installCmd(staged, a.bool("replace", true), a.bool("test", false)))
            ApkInstall.failure(po, pc)?.let { return ToolResult.err(it) }
            return ToolResult.ok("installed ${info.packageName} ${info.versionName ?: ""}".trim())
        } finally {
            runCatching { Shell.run(ctx, ApkInstall.cleanupCmd(staged)) }
            local.delete()
        }
    }

    /** Download in the engine (not via shell); null on success, else an error. */
    private fun download(url: String, dest: java.io.File): String? {
        try {
        var u = java.net.URL(url); var hops = 0
        while (true) {
            val c = u.openConnection() as java.net.HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "MobileTokenSaver"); c.connectTimeout = 15000; c.readTimeout = 60000
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: return "redirect without Location"
                c.disconnect()
                u = java.net.URL(u, loc)
                if (u.protocol != "http" && u.protocol != "https") return "redirect to non-http url"
                if (++hops > 5) return "too many redirects"
                continue
            }
            if (code !in 200..299) { c.disconnect(); return "download failed: HTTP $code" }
            c.inputStream.use { inp -> dest.outputStream().use { inp.copyTo(it, 64 * 1024) } }
            c.disconnect()
            return null
        }
        } catch (e: Exception) { return "download failed: ${e.message}" }
    }

    val notifications = Tool("notifications", "List active notifications (via NotificationListener). action list.",
        """{"type":"object","properties":{"action":{"type":"string"}}}""") { _ ->
        com.qcenaman.mobiletokensaver.service.NotifService.instance?.let { ToolResult.ok(it.dump().ifEmpty { "(none)" }) }
            ?: ToolResult.err("notification access not granted")
    }

    val system = Tool("system", "Phone state: battery, network, storage.",
        """{"type":"object","properties":{}}""") { _ ->
        val bat = Shell.run(ctx, "dumpsys battery | grep -E 'level|status|temperature'").second
        val net = Shell.run(ctx, "settings get global wifi_on; ip route 2>/dev/null | head -1").second
        val disk = Shell.run(ctx, "df -h /sdcard | tail -1").second
        ToolResult.ok("-- shell --\n${Shell.status(ctx)}\n-- addresses --\n${com.qcenaman.mobiletokensaver.net.Addresses.summary()}\n-- battery --\n$bat\n-- network --\n$net\n-- storage --\n$disk")
    }

    val web = Tool("web", "Fetch a URL on the phone and return clean text.",
        """{"type":"object","properties":{"url":{"type":"string"},"max_chars":{"type":"integer"}},"required":["url"]}""") { a ->
        val url = a.string("url")
        val html = runCatching {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
            c.setRequestProperty("User-Agent", "Mozilla/5.0 MobileTokenSaver"); c.connectTimeout = 10000; c.readTimeout = 15000
            c.inputStream.bufferedReader().readText()
        }.getOrElse { return@Tool ToolResult.err("fetch failed: ${it.message}") }
        val text = html.replace(Regex("(?s)<script.*?</script>"), " ")
            .replace(Regex("(?s)<style.*?</style>"), " ")
            .replace(Regex("<[^>]+>"), " ").replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n\\s*\n+"), "\n").trim()
        ToolResult.ok(text.take(a.int("max_chars", 6000)))
    }

    val all = listOf(screen, apps, notifications, system, web)
}
