package com.qcenaman.mobiletokensaver.tools

/**
 * On-device inference via LiteRT-LM (Gemma). Loaded lazily; reuses the AI Edge Gallery's
 * downloaded model file when readable, otherwise stays unavailable until a model is provided.
 * Wired in a later step — kept as a seam so the tool exists and reports honestly.
 */
object LocalAi {
    @Volatile private var available = false
    fun run(action: String, text: String, instruction: String): String? {
        if (!available) return null
        return null
    }
}
