package top.jarman.gamehaptic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import top.jarman.gamehaptic.MainActivity
import top.jarman.gamehaptic.R
import top.jarman.gamehaptic.audio.HapticAudioAnalyzer
import top.jarman.gamehaptic.audio.HapticConfig
import top.jarman.gamehaptic.audio.HapticEvent
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureService : Service() {
    private val isCapturing = AtomicBoolean(false)
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var analyzer = HapticAudioAnalyzer(SAMPLE_RATE)
    private var overlayView: View? = null
    private var latestScore = 0f
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_UPDATE_CONFIG -> {
                analyzer.updateConfig(intent.readHapticConfig())
                updateNotification()
            }
            ACTION_START -> startCapture(intent)
            else -> {
                publishState(false, CaptureStatusMessage.STOPPED)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture(publishWhenAlreadyStopped = false)
        super.onDestroy()
    }

    private fun startCapture(intent: Intent) {
        if (isCapturing.get()) {
            analyzer.updateConfig(intent.readHapticConfig())
            return
        }

        startForegroundCompat(buildNotification())

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode == 0 || resultData == null) {
            stopCapture(finalMessage = CaptureStatusMessage.NO_PROJECTION_PERMISSION)
            return
        }

        analyzer.updateConfig(intent.readHapticConfig())

        try {
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                stopCapture(finalMessage = CaptureStatusMessage.PROJECTION_NULL)
                return
            }
            mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                }
            }
            projection.registerCallback(mediaProjectionCallback!!, null)
            mediaProjection = projection

            val recorder = buildAudioRecord(projection)
            audioRecord = recorder
            isCapturing.set(true)
            showOverlay()
            publishState(true, CaptureStatusMessage.RUNNING)
            captureThread = Thread({ captureLoop(recorder) }, "GameHapticAudioCapture").also { it.start() }
        } catch (exception: SecurityException) {
            stopCapture(
                finalMessage = CaptureStatusMessage.PERMISSION_DENIED,
                finalDetail = exception.message.orEmpty()
            )
        } catch (exception: RuntimeException) {
            stopCapture(
                finalMessage = CaptureStatusMessage.START_FAILED,
                finalDetail = exception.message.orEmpty()
            )
        }
    }

    private fun buildAudioRecord(projection: MediaProjection): AudioRecord {
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val builder = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(captureConfig)

        val record = builder.build()
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            getString(R.string.service_audio_record_init_failed)
        }
        return record
    }

    private fun captureLoop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ShortArray(FRAME_SAMPLES)
        var finalMessage = CaptureStatusMessage.STOPPED
        var finalDetail = ""
        try {
            record.startRecording()
            while (isCapturing.get()) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    analyzer.process(buffer, read)?.let { event ->
                        latestScore = event.score
                        vibrate(event)
                    }
                }
            }
        } catch (exception: RuntimeException) {
            finalMessage = CaptureStatusMessage.CAPTURE_INTERRUPTED
            finalDetail = exception.message.orEmpty()
        } finally {
            stopCapture(
                finalMessage = finalMessage,
                finalDetail = finalDetail
            )
        }
    }

    private fun vibrate(event: HapticEvent) {
        val vibrator = getSystemService(Vibrator::class.java)

        if (!vibrator.hasVibrator()) return

        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(
                longArrayOf(0L, 8L, event.durationMs, 18L),
                intArrayOf(0, event.attackAmplitude, event.amplitude, event.releaseAmplitude),
                -1
            )
        } else {
            VibrationEffect.createOneShot(event.durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }

    private fun stopCapture(
        finalMessage: CaptureStatusMessage = CaptureStatusMessage.STOPPED,
        finalDetail: String = "",
        publishWhenAlreadyStopped: Boolean = true
    ) {
        if (!isCapturing.getAndSet(false) && audioRecord == null && mediaProjection == null) {
            removeOverlay()
            if (publishWhenAlreadyStopped) {
                publishState(false, finalMessage, finalDetail)
            }
            stopForegroundCompat()
            stopSelf()
            return
        }

        val threadToJoin = captureThread
        captureThread = null
        if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
            threadToJoin.interrupt()
        }

        audioRecord?.runCatching {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
            release()
        }
        audioRecord = null

        mediaProjectionCallback?.let { callback ->
            mediaProjection?.unregisterCallback(callback)
        }
        mediaProjectionCallback = null
        mediaProjection?.runCatching { stop() }
        mediaProjection = null

        removeOverlay()
        publishState(false, finalMessage, finalDetail)
        stopForegroundCompat()
        stopSelf()
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this) || overlayView != null) return

        val windowManager = getSystemService(WindowManager::class.java)
        val overlay = View(this).apply {
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        windowManager.addView(overlay, params)
        overlayView = overlay
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching {
            getSystemService(WindowManager::class.java).removeView(view)
        }
        overlayView = null
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (latestScore > 0f) {
            getString(R.string.notification_content_with_score, latestScore)
        } else {
            getString(R.string.notification_content_idle)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title_running))
            .setContentText(contentText)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_stop), stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startForegroundCompat(notification: Notification) {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        isForeground = true
    }

    private fun stopForegroundCompat() {
        if (!isForeground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun publishState(
        running: Boolean,
        message: CaptureStatusMessage,
        detail: String = ""
    ) {
        CaptureStateStore.write(this, running, message, detail)
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_MESSAGE_CODE, message.name)
                .putExtra(EXTRA_MESSAGE_DETAIL, detail)
        )
    }

    private fun Intent.readHapticConfig(): HapticConfig = HapticConfig(
        noiseGate = getFloatExtra(EXTRA_NOISE_GATE, HapticConfig().noiseGate),
        sensitivity = getFloatExtra(EXTRA_SENSITIVITY, HapticConfig().sensitivity),
        transientFocus = getFloatExtra(EXTRA_TRANSIENT_FOCUS, HapticConfig().transientFocus)
    )

    companion object {
        const val ACTION_START = "top.jarman.gamehaptic.action.START"
        const val ACTION_STOP = "top.jarman.gamehaptic.action.STOP"
        const val ACTION_UPDATE_CONFIG = "top.jarman.gamehaptic.action.UPDATE_CONFIG"
        const val ACTION_STATE = "top.jarman.gamehaptic.action.STATE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_NOISE_GATE = "noise_gate"
        const val EXTRA_SENSITIVITY = "sensitivity"
        const val EXTRA_TRANSIENT_FOCUS = "transient_focus"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_MESSAGE_CODE = "message_code"
        const val EXTRA_MESSAGE_DETAIL = "message_detail"

        private const val CHANNEL_ID = "game_haptic_capture"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 44_100
        private const val FRAME_SAMPLES = 1024
    }
}
