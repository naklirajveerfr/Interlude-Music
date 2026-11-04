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
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.metrolist.music.R
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.TimeCapsuleNotificationKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.findTimeCapsule
import com.metrolist.music.utils.get
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TimeCapsuleEntryPoint {
    fun database(): MusicDatabase
}

class TimeCapsuleWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!context.dataStore.get(TimeCapsuleNotificationKey, false)) return Result.success()

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return Result.success()

        val database = EntryPointAccessors.fromApplication(context, TimeCapsuleEntryPoint::class.java).database()
        val capsule = database.findTimeCapsule(context.dataStore.get(HideVideoSongsKey, false))
            ?: return Result.success()

        val topTitle = capsule.songs.first().title
        val text =
            if (capsule.yearsAgo == 1) {
                context.getString(R.string.time_capsule_notification_one_year, topTitle)
            } else {
                context.getString(R.string.time_capsule_notification_years, capsule.yearsAgo, topTitle)
            }
        val more = capsule.songs.take(4).joinToString(" · ") { it.title }

        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.time_capsule),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.time_capsule_channel_desc) },
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
                .setContentTitle(context.getString(R.string.time_capsule))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$more"))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build(),
        )
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "time_capsule"
        private const val NOTIFICATION_ID = 4201
    }
}

object TimeCapsuleScheduler {
    private const val WORK_NAME = "time_capsule_daily"

    /** Schedules the check for around 10:00 local time every day, or cancels it. */
    fun update(
        context: Context,
        enabled: Boolean,
    ) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(10, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request =
            PeriodicWorkRequestBuilder<TimeCapsuleWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
