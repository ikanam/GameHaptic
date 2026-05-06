package top.jarman.gamehaptic.service

import android.content.Context

enum class CaptureStatusMessage {
    WAITING,
    PROJECTION_CANCELLED,
    CAPTURE_STARTING,
    CAPTURE_STOPPING,
    NO_PROJECTION_PERMISSION,
    PROJECTION_NULL,
    RUNNING,
    PERMISSION_DENIED,
    START_FAILED,
    CAPTURE_INTERRUPTED,
    STOPPED
}

data class CaptureState(
    val running: Boolean,
    val message: CaptureStatusMessage,
    val detail: String
)

object CaptureStateStore {
    private const val PREFS_NAME = "capture_state"
    private const val KEY_RUNNING = "running"
    private const val KEY_MESSAGE_CODE = "message_code"
    private const val KEY_DETAIL = "detail"

    fun read(context: Context): CaptureState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val running = prefs.getBoolean(KEY_RUNNING, false)
        val message = prefs.getString(KEY_MESSAGE_CODE, null)?.let { rawValue ->
            runCatching { CaptureStatusMessage.valueOf(rawValue) }.getOrNull()
        } ?: if (running) {
            CaptureStatusMessage.RUNNING
        } else {
            CaptureStatusMessage.WAITING
        }
        return CaptureState(
            running = running,
            message = message,
            detail = prefs.getString(KEY_DETAIL, "").orEmpty()
        )
    }

    fun write(
        context: Context,
        running: Boolean,
        message: CaptureStatusMessage,
        detail: String = ""
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, running)
            .putString(KEY_MESSAGE_CODE, message.name)
            .putString(KEY_DETAIL, detail)
            .apply()
    }
}
