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

/**
 * A single installable component resolved from an upstream release feed.
 *
 * Every component carries its own version. The microG release tag tracks GmsCore only:
 * a v0.3.15.250932 release ships com.google.android.gms-250932030.apk next to
 * com.android.vending-84022630.apk, so the Companion cannot be versioned off the tag.
 */
data class ComponentRelease(
    val packageName: String,
    val url: String,
    val versionName: String?,
    /** Upstream version code, or null when the feed does not expose one (GsfProxy). */
    val versionCode: Long?
) {
    /** Human-readable version for the UI, preferring a real version name. */
    val displayVersion: String
        get() = versionName ?: versionCode?.toString() ?: "N/A"
}

data class ReleaseInfo(
    val tagName: String,
    val gms: ComponentRelease?,
    val vending: ComponentRelease?,
    val aurora: ComponentRelease?,
    val gsf: ComponentRelease?
) {
    fun forPackage(packageName: String): ComponentRelease? = when (packageName) {
        PACKAGE_GMS -> gms
        PACKAGE_VENDING -> vending
        PACKAGE_AURORA -> aurora
        PACKAGE_GSF -> gsf
        else -> null
    }

    companion object {
        const val PACKAGE_GMS = "com.google.android.gms"
        const val PACKAGE_VENDING = "com.android.vending"
        const val PACKAGE_AURORA = "com.aurora.store"
        const val PACKAGE_GSF = "com.google.android.gsf"

        /** Shipped as a ROM prebuilt, never downloaded. */
        const val PACKAGE_AURORA_SERVICES = "com.aurora.services"
    }
}
