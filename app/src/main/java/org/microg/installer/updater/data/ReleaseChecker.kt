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

package org.microg.installer.updater.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ReleaseChecker {

    private const val TAG = "microGInstaller"
    private const val USER_AGENT = "microGUpdater-App"
    private const val GITHUB_ACCEPT = "application/vnd.github.v3+json"

    private const val GMS_RELEASES_API =
        "https://api.github.com/repos/microg/GmsCore/releases/latest"
    private const val GSF_RELEASES_API =
        "https://api.github.com/repos/microg/GsfProxy/releases/latest"
    private const val FDROID_AURORA_API =
        "https://f-droid.org/api/v1/packages/com.aurora.store"
    private const val FDROID_REPO = "https://f-droid.org/repo"

    /**
     * Matches the versioned APKs in a microG release and nothing else.
     *
     * A release contains com.google.android.gms-250932030.apk alongside a -hw variant,
     * detached .asc signatures and an org.microg.gms-*-user.apk build. Anchoring the
     * version code directly against .apk excludes all three, where the previous
     * substring filters let asset renames through silently.
     */
    private val MICROG_ASSET = Regex("""^(com\.google\.android\.gms|com\.android\.vending)-(\d+)\.apk$""")

    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val microG = fetchMicroGRelease()
        val aurora = fetchAuroraRelease()
        val gsf = fetchGsfRelease()

        if (microG == null && aurora == null && gsf == null) {
            return@withContext null
        }

        ReleaseInfo(
            tagName = microG?.tag ?: "",
            gms = microG?.gms,
            vending = microG?.vending,
            aurora = aurora,
            gsf = gsf
        )
    }

    private class MicroGRelease(
        val tag: String,
        val gms: ComponentRelease?,
        val vending: ComponentRelease?
    )

    private fun fetchMicroGRelease(): MicroGRelease? {
        val body = httpGetString(GMS_RELEASES_API, GITHUB_ACCEPT) ?: return null
        return try {
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").removePrefix("v")
            val assets: JSONArray = json.optJSONArray("assets") ?: JSONArray()

            var gms: ComponentRelease? = null
            var vending: ComponentRelease? = null

            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val match = MICROG_ASSET.matchEntire(asset.optString("name", "")) ?: continue
                val url = asset.optString("browser_download_url", "")
                if (url.isEmpty()) continue

                val packageName = match.groupValues[1]
                val versionCode = match.groupValues[2].toLongOrNull() ?: continue

                when (packageName) {
                    ReleaseInfo.PACKAGE_GMS -> gms = ComponentRelease(
                        packageName = packageName,
                        url = url,
                        // The release tag tracks GmsCore, so it is a valid name here only.
                        versionName = tag.ifEmpty { null },
                        versionCode = versionCode
                    )
                    ReleaseInfo.PACKAGE_VENDING -> vending = ComponentRelease(
                        packageName = packageName,
                        url = url,
                        // The Companion is versioned independently and the feed exposes
                        // no name for it, so the version code is the only truth.
                        versionName = null,
                        versionCode = versionCode
                    )
                }
            }

            if (gms == null && vending == null) {
                Log.w(TAG, "microG release $tag matched no known assets")
                return null
            }
            MicroGRelease(tag, gms, vending)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse microG release", e)
            null
        }
    }

    private fun fetchAuroraRelease(): ComponentRelease? {
        val body = httpGetString(FDROID_AURORA_API) ?: return null
        return try {
            val json = JSONObject(body)
            // suggestedVersionCode is F-Droid's own pick of the current build; the
            // packages array is not contractually ordered.
            val versionCode = json.optLong("suggestedVersionCode", 0L)
            if (versionCode <= 0L) return null

            val packages = json.optJSONArray("packages") ?: JSONArray()
            var versionName: String? = null
            for (i in 0 until packages.length()) {
                val entry = packages.optJSONObject(i) ?: continue
                if (entry.optLong("versionCode", -1L) == versionCode) {
                    versionName = entry.optString("versionName", "").ifEmpty { null }
                    break
                }
            }

            ComponentRelease(
                packageName = ReleaseInfo.PACKAGE_AURORA,
                url = "$FDROID_REPO/${ReleaseInfo.PACKAGE_AURORA}_$versionCode.apk",
                versionName = versionName,
                versionCode = versionCode
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Aurora Store release", e)
            null
        }
    }

    private fun fetchGsfRelease(): ComponentRelease? {
        val body = httpGetString(GSF_RELEASES_API, GITHUB_ACCEPT) ?: return null
        return try {
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").removePrefix("v")
            val assets: JSONArray = json.optJSONArray("assets") ?: JSONArray()

            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (!asset.optString("name", "").endsWith(".apk")) continue
                val url = asset.optString("browser_download_url", "")
                if (url.isEmpty()) continue

                return ComponentRelease(
                    packageName = ReleaseInfo.PACKAGE_GSF,
                    url = url,
                    versionName = tag.ifEmpty { null },
                    // GsfProxy ships an unversioned GsfProxy.apk, so there is no code to
                    // read without downloading it first; comparison falls back to name.
                    versionCode = null
                )
            }
            Log.w(TAG, "GsfProxy release $tag had no apk asset")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse GsfProxy release", e)
            null
        }
    }

    private fun httpGetString(spec: String, accept: String? = null): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(spec).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                accept?.let { setRequestProperty("Accept", it) }
                connectTimeout = 10000
                readTimeout = 10000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "GET $spec returned HTTP ${connection.responseCode}")
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $spec failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
