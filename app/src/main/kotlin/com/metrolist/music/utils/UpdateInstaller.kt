/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.metrolist.music.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** What the in-app updater is doing right now, shown by UpdateInstallHost. */
sealed interface UpdateState {
    data object Idle : UpdateState

    /** The "install unknown apps" permission is missing; the user is told why before being sent to settings. */
    data class NeedsPermission(
        val release: ReleaseInfo,
        val settingsOpened: Boolean = false,
    ) : UpdateState

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateState {
        /** 0..1, or null when the size isn't known. */
        val progress: Float?
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }
}

/**
 * Downloads the APK of a release inside the app and hands it to the system installer.
 */
object UpdateInstaller {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val NOTIFICATION_ID = 1002

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun install(context: Context, release: ReleaseInfo?) {
        val app = context.applicationContext
        val asset = release?.let { Updater.getApkAssetForCurrentVariant(it) }
        if (release == null || asset == null) {
            openReleasesPage(app)
            return
        }

        if (job?.isActive == true) return

        if (!app.packageManager.canRequestPackageInstalls()) {
            // Explain first; the permission screen is opened from the dialog
            _state.value = UpdateState.NeedsPermission(release)
            return
        }

        _state.value = UpdateState.Downloading(0L, asset.size)
        job = scope.launch {
            try {
                val file = download(app, asset)
                _state.value = UpdateState.Idle
                launchInstaller(app, file)
            } catch (e: CancellationException) {
                _state.value = UpdateState.Idle
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Update download failed")
                _state.value = UpdateState.Idle
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                }
                openReleasesPage(app)
            }
        }
    }

    /** Opens the system screen where the user allows Interlude Music to install apps. */
    fun openInstallPermissionSettings(context: Context) {
        val app = context.applicationContext
        val current = _state.value as? UpdateState.NeedsPermission ?: return
        _state.value = current.copy(settingsOpened = true)
        app.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Called when the app comes back to the foreground: starts the download if the permission was granted. */
    fun resumeAfterPermission(context: Context) {
        val current = _state.value as? UpdateState.NeedsPermission ?: return
        if (!current.settingsOpened) return
        if (context.applicationContext.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.Idle
            install(context, current.release)
        }
    }

    fun dismissPermissionPrompt() {
        if (_state.value is UpdateState.NeedsPermission) _state.value = UpdateState.Idle
    }

    fun cancelDownload() {
        job?.cancel()
    }

    private suspend fun download(context: Context, asset: ReleaseAsset): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply {
                deleteRecursively()
                mkdirs()
            }
            val file = File(dir, "Interlude-Music-update.apk")
            val connection = URL(asset.downloadUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            var finished = false
            try {
                check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size
                var downloaded = 0L
                var lastEmit = 0L
                _state.value = UpdateState.Downloading(0L, total)
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            ensureActive()
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastEmit >= 100) {
                                lastEmit = now
                                _state.value = UpdateState.Downloading(downloaded, total)
                            }
                        }
                    }
                }
                _state.value = UpdateState.Downloading(downloaded, total)
                if (asset.size > 0 && file.length() != asset.size) error("Incomplete download")
                finished = true
                file
            } finally {
                connection.disconnect()
                if (!finished) file.delete()
            }
        }

    private fun launchInstaller(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        if (isAppInForeground(context)) {
            context.startActivity(intent)
            return
        }

        // Android blocks starting the installer from the background, so ask the user to tap instead
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, "updates")
            .setSmallIcon(R.drawable.update)
            .setContentTitle(context.getString(R.string.update_ready_title))
            .setContentText(context.getString(R.string.update_ready_message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun isAppInForeground(context: Context): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun openReleasesPage(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(Updater.DOWNLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
