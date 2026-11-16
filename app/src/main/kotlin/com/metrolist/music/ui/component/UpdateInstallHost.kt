package com.metrolist.music.ui.component

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.utils.UpdateInstaller
import com.metrolist.music.utils.UpdateState

/**
 * Shows the in-app update steps: why the install permission is needed, then the download progress.
 * Place it once near the root of the UI.
 */
@Composable
fun UpdateInstallHost() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by UpdateInstaller.state.collectAsStateWithLifecycle()

    // When the user returns from the permission screen, continue the update
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) UpdateInstaller.resumeAfterPermission(context)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (val current = state) {
        UpdateState.Idle -> Unit

        is UpdateState.NeedsPermission ->
            AlertDialog(
                onDismissRequest = UpdateInstaller::dismissPermissionPrompt,
                title = { Text(stringResource(R.string.update_permission_title)) },
                text = { Text(stringResource(R.string.update_permission_message)) },
                confirmButton = {
                    TextButton(onClick = { UpdateInstaller.openInstallPermissionSettings(context) }) {
                        Text(stringResource(R.string.update_permission_open))
                    }
                },
                dismissButton = {
                    TextButton(onClick = UpdateInstaller::dismissPermissionPrompt) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )

        is UpdateState.Downloading ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.update_downloading)) },
                text = {
                    Column {
                        val progress = current.progress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            text =
                                if (progress != null) {
                                    stringResource(
                                        R.string.update_download_progress,
                                        Formatter.formatShortFileSize(context, current.downloadedBytes),
                                        Formatter.formatShortFileSize(context, current.totalBytes),
                                        (progress * 100).toInt(),
                                    )
                                } else {
                                    stringResource(
                                        R.string.update_download_progress_unknown,
                                        Formatter.formatShortFileSize(context, current.downloadedBytes),
                                    )
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = UpdateInstaller::cancelDownload) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
    }
}
