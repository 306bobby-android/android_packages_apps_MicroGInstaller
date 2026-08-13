/*
 * Copyright (C) 2026 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.installer.updater.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.microg.installer.updater.MainActivity
import org.microg.installer.updater.R
import org.microg.installer.updater.data.ComponentRelease
import org.microg.installer.updater.data.InstalledPackages
import org.microg.installer.updater.data.ReleaseChecker
import org.microg.installer.updater.data.ReleaseInfo
import org.microg.installer.updater.installer.SystemInstaller
import java.util.concurrent.TimeUnit

class UpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = Settings.prefs(context)
        if (!prefs.getBoolean(Settings.KEY_AUTO_CHECK, true)) {
            return Result.success()
        }

        val release = ReleaseChecker.fetchLatestRelease() ?: return Result.retry()

        // Only components that are actually present are updated in the background. A
        // component the user declined at setup must not turn into a recurring prompt to
        // install it, nor be silently installed by the auto-install path.
        val outdated = Settings.TRACKED_PACKAGES
            .mapNotNull { release.forPackage(it) }
            .filter { candidate ->
                val installed = InstalledPackages.get(context, candidate.packageName)
                installed != null && InstalledPackages.needsUpdate(installed, candidate)
            }

        if (outdated.isEmpty()) return Result.success()

        if (prefs.getBoolean(Settings.KEY_AUTO_INSTALL, false)) {
            installAll(outdated)
        } else {
            showNotification()
        }
        return Result.success()
    }

    private suspend fun installAll(components: List<ComponentRelease>) {
        for (component in components) {
            val result = SystemInstaller.downloadAndInstall(context, component)
            if (!result.succeeded) {
                Log.w(TAG, "Background update of ${component.packageName} failed: $result")
            }
        }
    }

    private fun showNotification() {
        val channelId = context.getString(R.string.notif_channel_id)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.notif_content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Shared preference contract, so the worker and the settings screen cannot drift. */
    object Settings {
        const val PREFS_NAME = "updater_settings"
        const val KEY_AUTO_CHECK = "auto_check_updates"
        const val KEY_AUTO_INSTALL = "auto_install_updates"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_FREQUENCY_HOURS = "check_frequency_hours"
        const val DEFAULT_FREQUENCY_HOURS = 24

        val TRACKED_PACKAGES = listOf(
            ReleaseInfo.PACKAGE_GMS,
            ReleaseInfo.PACKAGE_VENDING,
            ReleaseInfo.PACKAGE_GSF,
            ReleaseInfo.PACKAGE_AURORA
        )

        fun prefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "microGInstaller"
        private const val WORK_NAME = "microGUpdateCheck"
        private const val NOTIFICATION_ID = 1001

        /**
         * Applies the current preferences to the periodic check.
         *
         * [reschedule] must be true when a setting changed, so the new interval and
         * network constraint take effect; routine callers (boot, app start) leave it
         * false so that opening the app does not endlessly postpone the next run.
         */
        fun scheduleWork(context: Context, reschedule: Boolean = false) {
            val prefs = Settings.prefs(context)
            val manager = WorkManager.getInstance(context)

            if (!prefs.getBoolean(Settings.KEY_AUTO_CHECK, true)) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }

            val hours = prefs.getInt(
                Settings.KEY_FREQUENCY_HOURS,
                Settings.DEFAULT_FREQUENCY_HOURS
            ).coerceAtLeast(1).toLong()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (prefs.getBoolean(Settings.KEY_WIFI_ONLY, true)) {
                        NetworkType.UNMETERED
                    } else {
                        NetworkType.CONNECTED
                    }
                )
                .build()

            val request = PeriodicWorkRequestBuilder<UpdateWorker>(hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                if (reschedule) {
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                } else {
                    ExistingPeriodicWorkPolicy.KEEP
                },
                request
            )
        }
    }
}
