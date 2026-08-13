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

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import org.microg.installer.updater.data.ReleaseInfo
import java.io.File
import java.security.MessageDigest

/**
 * Pins the signing certificate of every APK this app installs.
 *
 * This app holds INSTALL_PACKAGES and installs into com.google.android.gms, the one
 * package the ROM grants signature spoofing to. Without a pin, whatever bytes the
 * release feed hands back become system-blessed Google Play Services on first install,
 * where there is no already-installed signature for the platform to compare against.
 *
 * Digests are the SHA-256 of the DER-encoded signing certificate, which is exactly what
 * [Signature.toByteArray] returns, and were taken from the published release artifacts.
 * A key rotation upstream will fail closed here and requires updating these constants.
 */
object SignatureVerifier {

    private const val TAG = "microGInstaller"

    /**
     * C=DE, O=NOGAPPS Project, valid to 2037-09-30.
     * microG signs GmsCore, the Companion and GsfProxy with this single key.
     */
    private const val MICROG_NOGAPPS =
        "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165"

    /**
     * C=UK, O=fdroid.org, CN=FDroid, valid to 2046-08-29.
     * F-Droid re-signs the Aurora Store builds it serves, so this is F-Droid's key and
     * not AuroraOSS's; sourcing Aurora from elsewhere would need a different pin.
     */
    private const val FDROID =
        "5c83c7672b929955dc0a1db89a5e6ae4389e2eae7ec939956041694e5815f532"

    private val PINNED_SIGNERS: Map<String, Set<String>> = mapOf(
        ReleaseInfo.PACKAGE_GMS to setOf(MICROG_NOGAPPS),
        ReleaseInfo.PACKAGE_VENDING to setOf(MICROG_NOGAPPS),
        ReleaseInfo.PACKAGE_GSF to setOf(MICROG_NOGAPPS),
        ReleaseInfo.PACKAGE_AURORA to setOf(FDROID)
    )

    /** Returns null when [apk] may be installed, otherwise the reason to refuse it. */
    fun verify(context: Context, apk: File, expectedPackage: String): String? {
        // Fail closed: a package with no pin is never installed.
        val pinned = PINNED_SIGNERS[expectedPackage]
            ?: return "No pinned signing certificate for $expectedPackage"

        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return "Downloaded file is not a readable APK"

        // A correctly signed APK for the wrong package would otherwise be installed
        // under whatever name it declares.
        if (info.packageName != expectedPackage) {
            return "APK declares ${info.packageName}, expected $expectedPackage"
        }

        val signers = signersOf(info)
        if (signers.isEmpty()) {
            return "APK is unsigned"
        }

        // Every signer must be pinned, not merely one of them.
        val actual = signers.map { sha256(it.toByteArray()) }
        val untrusted = actual.filterNot { pinned.contains(it) }
        if (untrusted.isNotEmpty()) {
            Log.e(
                TAG,
                "Refusing $expectedPackage: untrusted signer(s) ${untrusted.joinToString()}"
            )
            return "APK is signed by an untrusted certificate"
        }

        Log.d(TAG, "Signature pin satisfied for $expectedPackage")
        return null
    }

    private fun signersOf(info: PackageInfo): List<Signature> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Who actually signed these APK contents, as opposed to any rotation history.
            return info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        }
        @Suppress("DEPRECATION")
        return info.signatures?.toList().orEmpty()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
