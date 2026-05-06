package top.jarman.gamehaptic.service

import android.content.Context

data class CaptureState(
    val running: Boolean,
    val message: String
)

object CaptureStateStore {
    private const val PREFS_NAME = "capture_state"
    private const val KEY_RUNNING = "running"
    private const val KEY_MESSAGE = "message"

    fun read(context: Context): CaptureState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return CaptureState(
            running = prefs.getBoolean(KEY_RUNNING, false),
            message = prefs.getString(KEY_MESSAGE, "").orEmpty()
        )
    }

    fun write(context: Context, running: Boolean, message: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, running)
            .putString(KEY_MESSAGE, message)
            .apply()
    }
}
