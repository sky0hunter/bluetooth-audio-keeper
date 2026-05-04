package com.shogun.btaudiokeeper

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED

class MainActivity : ComponentActivity() {

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore — service still works without notification visible */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotifPermission()
        setContent {
            BTKeeperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KeeperScreen()
                }
            }
        }
    }

    private fun maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun BTKeeperTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val isDark = (ctx.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    val colors: ColorScheme =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            if (isDark) darkColorScheme() else lightColorScheme()
        }

    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun KeeperScreen() {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(Prefs.state(ctx)) }
    var mode by remember { mutableStateOf(Prefs.mode(ctx)) }
    var amplitude by remember { mutableIntStateOf(Prefs.amplitude(ctx)) }

    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                state = Prefs.state(c)
            }
        }
        val filter = IntentFilter(Prefs.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(ctx, receiver, filter, RECEIVER_NOT_EXPORTED)
        onDispose { ctx.unregisterReceiver(receiver) }
    }

    LaunchedEffect(Unit) { state = Prefs.state(ctx) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopBar()
        Spacer(Modifier.height(32.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            ToggleOrb(state = state) {
                val running = state != Prefs.State.IDLE
                val intent = Intent(ctx, KeeperService::class.java)
                if (running) {
                    intent.action = KeeperService.ACTION_STOP
                    ctx.startService(intent)
                } else {
                    intent.action = if (mode == Prefs.Mode.AUTO)
                        KeeperService.ACTION_START_AUTO
                    else
                        KeeperService.ACTION_START_MANUAL
                    ContextCompat.startForegroundService(ctx, intent)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = ctx.getString(
                when (state) {
                    Prefs.State.IDLE -> R.string.status_idle
                    Prefs.State.WATCHING -> R.string.status_watching
                    Prefs.State.STREAMING -> R.string.status_streaming
                }
            ),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(28.dp))
        ModeCard(
            mode = mode,
            onModeChange = { newMode ->
                mode = newMode
                Prefs.setMode(ctx, newMode)
                if (state != Prefs.State.IDLE) {
                    val intent = Intent(ctx, KeeperService::class.java).apply {
                        action = if (newMode == Prefs.Mode.AUTO)
                            KeeperService.ACTION_START_AUTO
                        else
                            KeeperService.ACTION_START_MANUAL
                    }
                    ContextCompat.startForegroundService(ctx, intent)
                }
            }
        )
        Spacer(Modifier.height(16.dp))
        SignalCard(
            amplitude = amplitude,
            onChange = {
                amplitude = it
                Prefs.setAmplitude(ctx, it)
            }
        )
        Spacer(Modifier.height(16.dp))
        InfoCard()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            Icons.Filled.BluetoothAudio,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "BT Audio Keeper",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun ToggleOrb(state: Prefs.State, onClick: () -> Unit) {
    val running = state != Prefs.State.IDLE
    val streaming = state == Prefs.State.STREAMING

    val transition = rememberInfiniteTransition(label = "orb-pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (streaming) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val ringColor by animateColorAsState(
        targetValue = when (state) {
            Prefs.State.STREAMING -> MaterialTheme.colorScheme.primary
            Prefs.State.WATCHING -> MaterialTheme.colorScheme.tertiary
            Prefs.State.IDLE -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(400),
        label = "ring"
    )
    val coreColor by animateColorAsState(
        targetValue = when (state) {
            Prefs.State.STREAMING -> MaterialTheme.colorScheme.primaryContainer
            Prefs.State.WATCHING -> MaterialTheme.colorScheme.tertiaryContainer
            Prefs.State.IDLE -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(400),
        label = "core"
    )
    val iconColor by animateColorAsState(
        targetValue = when (state) {
            Prefs.State.STREAMING -> MaterialTheme.colorScheme.onPrimaryContainer
            Prefs.State.WATCHING -> MaterialTheme.colorScheme.onTertiaryContainer
            Prefs.State.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(400),
        label = "icon"
    )

    Box(
        modifier = Modifier
            .size(220.dp)
            .scale(if (streaming) pulse else 1f)
            .background(ringColor.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .background(ringColor.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(coreColor)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }
}

@Composable
private fun ModeCard(
    mode: Prefs.Mode,
    onModeChange: (Prefs.Mode) -> Unit
) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                ctx.getString(R.string.mode_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == Prefs.Mode.MANUAL,
                    onClick = { onModeChange(Prefs.Mode.MANUAL) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) {
                    Text(ctx.getString(R.string.mode_manual))
                }
                SegmentedButton(
                    selected = mode == Prefs.Mode.AUTO,
                    onClick = { onModeChange(Prefs.Mode.AUTO) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) {
                    Text(ctx.getString(R.string.mode_auto))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = ctx.getString(
                    if (mode == Prefs.Mode.AUTO) R.string.mode_auto_hint
                    else R.string.mode_manual_hint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SignalCard(amplitude: Int, onChange: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    LocalContext.current.getString(R.string.signal_level),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    amplitude.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = amplitude.toFloat(),
                onValueChange = { onChange(it.toInt().coerceAtLeast(Prefs.MIN_AMPLITUDE)) },
                valueRange = Prefs.MIN_AMPLITUDE.toFloat()..Prefs.MAX_AMPLITUDE.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
            Text(
                LocalContext.current.getString(R.string.signal_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                LocalContext.current.getString(R.string.how_it_works_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                LocalContext.current.getString(R.string.how_it_works_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
