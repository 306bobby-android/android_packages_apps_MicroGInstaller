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

package org.microg.installer.updater

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.microg.installer.updater.data.ComponentRelease
import org.microg.installer.updater.data.InstalledPackages
import org.microg.installer.updater.data.ReleaseChecker
import org.microg.installer.updater.data.ReleaseInfo
import org.microg.installer.updater.installer.SystemInstaller

class SetupWizardActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "microGInstaller"

        const val EXTRA_INCLUDE_AURORA = "include_aurora"
        const val EXTRA_INCLUDE_GSF = "include_gsf"
        const val EXTRA_INCLUDE_AURORA_SERVICES = "include_aurora_services"
    }

    private data class SetupOptions(
        val includeAurora: Boolean,
        val includeGsf: Boolean,
        val includeAuroraServices: Boolean
    )

    private lateinit var wizardTitle: TextView
    private lateinit var wizardSubtitle: TextView
    private lateinit var infoBoxTitle: TextView
    private lateinit var infoBoxDescription: TextView
    private lateinit var optionsContainer: View
    private lateinit var progressContainer: View
    private lateinit var progressText: TextView
    private lateinit var btnPrimaryAction: MaterialButton
    private lateinit var btnSecondaryAction: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_MicroGUpdater)
        super.onCreate(savedInstanceState)

        // Any pre-existing com.google.android.gms ends setup before it starts. Official
        // GApps means microG must not be installed at all; an existing microG means this
        // installer did not put it there, so it must not install anything or claim the
        // launcher entry. The wizard gates on the same condition and normally skips this
        // activity outright -- this is the direct-launch backstop.
        if (InstalledPackages.get(this, ReleaseInfo.PACKAGE_GMS) != null) {
            Log.d(TAG, "GMS already present, leaving setup untouched")
            finishSetupWizard()
            return
        }

        setContentView(R.layout.activity_setup_wizard)

        val rootLayout = findViewById<View>(R.id.wizardRootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        wizardTitle = findViewById(R.id.wizardTitle)
        wizardSubtitle = findViewById(R.id.wizardSubtitle)
        infoBoxTitle = findViewById(R.id.infoBoxTitle)
        infoBoxDescription = findViewById(R.id.infoBoxDescription)
        optionsContainer = findViewById(R.id.optionsContainer)
        progressContainer = findViewById(R.id.progressContainer)
        progressText = findViewById(R.id.progressText)
        btnPrimaryAction = findViewById(R.id.btnPrimaryAction)
        btnSecondaryAction = findViewById(R.id.btnSecondaryAction)

        val options = SetupOptions(
            includeAurora = intent.getBooleanExtra(EXTRA_INCLUDE_AURORA, true),
            includeGsf = intent.getBooleanExtra(EXTRA_INCLUDE_GSF, false),
            includeAuroraServices = intent.getBooleanExtra(EXTRA_INCLUDE_AURORA_SERVICES, false)
        )
        startMicroGInstallation(options)
    }

    private fun finishSetupWizard(resultCode: Int = Activity.RESULT_OK) {
        setResult(resultCode)
        finish()
    }

    /**
     * The launcher entry exists only once this installer has actually installed microG.
     * It is never enabled off a pre-existing install or a download that failed to commit.
     */
    private fun setAppLauncherEnabled(enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                state,
                PackageManager.DONT_KILL_APP
            )
        }.onFailure { Log.w(TAG, "Could not set launcher state", it) }
    }

    /**
     * Aurora Services ships as a privileged ROM prebuilt because it needs INSTALL_PACKAGES
     * to do anything; it is toggled here rather than downloaded.
     */
    private fun setAuroraServicesEnabled(enabled: Boolean) {
        if (InstalledPackages.get(this, ReleaseInfo.PACKAGE_AURORA_SERVICES) == null) {
            Log.d(TAG, "Aurora Services prebuilt not present, nothing to toggle")
            return
        }
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            packageManager.setApplicationEnabledSetting(
                ReleaseInfo.PACKAGE_AURORA_SERVICES,
                state,
                0
            )
        }.onFailure { Log.w(TAG, "Could not toggle Aurora Services", it) }
    }

    private fun startMicroGInstallation(options: SetupOptions) {
        optionsContainer.visibility = View.GONE
        progressContainer.visibility = View.VISIBLE
        btnPrimaryAction.isEnabled = false
        btnSecondaryAction.visibility = View.GONE

        lifecycleScope.launch {
            progressText.text = getString(R.string.setup_checking_release)
            val release = ReleaseChecker.fetchLatestRelease()

            if (release?.gms == null) {
                showRecoverableState(
                    title = getString(R.string.setup_paused_title),
                    description = getString(R.string.setup_paused_desc),
                    options = options
                )
                return@launch
            }

            // GmsCore is the one component that must land for setup to have meant anything.
            val gmsResult = SystemInstaller.downloadAndInstall(this@SetupWizardActivity, release.gms) {
                progressText.text = getString(R.string.setup_downloading_gms, it)
            }
            if (!gmsResult.succeeded) {
                showRecoverableState(
                    title = getString(R.string.setup_failed_title),
                    description = getString(R.string.setup_failed_desc),
                    options = options
                )
                return@launch
            }

            val installed = mutableListOf(getString(R.string.setup_summary_gms))

            if (installComponent(release.vending, R.string.setup_downloading_vending)) {
                installed.add(getString(R.string.setup_summary_vending))
            }
            if (options.includeGsf &&
                installComponent(release.gsf, R.string.setup_downloading_gsf)
            ) {
                installed.add(getString(R.string.setup_summary_gsf))
            }
            val auroraInstalled = options.includeAurora &&
                installComponent(release.aurora, R.string.setup_downloading_aurora)
            if (auroraInstalled) {
                installed.add(getString(R.string.setup_summary_aurora))
            }

            // Reconcile the prebuilt with the choice rather than only ever enabling it.
            // It ships enabled, so leaving the box unchecked has to actively turn it off
            // or the checkbox would appear to do nothing. It is also useless on its own,
            // hence the dependency on Aurora Store having landed.
            val enableCompanion = auroraInstalled && options.includeAuroraServices
            if (enableCompanion) {
                progressText.text = getString(R.string.setup_enabling_aurora_services)
                installed.add(getString(R.string.setup_summary_aurora_services))
            }
            setAuroraServicesEnabled(enableCompanion)

            // microG is genuinely installed by us at this point, so surface the updater.
            setAppLauncherEnabled(true)
            showCompleteState(installed)
        }
    }

    /** Best-effort install of a secondary component; failures never block setup. */
    private suspend fun installComponent(
        release: ComponentRelease?,
        progressRes: Int
    ): Boolean {
        if (release == null) return false
        progressText.text = getString(progressRes, 0)
        val result = SystemInstaller.downloadAndInstall(this, release) {
            progressText.text = getString(progressRes, it)
        }
        if (!result.succeeded) {
            Log.w(TAG, "Optional component ${release.packageName} did not install: $result")
        }
        return result.succeeded
    }

    private fun showRecoverableState(title: String, description: String, options: SetupOptions) {
        optionsContainer.visibility = View.VISIBLE
        progressContainer.visibility = View.GONE

        infoBoxTitle.text = title
        infoBoxDescription.text = description

        btnPrimaryAction.text = getString(R.string.btn_retry_install)
        btnPrimaryAction.isEnabled = true
        btnPrimaryAction.setOnClickListener { startMicroGInstallation(options) }

        btnSecondaryAction.text = getString(R.string.btn_skip_for_now)
        btnSecondaryAction.visibility = View.VISIBLE
        btnSecondaryAction.setOnClickListener { finishSetupWizard() }
    }

    private fun showCompleteState(installed: List<String>) {
        progressContainer.visibility = View.GONE
        optionsContainer.visibility = View.VISIBLE

        wizardTitle.text = getString(R.string.setup_complete_title)
        wizardSubtitle.text = getString(R.string.setup_complete_desc)
        infoBoxTitle.text = getString(R.string.setup_complete_title)
        infoBoxDescription.text = installed.joinToString("\n") { getString(R.string.setup_bullet, it) }

        btnPrimaryAction.isEnabled = true
        btnPrimaryAction.text = getString(R.string.btn_finish_setup)
        btnPrimaryAction.setOnClickListener { finishSetupWizard() }
        btnSecondaryAction.visibility = View.GONE
    }
}
