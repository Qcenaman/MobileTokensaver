package com.qcenaman.mobiletokensaver.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import com.qcenaman.mobiletokensaver.CmdLog
import com.qcenaman.mobiletokensaver.adb.AdbManager
import com.qcenaman.mobiletokensaver.engine.HelperClient
import com.qcenaman.mobiletokensaver.engine.Shell

/**
 * Keeps the privileged channel alive on its own:
 *  - keeps the shell helper running (it survives Wi-Fi loss once started);
 *  - when Wi-Fi comes back, turns Wireless debugging on again (WRITE_SECURE_SETTINGS) and reconnects;
 *  - when the phone says pairing is required, asks for the code by notification (never drives the screen itself).
 * Reporting to the PC runs on its own (never behind a shell/ADB attempt, which can hang for minutes on mobile data):
 * every [REPORT_EVERY_MS], when any network comes or goes, when the phone's own IPs change (Tailscale up/down,
 * new Wi-Fi), after /link and after pc status (those two are triggered where they happen).
 */
object LinkKeeper {
    const val REPORT_EVERY_MS = 20 * 60_000L
    @Volatile private var running = false
    @Volatile private var wifiUp = false
    @Volatile private var lastAddresses = ""
    @Volatile private var lastPairAsk = 0L

    fun start(ctx: Context) {
        if (running) return
        running = true
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { wifiUp = true; CmdLog.add("wifi up"); Thread { try { repair(ctx) } catch (_: Exception) {} }.apply { isDaemon = true }.start() }
                override fun onLost(n: Network) { wifiUp = false; CmdLog.add("wifi lost — shell stays on the helper if it is up") }
            })
        // Any network coming or going (mobile data, Wi-Fi, the Tailscale VPN) or getting new addresses → report soon.
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { PcLink.reportSoon(ctx, "network up", 3000) }
                override fun onLost(n: Network) { PcLink.reportSoon(ctx, "network lost", 5000) }
                override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) { if (addressesChanged()) PcLink.reportSoon(ctx, "ip change", 3000) }
            })
        }
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: Network) { CmdLog.add("vpn up"); PcLink.reportSoon(ctx, "vpn up", 4000) }
                    override fun onLost(n: Network) { CmdLog.add("vpn lost"); PcLink.reportSoon(ctx, "vpn lost", 3000) }
                })
        }
        Thread {
            while (running) {
                try { repair(ctx) } catch (_: Exception) {}
                try { Thread.sleep(45_000) } catch (_: InterruptedException) {}
            }
        }.apply { isDaemon = true; name = "link-keeper" }.start()
        Thread {
            try { Thread.sleep(8_000) } catch (_: InterruptedException) {}
            while (running) {
                try {
                    val since = System.currentTimeMillis() - maxOf(PcLink.lastReportAt, PcLink.lastReportTry)
                    if (addressesChanged()) PcLink.report(ctx, why = "ip change")
                    else if (since >= REPORT_EVERY_MS) PcLink.report(ctx, why = "timer")
                    else if (PcLink.lastReportError.isNotEmpty() && since >= 2 * 60_000L) PcLink.report(ctx, why = "retry")
                } catch (_: Exception) {}
                try { Thread.sleep(60_000) } catch (_: InterruptedException) {}
            }
        }.apply { isDaemon = true; name = "pc-reporter" }.start()
    }

    /** True (and remembers) when the phone's LAN/Tailscale addresses differ from the last look. */
    private fun addressesChanged(): Boolean {
        val now = Addresses.summary()
        if (now == lastAddresses) return false
        val first = lastAddresses.isEmpty()
        lastAddresses = now
        if (!first) CmdLog.add("addresses changed: $now")
        return !first
    }

    @Synchronized
    private fun repair(ctx: Context) {
        try { com.qcenaman.mobiletokensaver.adb.AdbTcp.keep(ctx) } catch (_: Exception) {}
        if (HelperClient.alive()) return
        if (!wifiUp) return // adbd only listens on Wi-Fi; nothing to do until it returns
        enableWirelessDebugging(ctx)
        if (Shell.ready(ctx)) { HelperClient.ensureStarted(ctx); return }
        if (AdbManager.get(ctx).needsPairing && System.currentTimeMillis() - lastPairAsk > 30 * 60_000L) {
            lastPairAsk = System.currentTimeMillis()
            com.qcenaman.mobiletokensaver.adb.Pairing.startWatching(ctx) // asks for the code the moment the user opens "Pair device with pairing code"
            CmdLog.add("pairing required: open Wireless debugging → Pair device with pairing code; a notification asks for the code")
        }
    }

    /** Needs WRITE_SECURE_SETTINGS (granted once over adb: pm grant … WRITE_SECURE_SETTINGS). */
    fun enableWirelessDebugging(ctx: Context): Boolean = try {
        val cr = ctx.contentResolver
        if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) != 1) {
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            CmdLog.add("wireless debugging switched on")
            Thread.sleep(1500)
        }
        true
    } catch (e: Exception) { CmdLog.add("cannot switch wireless debugging on: ${e.message}"); false }

    /** Is a Wi-Fi network up right now (Wireless debugging / ADB only work over Wi-Fi)? */
    fun wifiUp(ctx: Context): Boolean = wifiUp || runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(false)

    fun stop() { running = false }
}
