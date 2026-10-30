package com.metrolist.music.ui.player

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.metrolist.music.R

private val SheetColor = Color(0xFF1F1F1F)
private val CardColor = Color(0xFF2A2A2A)

/** Bottom sheet opened from the output label: the current output, other paired audio devices, and a way to pair more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioOutputSheet(
    output: AudioOutput,
    nowPlaying: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val phoneLabel = stringResource(R.string.audio_output_this_phone)
    val others = remember(output) {
        val paired = pairedAudioDevices(context).filter { !output.isBluetooth || it != output.name }
        if (output.isBluetooth) listOf(AudioOutput(false, phoneLabel)) + paired.map { AudioOutput(true, it) }
        else paired.map { AudioOutput(true, it) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.audio_output_connect),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardColor).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (output.isBluetooth) output.name else phoneLabel,
                        color = Color.White,
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
                        .clickable { openOutputSwitcher(context); onDismiss() }
                        .padding(vertical = 14.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(if (device.isBluetooth) R.drawable.bluetooth else R.drawable.smartphone),
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(device.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(32.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardColor)
                    .clickable { openBluetoothSettings(context); onDismiss() }
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(painterResource(R.drawable.bluetooth), null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.audio_output_find_bluetooth),
                    color = Color.White,
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

/** Android only lets the system move audio to another output, so a tapped device opens its output picker. */
private fun openOutputSwitcher(context: Context) {
    val shown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
    if (shown) return
    runCatching {
        context.startActivity(
            Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { openBluetoothSettings(context) }
}

private fun openBluetoothSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
