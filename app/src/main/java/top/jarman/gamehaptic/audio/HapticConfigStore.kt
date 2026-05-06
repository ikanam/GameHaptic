package top.jarman.gamehaptic.audio

import android.content.Context

object HapticConfigStore {
    private const val PREFS_NAME = "haptic_config"
    private const val KEY_NOISE_GATE = "noise_gate"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_TRANSIENT_FOCUS = "transient_focus"

    fun read(context: Context): HapticConfig {
        val defaults = HapticConfig()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return HapticConfig(
            noiseGate = prefs.getFloat(KEY_NOISE_GATE, defaults.noiseGate),
            sensitivity = prefs.getFloat(KEY_SENSITIVITY, defaults.sensitivity),
            transientFocus = prefs.getFloat(KEY_TRANSIENT_FOCUS, defaults.transientFocus)
        ).normalized()
    }

    fun write(context: Context, config: HapticConfig) {
        val normalized = config.normalized()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_NOISE_GATE, normalized.noiseGate)
            .putFloat(KEY_SENSITIVITY, normalized.sensitivity)
            .putFloat(KEY_TRANSIENT_FOCUS, normalized.transientFocus)
            .apply()
    }
}
