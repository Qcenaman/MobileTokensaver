package com.qcenaman.mobiletokensaver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.qcenaman.mobiletokensaver.CmdLog
import com.qcenaman.mobiletokensaver.Prefs
import com.qcenaman.mobiletokensaver.adb.Pairing
import com.qcenaman.mobiletokensaver.appVersion
import androidx.compose.runtime.collectAsState
import kotlin.concurrent.thread
import com.qcenaman.mobiletokensaver.service.EngineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.killed = false
        EngineService.start(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Home() }
            }
        }
    }

    override fun onResume() { super.onResume(); Pairing.startWatching(applicationContext) }

    @Composable
    private fun Home() {
        val scroll = rememberScrollState()
        var paused by remember { mutableStateOf(Prefs.paused) }
        val pairStatus by Pairing.status.collectAsState()
        val log by CmdLog.recent.collectAsState()
        var shell by remember { mutableStateOf("checking…") }
        var code by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            while (true) {
                shell = withContext(Dispatchers.IO) { Pairing.shellState(applicationContext) }
                delay(5000)
            }
        }

        Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MobileTokenSaver", style = MaterialTheme.typography.headlineMedium)
            Text("Engine $appVersion · port ${Prefs.port} · ${if (paused) "paused" else "running"}", style = MaterialTheme.typography.bodySmall)
            Text(shell, style = MaterialTheme.typography.bodyMedium)

            Button(onClick = { paused = !paused; Prefs.paused = paused; EngineService.start(this@MainActivity) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (paused) "Resume engine" else "Pause engine")
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Access token", style = MaterialTheme.typography.titleMedium)
            Text(Prefs.token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("token", Prefs.token))
                }) { Text("Copy") }
                OutlinedButton(onClick = { Prefs.newToken() }) { Text("New token") }
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Shell access (one-time pairing)", style = MaterialTheme.typography.titleMedium)
            Text("1. Open Wireless debugging and tap 'Pair device with pairing code'.\n" +
                 "2. A notification appears — type the 6-digit code into it, or come back here and enter it below.",
                style = MaterialTheme.typography.bodySmall)
            Text(pairStatus, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { Pairing.submitCode(applicationContext, -1, code) }, enabled = code.length == 6) { Text("Pair") }
            }
            OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Developer options")
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            SetupButton("Battery: allow unrestricted") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            SetupButton("Accessibility: enable screen reader") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            SetupButton("Notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            SetupButton("All files access") {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Log", style = MaterialTheme.typography.titleMedium)
            log.take(40).forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }

    @Composable
    private fun SetupButton(label: String, onClick: () -> Unit) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
}
