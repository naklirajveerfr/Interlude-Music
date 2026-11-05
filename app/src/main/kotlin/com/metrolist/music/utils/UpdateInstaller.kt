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
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.metrolist.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the APK of a release inside the app and hands it to the system installer.
 */
object UpdateInstaller {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val NOTIFICATION_ID = 1002

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun install(context: Context, release: ReleaseInfo?) {
        val app = context.applicationContext
        val asset = release?.let { Updater.getApkAssetForCurrentVariant(it) }
        if (asset == null) {
            openReleasesPage(app)
            return
        }

        if (!app.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(app, R.string.update_allow_install, Toast.LENGTH_LONG).show()
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }

        if (job?.isActive == true) return
        Toast.makeText(app, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        job = scope.launch {
            runCatching { download(app, asset) }
                .onSuccess { launchInstaller(app, it) }
                .onFailure {
                    Timber.e(it, "Update download failed")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                    }
                    openReleasesPage(app)
                }
        }
    }

    private fun download(context: Context, asset: ReleaseAsset): File {
        val dir = File(context.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, "Interlude-Music-update.apk")
        val connection = URL(asset.downloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
        if (asset.size > 0 && file.length() != asset.size) {
            file.delete()
            error("Incomplete download")
        }
        return file
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
