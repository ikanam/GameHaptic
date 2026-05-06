package top.jarman.gamehaptic

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import top.jarman.gamehaptic.audio.HapticConfig
import top.jarman.gamehaptic.audio.HapticConfigStore
import top.jarman.gamehaptic.service.AudioCaptureService
import top.jarman.gamehaptic.service.CaptureStatusMessage
import top.jarman.gamehaptic.service.CaptureStateStore
import top.jarman.gamehaptic.ui.theme.GameHapticTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var permissionRefresh by mutableIntStateOf(0)
    private var isServiceRunning by mutableStateOf(false)
    private var serviceMessage by mutableStateOf(CaptureStatusMessage.WAITING)
    private var serviceMessageDetail by mutableStateOf("")
    private var hapticConfig by mutableStateOf(HapticConfig())

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            startCaptureService(result.resultCode, data)
        } else {
            serviceMessage = CaptureStatusMessage.PROJECTION_CANCELLED
            serviceMessageDetail = ""
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        permissionRefresh++
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        permissionRefresh++
    }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioCaptureService.ACTION_STATE) return
            isServiceRunning = intent.getBooleanExtra(AudioCaptureService.EXTRA_RUNNING, false)
            serviceMessage = intent.getStringExtra(AudioCaptureService.EXTRA_MESSAGE_CODE)?.let { rawValue ->
                runCatching { CaptureStatusMessage.valueOf(rawValue) }.getOrNull()
            } ?: CaptureStatusMessage.WAITING
            serviceMessageDetail = intent.getStringExtra(AudioCaptureService.EXTRA_MESSAGE_DETAIL).orEmpty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hapticConfig = HapticConfigStore.read(this)
        enableEdgeToEdge()
        setContent {
            GameHapticTheme {
                GameHapticScreen(
                    permissionRefresh = permissionRefresh,
                    config = hapticConfig,
                    running = isServiceRunning,
                    message = serviceMessage,
                    messageDetail = serviceMessageDetail,
                    onConfigChange = { config ->
                        val normalized = config.normalized()
                        hapticConfig = normalized
                        HapticConfigStore.write(this, normalized)
                        updateServiceConfig(normalized)
                    },
                    onRequestAudio = { requestAudioPermission() },
                    onRequestOverlay = { openOverlaySettings() },
                    onRequestNotification = { requestNotificationPermission() },
                    onStart = { requestProjectionAndStart() },
                    onStop = { stopCaptureService() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            serviceStateReceiver,
            IntentFilter(AudioCaptureService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        syncServiceState()
    }

    override fun onResume() {
        super.onResume()
        syncServiceState()
        permissionRefresh++
    }

    override fun onStop() {
        runCatching { unregisterReceiver(serviceStateReceiver) }
        super.onStop()
    }

    private fun requestProjectionAndStart() {
        when {
            !hasRecordAudioPermission() -> requestAudioPermission()
            requiresNotificationPermission() && !hasNotificationPermission() -> requestNotificationPermission()
            !Settings.canDrawOverlays(this) -> openOverlaySettings()
            else -> {
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }
    }

    private fun requestAudioPermission() {
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestNotificationPermission() {
        if (requiresNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openOverlaySettings() {
        val uri = Uri.parse("package:$packageName")
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri))
    }

    private fun startCaptureService(resultCode: Int, resultData: Intent) {
        val intent = Intent(this, AudioCaptureService::class.java)
            .setAction(AudioCaptureService.ACTION_START)
            .putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(AudioCaptureService.EXTRA_RESULT_DATA, resultData)
            .putHapticConfig(hapticConfig)
        ContextCompat.startForegroundService(this, intent)
        isServiceRunning = true
        serviceMessage = CaptureStatusMessage.CAPTURE_STARTING
        serviceMessageDetail = ""
    }

    private fun stopCaptureService() {
        startService(
            Intent(this, AudioCaptureService::class.java)
                .setAction(AudioCaptureService.ACTION_STOP)
        )
        isServiceRunning = false
        serviceMessage = CaptureStatusMessage.CAPTURE_STOPPING
        serviceMessageDetail = ""
    }

    private fun updateServiceConfig(config: HapticConfig) {
        if (!isServiceRunning) return
        startService(
            Intent(this, AudioCaptureService::class.java)
                .setAction(AudioCaptureService.ACTION_UPDATE_CONFIG)
                .putHapticConfig(config)
        )
    }

    private fun syncServiceState() {
        val state = CaptureStateStore.read(this)
        isServiceRunning = state.running
        serviceMessage = state.message
        serviceMessageDetail = state.detail
    }

    private fun Intent.putHapticConfig(config: HapticConfig): Intent = apply {
        putExtra(AudioCaptureService.EXTRA_NOISE_GATE, config.noiseGate)
        putExtra(AudioCaptureService.EXTRA_SENSITIVITY, config.sensitivity)
        putExtra(AudioCaptureService.EXTRA_TRANSIENT_FOCUS, config.transientFocus)
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        !requiresNotificationPermission() ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requiresNotificationPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameHapticScreen(
    permissionRefresh: Int,
    config: HapticConfig,
    running: Boolean,
    message: CaptureStatusMessage,
    messageDetail: String,
    onConfigChange: (HapticConfig) -> Unit,
    onRequestAudio: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestNotification: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val hasAudio = permissionRefresh >= 0 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val hasOverlay = permissionRefresh >= 0 && Settings.canDrawOverlays(context)
    val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val hasNotification = !needsNotification ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val canStart = hasAudio && hasOverlay && hasNotification && !running

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    CaptureStatusAction(
                        canStart = canStart,
                        running = running,
                        onStart = onStart,
                        onStop = onStop
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StatusCard(
                running = running,
                message = message,
                messageDetail = messageDetail
            )

            PermissionCard(
                title = stringResource(R.string.permission_audio_title),
                description = stringResource(R.string.permission_audio_description),
                granted = hasAudio,
                actionText = stringResource(R.string.action_grant),
                onAction = onRequestAudio
            )
            PermissionCard(
                title = stringResource(R.string.permission_overlay_title),
                description = stringResource(R.string.permission_overlay_description),
                granted = hasOverlay,
                actionText = stringResource(R.string.action_open_settings),
                onAction = onRequestOverlay
            )
            if (needsNotification) {
                PermissionCard(
                    title = stringResource(R.string.permission_notification_title),
                    description = stringResource(R.string.permission_notification_description),
                    granted = hasNotification,
                    actionText = stringResource(R.string.action_grant),
                    onAction = onRequestNotification
                )
            }

            TuningCard(
                config = config,
                onConfigChange = onConfigChange
            )
        }
    }
}

@Composable
private fun CaptureStatusAction(
    canStart: Boolean,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val enabled = running || canStart
    val iconRes = when {
        running -> R.drawable.ic_action_stop
        canStart -> R.drawable.ic_action_play
        else -> R.drawable.ic_action_lock
    }
    val description = when {
        running -> stringResource(R.string.capture_action_description_running)
        canStart -> stringResource(R.string.capture_action_description_ready)
        else -> stringResource(R.string.capture_action_description_locked)
    }
    val label = when {
        running -> stringResource(R.string.action_stop)
        canStart -> stringResource(R.string.action_start)
        else -> stringResource(R.string.action_pending_authorization)
    }
    val tint = when {
        running -> MaterialTheme.colorScheme.error
        canStart -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }

    TextButton(
        enabled = enabled,
        onClick = {
            if (running) {
                onStop()
            } else {
                onStart()
            }
        }
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            tint = tint
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = tint
        )
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    message: CaptureStatusMessage,
    messageDetail: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (running) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (running) {
                    stringResource(R.string.status_running)
                } else {
                    stringResource(R.string.status_not_running)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResourceForCaptureStatus(message, messageDetail),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    actionText: String,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = if (granted) {
                        stringResource(R.string.permission_granted)
                    } else {
                        stringResource(R.string.permission_pending)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                enabled = !granted,
                onClick = onAction
            ) {
                Text(actionText)
            }
        }
    }
}

@Composable
private fun TuningCard(
    config: HapticConfig,
    onConfigChange: (HapticConfig) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.tuning_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(
                    enabled = config != HapticConfig(),
                    onClick = { onConfigChange(HapticConfig()) }
                ) {
                    Text(stringResource(R.string.action_reset))
                }
            }
            SliderRow(
                title = stringResource(R.string.tuning_noise_gate_title),
                description = stringResource(R.string.tuning_noise_gate_description),
                valueText = formatPercent(config.noiseGate / 0.18f),
                value = config.noiseGate,
                valueRange = 0.005f..0.18f,
                onValueChange = { onConfigChange(config.copy(noiseGate = it)) }
            )
            SliderRow(
                title = stringResource(R.string.tuning_sensitivity_title),
                description = stringResource(R.string.tuning_sensitivity_description),
                valueText = formatOneDecimal(config.sensitivity),
                value = config.sensitivity,
                valueRange = 0.4f..2.5f,
                onValueChange = { onConfigChange(config.copy(sensitivity = it)) }
            )
            SliderRow(
                title = stringResource(R.string.tuning_music_filter_title),
                description = stringResource(R.string.tuning_music_filter_description),
                valueText = formatPercent(config.transientFocus),
                value = config.transientFocus,
                valueRange = 0f..1f,
                onValueChange = { onConfigChange(config.copy(transientFocus = it)) }
            )
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    description: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange
        )
    }
}

private fun formatPercent(value: Float): String =
    String.format(Locale.US, "%d%%", (value.coerceIn(0f, 1f) * 100f).toInt())

private fun formatOneDecimal(value: Float): String =
    String.format(Locale.US, "%.1f", value)

@Composable
private fun stringResourceForCaptureStatus(
    message: CaptureStatusMessage,
    detail: String
): String =
    when (message) {
        CaptureStatusMessage.WAITING -> stringResource(R.string.status_waiting)
        CaptureStatusMessage.PROJECTION_CANCELLED -> stringResource(R.string.status_projection_cancelled)
        CaptureStatusMessage.CAPTURE_STARTING -> stringResource(R.string.status_capture_starting)
        CaptureStatusMessage.CAPTURE_STOPPING -> stringResource(R.string.status_capture_stopping)
        CaptureStatusMessage.NO_PROJECTION_PERMISSION -> stringResource(R.string.service_no_projection_permission)
        CaptureStatusMessage.PROJECTION_NULL -> stringResource(R.string.service_projection_null)
        CaptureStatusMessage.RUNNING -> stringResource(R.string.service_running)
        CaptureStatusMessage.PERMISSION_DENIED -> stringResource(R.string.service_permission_denied, detail)
        CaptureStatusMessage.START_FAILED -> stringResource(R.string.service_start_failed, detail)
        CaptureStatusMessage.CAPTURE_INTERRUPTED -> stringResource(R.string.service_capture_interrupted, detail)
        CaptureStatusMessage.STOPPED -> stringResource(R.string.service_stopped)
    }

@Preview(showBackground = true)
@Composable
private fun GameHapticScreenPreview() {
    GameHapticTheme {
        Surface {
            GameHapticScreen(
                permissionRefresh = 0,
                config = HapticConfig(),
                running = false,
                message = CaptureStatusMessage.WAITING,
                messageDetail = "",
                onConfigChange = {},
                onRequestAudio = {},
                onRequestOverlay = {},
                onRequestNotification = {},
                onStart = {},
                onStop = {}
            )
        }
    }
}
