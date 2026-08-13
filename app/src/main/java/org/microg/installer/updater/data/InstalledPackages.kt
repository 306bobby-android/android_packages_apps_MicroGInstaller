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

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat

/** Installed-package lookups and the single definition of "needs an update". */
object InstalledPackages {

    private const val FAKE_SIGNATURE_PERMISSION = "android.permission.FAKE_PACKAGE_SIGNATURE"

    data class Installed(val versionName: String?, val versionCode: Long) {
        val displayVersion: String get() = versionName ?: versionCode.toString()
    }

    fun get(context: Context, packageName: String): Installed? {
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            Installed(info.versionName, PackageInfoCompat.getLongVersionCode(info))
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * True when [release] is strictly newer than what is installed.
     *
     * Version codes are compared when the feed exposes one, which is the only reliable
     * signal: comparing version names across components produced permanent false
     * positives, because the Companion and GmsCore are numbered independently. Strict
     * greater-than also blocks downgrades and stops a mismatch turning into a daily
     * re-download of the same build.
     */
    fun needsUpdate(installed: Installed?, release: ComponentRelease?): Boolean {
        if (release == null) return false
        if (installed == null) return true
        release.versionCode?.let { return it > installed.versionCode }
        // GsfProxy publishes no version code, so fall back to the version name. Its tag
        // and its versionName disagree on the leading "v" (tag v0.1.0 vs versionName
        // "v0.1.0" against a stripped tag), so normalise both sides or the comparison
        // reports an update forever.
        val latest = release.versionName?.removePrefix("v") ?: return false
        return latest != installed.versionName?.removePrefix("v")
    }

    /** True when com.google.android.gms is present and is microG rather than official GApps. */
    fun isMicroGInstalled(context: Context): Boolean =
        requestsFakeSignaturePermission(context) == true

    /** True when com.google.android.gms is present and is *not* microG. */
    fun isOfficialGAppsInstalled(context: Context): Boolean =
        requestsFakeSignaturePermission(context) == false

    /** null when the package is absent, otherwise whether it requests signature spoofing. */
    private fun requestsFakeSignaturePermission(context: Context): Boolean? {
        return try {
            val info = context.packageManager
                .getPackageInfo(ReleaseInfo.PACKAGE_GMS, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions?.contains(FAKE_SIGNATURE_PERMISSION) ?: false
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
