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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import org.microg.installer.updater.data.ComponentRelease
import org.microg.installer.updater.data.InstalledPackages
import org.microg.installer.updater.data.ReleaseChecker
import org.microg.installer.updater.data.ReleaseInfo
import org.microg.installer.updater.installer.SystemInstaller
import org.microg.installer.updater.worker.UpdateWorker

class MainActivity : AppCompatActivity() {

    /** Binds one included component_card to the package it tracks. */
    private inner class ComponentCard(
        root: View,
        val packageName: String,
        titleRes: Int,
        packageRes: Int
    ) {
        val installedVer: TextView = root.findViewById(R.id.installedVer)
        val latestVer: TextView = root.findViewById(R.id.latestVer)
        val button: MaterialButton = root.findViewById(R.id.btnAction)

        init {
            root.findViewById<TextView>(R.id.componentTitle).setText(titleRes)
            root.findViewById<TextView>(R.id.componentPackage).setText(packageRes)
        }
    }

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var cards: List<ComponentCard>

    private var currentRelease: ReleaseInfo? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        swipeRefresh = findViewById(R.id.swipeRefresh)
        statusCard = findViewById(R.id.statusCard)
        statusIcon = findViewById(R.id.statusIcon)
        statusTitle = findViewById(R.id.statusTitle)
        statusSubtitle = findViewById(R.id.statusSubtitle)

        cards = listOf(
            ComponentCard(
                findViewById(R.id.gmsCard),
                ReleaseInfo.PACKAGE_GMS,
                R.string.gmscore_title,
                R.string.gmscore_package
            ),
            ComponentCard(
                findViewById(R.id.vendingCard),
                ReleaseInfo.PACKAGE_VENDING,
                R.string.vending_title,
                R.string.vending_package
            ),
            ComponentCard(
                findViewById(R.id.gsfCard),
                ReleaseInfo.PACKAGE_GSF,
                R.string.gsf_title,
                R.string.gsf_package
            ),
            ComponentCard(
                findViewById(R.id.auroraCard),
                ReleaseInfo.PACKAGE_AURORA,
                R.string.aurora_title,
                R.string.aurora_package
            )
        )

        cards.forEach { card ->
            card.button.setOnClickListener {
                currentRelease?.forPackage(card.packageName)?.let { install(card, it) }
            }
        }

        swipeRefresh.setOnRefreshListener { checkForUpdates() }
        findViewById<MaterialButton>(R.id.btnCheckNow).setOnClickListener { checkForUpdates() }

        requestNotificationPermissionIfNeeded()
        UpdateWorker.scheduleWork(this)
        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        // Versions can change while the activity is backgrounded, e.g. a background
        // auto-install completing.
        refreshInstalledVersions()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * POST_NOTIFICATIONS is a runtime permission from API 33 and is not granted to
     * privileged apps automatically, so without this the update notification -- the only
     * prompt in the default configuration -- is dropped silently.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshInstalledVersions() {
        cards.forEach { card ->
            val installed = InstalledPackages.get(this, card.packageName)
            card.installedVer.text = getString(
                R.string.installed_version,
                installed?.displayVersion ?: getString(R.string.version_not_installed)
            )
        }
    }

    private fun checkForUpdates() {
        swipeRefresh.isRefreshing = true
        statusTitle.text = getString(R.string.status_checking)
        statusSubtitle.text = getString(R.string.status_checking_desc)

        lifecycleScope.launch {
            val release = ReleaseChecker.fetchLatestRelease()
            swipeRefresh.isRefreshing = false
            currentRelease = release

            if (release == null) {
                applyStatus(R.string.status_failed, R.string.status_failed_desc, R.color.status_amber, R.color.status_amber_bg)
                Toast.makeText(this@MainActivity, R.string.toast_check_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }

            refreshInstalledVersions()

            // A component that was never installed is offered, not reported as an
            // update. Counting absent optional components here would leave the banner
            // permanently amber for anyone who declined GSF or Aurora Store.
            var anyInstalledOutdated = false
            cards.forEach { card ->
                val candidate = release.forPackage(card.packageName)
                val installed = InstalledPackages.get(this@MainActivity, card.packageName)

                card.latestVer.text = getString(
                    R.string.latest_version,
                    candidate?.displayVersion ?: "N/A"
                )

                val actionable = InstalledPackages.needsUpdate(installed, candidate)
                card.button.isEnabled = actionable
                card.button.setText(
                    when {
                        !actionable -> R.string.btn_up_to_date
                        installed == null -> R.string.btn_install
                        else -> R.string.btn_update
                    }
                )
                if (actionable && installed != null) anyInstalledOutdated = true
            }

            if (anyInstalledOutdated) {
                applyStatus(
                    R.string.status_update_available,
                    R.string.status_update_available_desc,
                    R.color.status_amber,
                    R.color.status_amber_bg
                )
            } else {
                applyStatus(
                    R.string.status_up_to_date,
                    R.string.status_up_to_date_desc,
                    R.color.status_green,
                    R.color.status_green_bg
                )
            }
        }
    }

    private fun applyStatus(titleRes: Int, subtitleRes: Int, colorRes: Int, backgroundRes: Int) {
        val accent = ContextCompat.getColor(this, colorRes)
        statusTitle.setText(titleRes)
        statusSubtitle.setText(subtitleRes)
        statusTitle.setTextColor(accent)
        statusIcon.setColorFilter(accent)
        statusCard.setCardBackgroundColor(ContextCompat.getColor(this, backgroundRes))
    }

    private fun install(card: ComponentCard, release: ComponentRelease) {
        card.button.isEnabled = false
        card.button.setText(R.string.btn_downloading)

        lifecycleScope.launch {
            val result = SystemInstaller.downloadAndInstall(this@MainActivity, release) { percent ->
                card.button.text = getString(R.string.btn_download_percent, percent)
            }

            if (result.succeeded) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_install_succeeded, release.packageName),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_install_failed, result.failureReason),
                    Toast.LENGTH_LONG
                ).show()
            }

            // Re-derive every button from the freshly installed state rather than
            // guessing what the label should revert to.
            checkForUpdates()
        }
    }
}
