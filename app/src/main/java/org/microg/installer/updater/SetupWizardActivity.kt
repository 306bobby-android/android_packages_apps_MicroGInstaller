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
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.microg.installer.updater.data.ReleaseChecker
import org.microg.installer.updater.databinding.ActivitySetupWizardBinding
import org.microg.installer.updater.installer.SystemInstaller

class SetupWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupWizardBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isOfficialGAppsInstalled()) {
            setAppLauncherEnabled(false)
            setResult(Activity.RESULT_OK)
            finish()
            return
        }

        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isMicroGInstalled = isMicroGInstalled()

        if (isMicroGInstalled) {
            setAppLauncherEnabled(true)
            setupMicroGDetectedUi()
        } else {
            setupCleanInstallUi()
        }
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
        binding.wizardTitle.text = getString(R.string.setup_microg_detected_title)
        binding.wizardSubtitle.text = getString(R.string.setup_microg_detected_desc)
        binding.infoBoxTitle.text = "microG Active"
        binding.infoBoxDescription.text = "All required microG components are already active on your custom ROM."
        binding.btnPrimaryAction.text = getString(R.string.btn_finish_setup)
        binding.btnSecondaryAction.visibility = View.GONE

        binding.btnPrimaryAction.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun setupCleanInstallUi() {
        binding.btnPrimaryAction.text = getString(R.string.btn_install_microg)
        binding.btnSecondaryAction.text = getString(R.string.btn_skip_microg)

        binding.btnSecondaryAction.setOnClickListener {
            setAppLauncherEnabled(false)
            setResult(Activity.RESULT_OK)
            finish()
        }

        binding.btnPrimaryAction.setOnClickListener {
            startMicroGInstallation()
        }
    }

    private fun startMicroGInstallation() {
        binding.optionsContainer.visibility = View.GONE
        binding.progressContainer.visibility = View.VISIBLE
        binding.btnPrimaryAction.isEnabled = false
        binding.btnSecondaryAction.visibility = View.GONE

        lifecycleScope.launch {
            binding.progressText.text = "Checking latest microG release..."
            val release = ReleaseChecker.fetchLatestRelease()

            if (release == null || release.gmsUrl == null) {
                Toast.makeText(this@SetupWizardActivity, "Could not fetch microG release from GitHub", Toast.LENGTH_LONG).show()
                binding.optionsContainer.visibility = View.VISIBLE
                binding.progressContainer.visibility = View.GONE
                binding.btnPrimaryAction.isEnabled = true
                binding.btnSecondaryAction.visibility = View.VISIBLE
                return@launch
            }

            // Step 1: Install GmsCore
            binding.progressText.text = getString(R.string.setup_downloading_gms, 0)
            val gmsSuccess = SystemInstaller.downloadAndInstall(
                this@SetupWizardActivity,
                release.gmsUrl,
                "com.google.android.gms"
            ) { progress ->
                runOnUiThread {
                    binding.progressText.text = getString(R.string.setup_downloading_gms, progress)
                }
            }

            if (!gmsSuccess) {
                Toast.makeText(this@SetupWizardActivity, "Failed to install microG GmsCore", Toast.LENGTH_LONG).show()
                binding.optionsContainer.visibility = View.VISIBLE
                binding.progressContainer.visibility = View.GONE
                binding.btnPrimaryAction.isEnabled = true
                binding.btnSecondaryAction.visibility = View.VISIBLE
                return@launch
            }

            // Step 2: Install Companion/Store (if available)
            if (release.vendingUrl != null) {
                binding.progressText.text = getString(R.string.setup_downloading_vending, 0)
                SystemInstaller.downloadAndInstall(
                    this@SetupWizardActivity,
                    release.vendingUrl,
                    "com.android.vending"
                ) { progress ->
                    runOnUiThread {
                        binding.progressText.text = getString(R.string.setup_downloading_vending, progress)
                    }
                }
            }

            // Enable MicroG Updater launcher icon since microG was enabled/installed
            setAppLauncherEnabled(true)

            // Step 3: Complete
            binding.progressContainer.visibility = View.GONE
            binding.optionsContainer.visibility = View.VISIBLE
            binding.infoBoxTitle.text = getString(R.string.setup_complete_title)
            binding.infoBoxDescription.text = getString(R.string.setup_complete_desc)
            binding.wizardTitle.text = getString(R.string.setup_complete_title)
            binding.wizardSubtitle.text = getString(R.string.setup_complete_desc)

            binding.btnPrimaryAction.isEnabled = true
            binding.btnPrimaryAction.text = getString(R.string.btn_finish_setup)
            binding.btnPrimaryAction.setOnClickListener {
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }
}
