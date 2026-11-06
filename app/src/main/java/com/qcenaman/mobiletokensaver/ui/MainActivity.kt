package com.qcenaman.mobiletokensaver.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.qcenaman.mobiletokensaver.CmdLog
import com.qcenaman.mobiletokensaver.Prefs
import com.qcenaman.mobiletokensaver.adb.Pairing
import com.qcenaman.mobiletokensaver.appVersion
import com.qcenaman.mobiletokensaver.auth.Auth
import com.qcenaman.mobiletokensaver.engine.HelperClient
import com.qcenaman.mobiletokensaver.engine.Keeper
import com.qcenaman.mobiletokensaver.engine.Privileged
import com.qcenaman.mobiletokensaver.engine.Privileged.ShizukuState
import com.qcenaman.mobiletokensaver.net.Tunnel
import com.qcenaman.mobiletokensaver.service.EngineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Setup: shell power (Shizuku), Tailscale Funnel, connector details for claude.ai, sign-in approvals, log. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.killed = false
        EngineService.start(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Home() }
            }
        }
    }

    private fun copy(label: String, v: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, v))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun open(i: Intent) { runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    @Composable
    private fun Home() {
        val scroll = rememberScrollState()
        var paused by remember { mutableStateOf(Prefs.paused) }
        val log by CmdLog.recent.collectAsState()
        val tunnel by Tunnel.state.collectAsState()
        val tunnelLog by Tunnel.log.collectAsState()
        val pending by Auth.pending.collectAsState()
        val pairStatus by Pairing.status.collectAsState()
        var shell by remember { mutableStateOf("checking…") }
        var shizuku by remember { mutableStateOf(ShizukuState.NOT_RUNNING) }
        var helperUp by remember { mutableStateOf(false) }
        var secret by remember { mutableStateOf(Prefs.clientSecret) }
        var token by remember { mutableStateOf(Prefs.token) }
        var code by remember { mutableStateOf("") }
        var sessions by remember { mutableStateOf(0) }
        var tunnelOn by remember { mutableStateOf(Prefs.tunnelOn) }

        LaunchedEffect(Unit) {
            while (true) {
                withContext(Dispatchers.IO) {
                    shizuku = Privileged.shizukuState()
                    helperUp = HelperClient.alive()
                    shell = Pairing.shellState(applicationContext)
                    sessions = Auth.oauth.sessions()
                }
                delay(3000)
            }
        }

        Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MobileTokenSaver", style = MaterialTheme.typography.headlineMedium)
            Text("v$appVersion · ${if (paused) "paused" else "running"}", style = MaterialTheme.typography.bodySmall)

            if (pending.isNotEmpty()) {
                Section("Claude wants to sign in")
                pending.forEach { p ->
                    Text("From ${runCatching { java.net.URI(p.redirectUri).host }.getOrNull() ?: p.redirectUri}", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { Auth.decide(applicationContext, p.id, true) }) { Text("Approve") }
                        OutlinedButton(onClick = { Auth.decide(applicationContext, p.id, false) }) { Text("Deny") }
                    }
                }
            }

            Section("1 · Shell power")
            Text(shell, style = MaterialTheme.typography.bodyMedium)
            Text("Shizuku: " + when (shizuku) {
                ShizukuState.READY -> "allowed"
                ShizukuState.NO_PERMISSION -> "running, not allowed yet"
                ShizukuState.OLD_VERSION -> "too old, update Shizuku"
                ShizukuState.NOT_RUNNING -> "not running"
            }, style = MaterialTheme.typography.bodySmall)
            when (shizuku) {
                ShizukuState.NO_PERMISSION -> Wide("Allow in Shizuku") { Privileged.requestShizuku() }
                ShizukuState.NOT_RUNNING, ShizukuState.OLD_VERSION -> Wide("Open Shizuku") {
                    packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { open(it) }
                        ?: open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                }
                ShizukuState.READY -> if (!helperUp) Wide("Start shell helper") { Keeper.kick(applicationContext) }
            }

            Section("2 · Tailscale Funnel")
            Text("Status: ${tunnel.status}", style = MaterialTheme.typography.bodyMedium)
            if (tunnel.error.isNotEmpty()) Text(tunnel.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (tunnel.login.isNotEmpty()) Wide("Sign in to Tailscale") { open(Intent(Intent.ACTION_VIEW, Uri.parse(tunnel.login))) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Tunnel.restart(applicationContext) }) { Text("Restart tunnel") }
                OutlinedButton(onClick = {
                    tunnelOn = !tunnelOn; Prefs.tunnelOn = tunnelOn
                    if (tunnelOn) Tunnel.restart(applicationContext) else Tunnel.stop()
                }) { Text(if (tunnelOn) "Turn off" else "Turn on") }
            }

            if (tunnel.status != "up" && tunnelLog.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tunnel log", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Button(onClick = { copy("Tunnel log", tunnelLog.joinToString("\n")) }) { Text("Copy log") }
                }
                tunnelLog.takeLast(12).forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }

            Section("3 · Claude connector")
            Text("In Claude: Settings → Connectors → Add custom connector. Paste the URL, open Advanced settings, paste Client ID and Client secret.",
                style = MaterialTheme.typography.bodySmall)
            val url = (tunnel.url.ifEmpty { "https://${Prefs.tsHostname}.tail90c428.ts.net" }) + "/mcp"
            Copyable("URL", url)
            if (tunnel.url.isEmpty()) Text("The URL only works once Tailscale Funnel above says \"up\".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Copyable("Client ID", Prefs.clientId)
            Copyable("Client secret", secret)
            Text("Signed-in Claude sessions: $sessions", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Auth.oauth.revokeAll(); sessions = 0 }) { Text("Sign out all") }
                OutlinedButton(onClick = { secret = Prefs.newClientSecret(); Auth.oauth.revokeAll(); sessions = 0 }) { Text("New secret") }
            }

            Section("4 · Permissions")
            Text("The shell helper grants most of these by itself once it runs.", style = MaterialTheme.typography.bodySmall)
            Wide("Accessibility (read and tap the screen)") { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            Wide("Notification access") { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            Wide("All files access") { open(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            Wide("Battery: unrestricted") { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }

            Section("Engine")
            Wide(if (paused) "Resume (accept tool calls)" else "Pause (refuse tool calls)") {
                paused = !paused; Prefs.paused = paused; EngineService.refresh(this@MainActivity)
            }
            Text("Direct access token (Bearer, for LAN/Tailscale use without OAuth)", style = MaterialTheme.typography.bodySmall)
            Copyable("Token", token)
            OutlinedButton(onClick = { token = Prefs.newToken() }) { Text("New token") }

            if (shizuku == ShizukuState.NOT_RUNNING && !helperUp) {
                Section("Fallback: pair Wireless debugging")
                Text("Only needed without Shizuku. Open Wireless debugging → Pair device with pairing code, then type the code here or into the notification.",
                    style = MaterialTheme.typography.bodySmall)
                Text(pairStatus, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { Pairing.startWatching(applicationContext); Pairing.submitCode(applicationContext, -1, code) }, enabled = code.length == 6) { Text("Pair") }
                }
                Wide("Open Developer options") { Pairing.startWatching(applicationContext); open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            }

            Section("Log")
            log.take(40).forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }

    @Composable
    private fun Section(title: String) {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }

    @Composable
    private fun Wide(label: String, onClick: () -> Unit) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }

    @Composable
    private fun Copyable(label: String, value: String, enabled: Boolean = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { copy(label, value) }, enabled = enabled) { Text("Copy") }
        }
    }
}
