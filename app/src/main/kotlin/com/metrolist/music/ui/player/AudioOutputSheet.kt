package com.metrolist.music.ui.player

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Content for the app's menu sheet (it follows the Menu background and Frosted glass settings):
 * the current output, other paired audio devices, and a way to pair more.
 */
@Composable
internal fun ColumnScope.AudioOutputMenuContent(
    nowPlaying: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val output = rememberAudioOutput()
    val player = LocalPlayerConnection.current?.service?.player
    val phoneLabel = stringResource(R.string.audio_output_this_phone)
    val scope = rememberCoroutineScope()
    var connecting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(output) { connecting = null }
    val others = remember(output) {
        val paired = pairedAudioDevices(context).filter { !output.isBluetooth || it != output.name }
        if (output.isBluetooth) listOf(AudioOutput(false, phoneLabel)) + paired.map { AudioOutput(true, it) }
        else paired.map { AudioOutput(true, it) }
    }

    run {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 20.dp)) {
            Text(
                stringResource(R.string.audio_output_connect),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (output.isBluetooth) output.name else phoneLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (nowPlaying.isNotBlank()) {
                        Text(
                            nowPlaying,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    painterResource(if (output.isBluetooth) R.drawable.bluetooth else R.drawable.smartphone),
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            others.forEach { device ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = connecting == null) {
                            connecting = device.name
                            scope.launch {
                                if (switchTo(context, player, device)) {
                                    // The top card updates by itself once the new output shows up.
                                    delay(8_000)
                                    if (connecting == device.name) connecting = null
                                } else {
                                    connecting = null
                                    Toast.makeText(context, R.string.audio_output_connect_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(vertical = 14.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(if (device.isBluetooth) R.drawable.bluetooth else R.drawable.smartphone),
                        null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(device.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (connecting == device.name) {
                            Text(
                                stringResource(R.string.audio_output_connecting),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f))
                    .clickable { openBluetoothSettings(context); onDismiss() }
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(painterResource(R.drawable.bluetooth), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.audio_output_find_bluetooth),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Names of paired Bluetooth audio devices. Empty without the Bluetooth permission. */
@SuppressLint("MissingPermission")
private fun pairedAudioDevices(context: Context): List<String> = runCatching {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    adapter?.bondedDevices.orEmpty()
        .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO }
        .mapNotNull { it.name?.takeIf(String::isNotBlank) }
        .distinct()
}.getOrDefault(emptyList())

/** Set when the user picked "This phone" while Bluetooth is connected, so the label matches where the audio goes. */
internal object OutputRoute {
    val forcedPhone = MutableStateFlow(false)
}

private fun isBluetoothOutput(device: AudioDeviceInfo): Boolean =
    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))

/**
 * Moves the app's audio to [target]. A connected output is chosen on the player itself; a paired
 * Bluetooth device that is not connected gets an A2DP connect request. Returns false when neither worked.
 */
private suspend fun switchTo(context: Context, player: ExoPlayer?, target: AudioOutput): Boolean =
    withContext(Dispatchers.Main) {
        if (player != null && routeTo(context, player, target)) return@withContext true
        if (!target.isBluetooth || !connectA2dp(context, target.name)) return@withContext false
        // Let the system pick the newly connected device.
        player?.setPreferredAudioDevice(null)
        OutputRoute.forcedPhone.value = false
        true
    }

private fun routeTo(context: Context, player: ExoPlayer, target: AudioOutput): Boolean {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    if (!target.isBluetooth) {
        val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return false
        player.setPreferredAudioDevice(speaker)
        OutputRoute.forcedPhone.value = true
        return true
    }
    val connected = devices.filter(::isBluetoothOutput)
    val device = connected.firstOrNull { it.productName?.toString() == target.name } ?: connected.singleOrNull()
        ?: return false
    player.setPreferredAudioDevice(device)
    OutputRoute.forcedPhone.value = false
    return true
}

/** Asks the Bluetooth stack to connect the paired device. Hidden API, so it can fail on newer Android versions. */
@SuppressLint("MissingPermission", "DiscouragedPrivateApi")
private suspend fun connectA2dp(context: Context, name: String): Boolean = withContext(Dispatchers.Main) {
    runCatching {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return@runCatching false
        val device = adapter.bondedDevices.firstOrNull { it.name == name } ?: return@runCatching false
        val proxy = withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine<BluetoothProfile?> { cont ->
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (cont.isActive) cont.resume(proxy)
                    }

                    override fun onServiceDisconnected(profile: Int) = Unit
                }
                if (!adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP) && cont.isActive) cont.resume(null)
            }
        } ?: return@runCatching false
        try {
            proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, device) as? Boolean ?: false
        } finally {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
        }
    }.getOrDefault(false)
}

private fun openBluetoothSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
