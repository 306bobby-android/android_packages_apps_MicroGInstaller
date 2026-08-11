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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.setupcompat.util.WizardManagerHelper
import kotlinx.coroutines.launch
import org.microg.installer.updater.data.ReleaseChecker
import org.microg.installer.updater.installer.SystemInstaller

class SetupWizardActivity : AppCompatActivity() {

    private lateinit var wizardTitle: TextView
    private lateinit var wizardSubtitle: TextView
    private lateinit var infoBoxTitle: TextView
    private lateinit var infoBoxDescription: TextView
    private lateinit var optionsContainer: View
    private lateinit var progressContainer: View
    private lateinit var progressText: TextView
    private lateinit var btnPrimaryAction: MaterialButton
    private lateinit var btnSecondaryAction: MaterialButton
    private lateinit var cbIncludeAurora: MaterialCheckBox
    private lateinit var auroraDescText: TextView
    private lateinit var auroraDivider: View

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_MicroGUpdater)
        super.onCreate(savedInstanceState)

        if (isOfficialGAppsInstalled()) {
            setAppLauncherEnabled(false)
            finishSetupWizard(Activity.RESULT_OK)
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
        cbIncludeAurora = findViewById(R.id.cbIncludeAurora)
        auroraDescText = findViewById(R.id.auroraDescText)
        auroraDivider = findViewById(R.id.auroraDivider)

        val isMicroGInstalled = isMicroGInstalled()

        if (isMicroGInstalled) {
            setAppLauncherEnabled(true)
            setupMicroGDetectedUi()
        } else {
            setupCleanInstallUi()
        }
    }

    private fun finishSetupWizard(resultCode: Int = Activity.RESULT_OK) {
        setResult(resultCode)
        finish()
    }

    private fun setAppLauncherEnabled(enabled: Boolean) {
        val componentName = ComponentName(this, MainActivity::class.java)
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        packageManager.setComponentEnabledSetting(
            componentName,
            state,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun isOfficialGAppsInstalled(): Boolean {
        return try {
            val pInfo = packageManager.getPackageInfo("com.google.android.gms", PackageManager.GET_PERMISSIONS)
            val permissions = pInfo.requestedPermissions ?: arrayOf()
            !permissions.contains("android.permission.FAKE_PACKAGE_SIGNATURE")
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isMicroGInstalled(): Boolean {
        return try {
            val pInfo = packageManager.getPackageInfo("com.google.android.gms", PackageManager.GET_PERMISSIONS)
            val permissions = pInfo.requestedPermissions ?: arrayOf()
            permissions.contains("android.permission.FAKE_PACKAGE_SIGNATURE")
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun setupMicroGDetectedUi() {
        wizardTitle.text = getString(R.string.setup_microg_detected_title)
        wizardSubtitle.text = getString(R.string.setup_microg_detected_desc)
        infoBoxTitle.text = "microG Active"
        infoBoxDescription.text = "All required microG components are already active on your custom ROM."
        cbIncludeAurora.visibility = View.GONE
        auroraDescText.visibility = View.GONE
        auroraDivider.visibility = View.GONE
        btnPrimaryAction.text = getString(R.string.btn_finish_setup)
        btnSecondaryAction.visibility = View.GONE

        btnPrimaryAction.setOnClickListener {
            finishSetupWizard(Activity.RESULT_OK)
        }
    }

    private fun setupCleanInstallUi() {
        btnPrimaryAction.text = getString(R.string.btn_install_microg)
        btnSecondaryAction.text = getString(R.string.btn_skip_microg)

        btnSecondaryAction.setOnClickListener {
            setAppLauncherEnabled(false)
            finishSetupWizard(Activity.RESULT_OK)
        }

        btnPrimaryAction.setOnClickListener {
            startMicroGInstallation()
        }
    }

    private fun startMicroGInstallation() {
        val shouldInstallAurora = cbIncludeAurora.isChecked
        optionsContainer.visibility = View.GONE
        progressContainer.visibility = View.VISIBLE
        btnPrimaryAction.isEnabled = false
        btnSecondaryAction.visibility = View.GONE

        lifecycleScope.launch {
            progressText.text = "Checking latest microG release..."
            val release = ReleaseChecker.fetchLatestRelease()

            if (release == null || release.gmsUrl == null) {
                Toast.makeText(this@SetupWizardActivity, "Could not fetch microG release from GitHub", Toast.LENGTH_LONG).show()
                optionsContainer.visibility = View.VISIBLE
                progressContainer.visibility = View.GONE
                btnPrimaryAction.isEnabled = true
                btnSecondaryAction.visibility = View.VISIBLE
                return@launch
            }

            // Step 1: Install GmsCore
            progressText.text = getString(R.string.setup_downloading_gms, 0)
            val gmsSuccess = SystemInstaller.downloadAndInstall(
                this@SetupWizardActivity,
                release.gmsUrl,
                "com.google.android.gms"
            ) { progress ->
                runOnUiThread {
                    progressText.text = getString(R.string.setup_downloading_gms, progress)
                }
            }

            if (!gmsSuccess) {
                Toast.makeText(this@SetupWizardActivity, "Failed to install microG GmsCore", Toast.LENGTH_LONG).show()
                optionsContainer.visibility = View.VISIBLE
                progressContainer.visibility = View.GONE
                btnPrimaryAction.isEnabled = true
                btnSecondaryAction.visibility = View.VISIBLE
                return@launch
            }

            // Step 2: Install Companion/Store (if available)
            if (release.vendingUrl != null) {
                progressText.text = getString(R.string.setup_downloading_vending, 0)
                SystemInstaller.downloadAndInstall(
                    this@SetupWizardActivity,
                    release.vendingUrl,
                    "com.android.vending"
                ) { progress ->
                    runOnUiThread {
                        progressText.text = getString(R.string.setup_downloading_vending, progress)
                    }
                }
            }

            // Step 3: Install Aurora Store (if requested & available)
            if (shouldInstallAurora && release.auroraUrl != null) {
                progressText.text = getString(R.string.setup_downloading_aurora, 0)
                SystemInstaller.downloadAndInstall(
                    this@SetupWizardActivity,
                    release.auroraUrl,
                    "com.aurora.store"
                ) { progress ->
                    runOnUiThread {
                        progressText.text = getString(R.string.setup_downloading_aurora, progress)
                    }
                }
            }

            // Enable MicroG Updater launcher icon since microG was enabled/installed
            setAppLauncherEnabled(true)

            // Step 4: Complete UI
            progressContainer.visibility = View.GONE
            optionsContainer.visibility = View.VISIBLE
            cbIncludeAurora.visibility = View.GONE
            auroraDescText.visibility = View.GONE
            auroraDivider.visibility = View.GONE

            infoBoxTitle.text = getString(R.string.setup_complete_title)
            if (shouldInstallAurora && release.auroraUrl != null) {
                infoBoxDescription.text = "• microG GmsCore & Companion active\n• Aurora Store installed\n• Cloud Messaging & Location services ready"
            } else {
                infoBoxDescription.text = "• microG GmsCore & Companion active\n• Cloud Messaging & Location services ready"
            }
            wizardTitle.text = getString(R.string.setup_complete_title)
            wizardSubtitle.text = getString(R.string.setup_complete_desc)

            btnPrimaryAction.isEnabled = true
            btnPrimaryAction.text = getString(R.string.btn_finish_setup)
            btnPrimaryAction.setOnClickListener {
                finishSetupWizard(Activity.RESULT_OK)
            }
        }
    }
}
