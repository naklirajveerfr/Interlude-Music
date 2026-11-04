/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.metrolist.music.R
import com.metrolist.music.constants.NewReleasesNotificationKey
import com.metrolist.music.constants.NewReleasesSeenKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.fetchFollowedArtistReleases
import com.metrolist.music.utils.get
import dagger.hilt.android.EntryPointAccessors

class NewReleasesWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!context.dataStore.get(NewReleasesNotificationKey, false)) return Result.success()

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return Result.success()

        val database = EntryPointAccessors.fromApplication(context, TimeCapsuleEntryPoint::class.java).database()
        val releases = fetchFollowedArtistReleases(database, maxArtists = 20)
        if (releases.isEmpty()) return Result.success()

        val seen = context.dataStore.get(NewReleasesSeenKey, "").split(",").filter { it.isNotBlank() }
        val fresh = releases.filter { it.id !in seen }
        context.dataStore.edit {
            it[NewReleasesSeenKey] = (fresh.map { album -> album.id } + seen).take(MAX_SEEN).joinToString(",")
        }
        // The first run only records what already exists, so turning the toggle on doesn't send a flood.
        if (seen.isEmpty() || fresh.isEmpty()) return Result.success()

        val first = fresh.first()
        val text =
            if (fresh.size == 1) {
                context.getString(
                    R.string.new_releases_notification_one,
                    first.artists?.firstOrNull()?.name.orEmpty(),
                    first.title,
                )
            } else {
                context.getString(R.string.new_releases_notification_many, fresh.size)
            }
        val details = fresh.take(4).joinToString("\n") { "${it.title} – ${it.artists?.firstOrNull()?.name.orEmpty()}" }

        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.new_releases_followed),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.new_releases_channel_desc) },
        )

        val launchIntent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent =
            launchIntent?.let {
                PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(context.getString(R.string.new_releases_followed))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(details))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build(),
        )
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "new_releases"
        private const val NOTIFICATION_ID = 4202
        private const val MAX_SEEN = 500
    }
}

object NewReleasesScheduler {
    fun update(
        context: Context,
        enabled: Boolean,
    ) = DailyWorkScheduler.update<NewReleasesWorker>(context, "new_releases_daily", 12, enabled)
}
