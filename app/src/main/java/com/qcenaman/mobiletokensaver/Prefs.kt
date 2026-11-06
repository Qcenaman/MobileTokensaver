package com.qcenaman.mobiletokensaver

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/** Settings and the access token, kept in EncryptedSharedPreferences. Call init() once from App. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        tokenFile = java.io.File(ctx.filesDir, "token")
        sp = try {
            EncryptedSharedPreferences.create(
                ctx, "secure",
                MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            ctx.getSharedPreferences("plain_fallback", Context.MODE_PRIVATE)
        }
        token
    }

    private lateinit var tokenFile: java.io.File

    val token: String
        get() = sp.getString("token", null)?.also { mirror(it) } ?: newToken()

    fun newToken(): String {
        val b = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val t = b.joinToString("") { "%02x".format(it) }
        sp.edit().putString("token", t).apply()
        mirror(t)
        return t
    }

    /** Private copy for `adb shell run-as` (adb already outranks the token). */
    private fun mirror(t: String) {
        if (!::tokenFile.isInitialized) return
        if (!tokenFile.exists() || tokenFile.readText() != t) runCatching { tokenFile.writeText(t) }
    }

    var paused: Boolean
        get() = sp.getBoolean("paused", false)
        set(v) = sp.edit().putBoolean("paused", v).apply()
    var killed: Boolean
        get() = sp.getBoolean("killed", false)
        set(v) = sp.edit().putBoolean("killed", v).apply()
    var stayAwake: Boolean
        get() = sp.getBoolean("stay_awake", true)
        set(v) = sp.edit().putBoolean("stay_awake", v).apply()
    var port: Int
        get() = sp.getInt("port", 8765)
        set(v) = sp.edit().putInt("port", v).apply()
    var setupDone: Boolean
        get() = sp.getBoolean("setup_done", false)
        set(v) = sp.edit().putBoolean("setup_done", v).apply()

    var pcLinkJson: String?
        get() = sp.getString("pc_link", null)
        set(v) = sp.edit().putString("pc_link", v).apply()

    /** Routes typed on the phone ({"public_host":…,"tunnel_url":…}); win over what the PC sent. */
    var pcRouteJson: String?
        get() = sp.getString("pc_route", null)
        set(v) = sp.edit().putString("pc_route", v).apply()

    fun toolEnabled(name: String) = sp.getBoolean("tool_$name", true)
    fun setToolEnabled(name: String, v: Boolean) = sp.edit().putBoolean("tool_$name", v).apply()
}
