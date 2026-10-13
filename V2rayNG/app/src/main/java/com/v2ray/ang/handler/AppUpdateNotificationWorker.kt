package com.v2ray.ang.handler

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit

class AppUpdateNotificationWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            checkAndNotify(context)
            Result.success()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "AppUpdateNotificationWorker error: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "pulse_periodic_app_update_check"
        const val EXTRA_OPEN_UPDATE = "extra_open_update"

        /**
         * Schedules periodic background update check every 2 hours with Network Connected constraint.
         */
        fun schedule(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val workRequest = PeriodicWorkRequestBuilder<AppUpdateNotificationWorker>(
                    repeatInterval = 2,
                    repeatIntervalTimeUnit = TimeUnit.HOURS,
                    flexTimeInterval = 30,
                    flexTimeIntervalUnit = TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
                LogUtil.i(AppConfig.TAG, "AppUpdateNotificationWorker: Enqueued periodic check")
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "AppUpdateNotificationWorker: Failed to schedule", e)
            }
        }

        suspend fun checkAndNotify(context: Context) {
            val result = UpdateCheckerManager.checkForUpdate()
            if (result.hasUpdate && !result.latestVersion.isNullOrBlank()) {
                val lastNotified = MmkvManager.decodeSettingsString(AppConfig.PREF_LAST_NOTIFIED_UPDATE_VERSION, "")
                if (lastNotified != result.latestVersion) {
                    showUpdateNotification(context, result.latestVersion, result.downloadUrl)
                    MmkvManager.encodeSettings(AppConfig.PREF_LAST_NOTIFIED_UPDATE_VERSION, result.latestVersion)
                }
            }
        }

        fun showUpdateNotification(context: Context, version: String, downloadUrl: String?) {
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_UPDATE, true)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                1001,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = "🚀 Доступно обновление ПУЛЬС $version"
            val text = "Вышла новая версия приложения. Нажмите для обновления."

            var directDownloadAction: NotificationCompat.Action? = null
            if (!downloadUrl.isNullOrBlank()) {
                val downloadIntent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val downloadPendingIntent = PendingIntent.getActivity(
                    context,
                    1002,
                    downloadIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                directDownloadAction = NotificationCompat.Action.Builder(
                    R.drawable.ic_cloud_download_24dp,
                    "Скачать",
                    downloadPendingIntent
                ).build()
            }

            NotificationHelper.notify(
                channelType = NotificationChannelType.APP_UPDATE,
                context = context,
                title = title,
                content = text,
                contentIntent = pendingIntent,
                action = directDownloadAction,
                highPriority = true
            )
            LogUtil.i(AppConfig.TAG, "AppUpdateNotificationWorker: Posted push notification for $version")
        }
    }
}
