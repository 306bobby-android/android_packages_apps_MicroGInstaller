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

package org.microg.installer.updater.installer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.microg.installer.updater.data.ComponentRelease
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

sealed class InstallResult {
    object Success : InstallResult()
    data class Failure(val reason: String) : InstallResult()

    val succeeded: Boolean get() = this is Success
    val failureReason: String get() = (this as? Failure)?.reason.orEmpty()
}

object SystemInstaller {

    private const val TAG = "microGInstaller"
    private const val RESULT_ACTION = "org.microg.installer.updater.INSTALL_RESULT"
    private const val USER_AGENT = "microGUpdater-App"
    private const val MAX_REDIRECTS = 5

    private val requestCodes = AtomicInteger(1)

    /**
     * Downloads [release] and installs it, suspending until the package manager reports
     * a final status.
     *
     * The returned value reflects the *install*, not the download. The previous version
     * returned true as soon as bytes had landed on disk, which made every caller's
     * failure branch unreachable and let the launcher icon be enabled for an install
     * that never happened.
     *
     * [onProgress] is always invoked on the main thread, so callers can touch views.
     */
    suspend fun downloadAndInstall(
        context: Context,
        release: ComponentRelease,
        onProgress: (Int) -> Unit = {}
    ): InstallResult {
        val apkFile = File(context.cacheDir, "${release.packageName}.apk")
        return try {
            val downloadError = download(release.url, apkFile) { percent ->
                withContext(Dispatchers.Main) { onProgress(percent) }
            }
            val rejection = if (downloadError != null) {
                downloadError
            } else {
                // Parsing the archive touches disk, so keep it off the caller's thread.
                withContext(Dispatchers.IO) {
                    SignatureVerifier.verify(context, apkFile, release.packageName)
                }
            }

            if (rejection != null) {
                Log.e(TAG, "Not installing ${release.packageName}: $rejection")
                InstallResult.Failure(rejection)
            } else {
                install(context, apkFile, release.packageName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Install of ${release.packageName} threw", e)
            InstallResult.Failure(e.message ?: e.javaClass.simpleName)
        } finally {
            // Each APK is tens of megabytes; never leave one behind in the cache.
            apkFile.delete()
        }
    }

    /** Returns null on success, or a human-readable reason on failure. */
    private suspend fun download(
        apkUrl: String,
        destination: File,
        onProgress: suspend (Int) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            var currentUrl = apkUrl
            var redirects = 0

            while (true) {
                connection?.disconnect()
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                connection.connect()

                val code = connection.responseCode
                val isRedirect = code == HttpURLConnection.HTTP_MOVED_PERM ||
                        code == HttpURLConnection.HTTP_MOVED_TEMP ||
                        code == HttpURLConnection.HTTP_SEE_OTHER ||
                        code == 307 || code == 308
                // HttpURLConnection declines to follow redirects that change protocol,
                // which the GitHub and F-Droid CDNs both do.
                val location = if (isRedirect) connection.getHeaderField("Location") else null
                if (location == null || redirects >= MAX_REDIRECTS) {
                    if (code != HttpURLConnection.HTTP_OK) {
                        return@withContext "Download failed with HTTP $code"
                    }
                    break
                }
                currentUrl = URL(URL(currentUrl), location).toString()
                redirects++
            }

            val active = connection!!
            val expectedLength = active.contentLengthLong
            var total = 0L
            var lastPercent = -1

            active.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        total += count
                        if (expectedLength > 0) {
                            val percent = (total * 100 / expectedLength).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }

            if (expectedLength > 0 && total != expectedLength) {
                return@withContext "Download truncated at $total of $expectedLength bytes"
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Download of $apkUrl failed", e)
            e.message ?: "Download failed"
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun install(
        context: Context,
        apkFile: File,
        packageName: String
    ): InstallResult {
        val appContext = context.applicationContext
        val installer = appContext.packageManager.packageInstaller

        // Staging copies the whole APK, so it must not run on the caller's thread.
        val sessionId = withContext(Dispatchers.IO) {
            try {
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                ).apply {
                    setAppPackageName(packageName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(
                            PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                        )
                    }
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite(packageName, 0, apkFile.length()).use { output ->
                        apkFile.inputStream().use { input -> input.copyTo(output) }
                        session.fsync(output)
                    }
                }
                id
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stage session for $packageName", e)
                -1
            }
        }

        if (sessionId == -1) {
            return InstallResult.Failure("Could not stage install session")
        }

        return awaitCommit(appContext, installer, sessionId, packageName)
    }

    /**
     * Commits the staged session and waits for its status broadcast.
     *
     * The receiver is registered at runtime against a per-session action. The status
     * PendingIntent previously pointed at a receiver class that was never declared in
     * the manifest, so no result was ever delivered anywhere.
     */
    private suspend fun awaitCommit(
        appContext: Context,
        installer: PackageInstaller,
        sessionId: Int,
        packageName: String
    ): InstallResult = suspendCancellableCoroutine { continuation ->
        val action = "$RESULT_ACTION.$packageName.$sessionId"

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                )
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // Not terminal: confirm, then wait for the real status to arrive.
                    val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    }
                    if (confirmation == null) {
                        finish(InstallResult.Failure("Confirmation required but not provided"))
                    } else {
                        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { appContext.startActivity(confirmation) }
                            .onFailure { finish(InstallResult.Failure("Cannot confirm install")) }
                    }
                    return
                }

                if (status == PackageInstaller.STATUS_SUCCESS) {
                    Log.d(TAG, "Installed $packageName")
                    finish(InstallResult.Success)
                } else {
                    Log.e(TAG, "Install of $packageName failed: $message (status $status)")
                    finish(InstallResult.Failure(message ?: "Install failed with status $status"))
                }
            }

            private fun finish(result: InstallResult) {
                runCatching { appContext.unregisterReceiver(this) }
                if (continuation.isActive) continuation.resume(result)
            }
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        continuation.invokeOnCancellation {
            runCatching { appContext.unregisterReceiver(receiver) }
            runCatching { installer.abandonSession(sessionId) }
        }

        try {
            val statusIntent = Intent(action).setPackage(appContext.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                requestCodes.getAndIncrement(),
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            installer.openSession(sessionId).use { session ->
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to commit session for $packageName", e)
            runCatching { installer.abandonSession(sessionId) }
            runCatching { appContext.unregisterReceiver(receiver) }
            if (continuation.isActive) {
                continuation.resume(
                    InstallResult.Failure(e.message ?: "Could not commit install session")
                )
            }
        }
    }
}
