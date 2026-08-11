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

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.microg.installer.updater.data.ReleaseChecker
import org.microg.installer.updater.data.ReleaseInfo
import org.microg.installer.updater.installer.SystemInstaller
import org.microg.installer.updater.worker.UpdateWorker

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var gmsInstalledVer: TextView
    private lateinit var gmsLatestVer: TextView
    private lateinit var vendingInstalledVer: TextView
    private lateinit var vendingLatestVer: TextView
    private lateinit var auroraInstalledVer: TextView
    private lateinit var auroraLatestVer: TextView
    private lateinit var btnUpdateGms: MaterialButton
    private lateinit var btnUpdateVending: MaterialButton
    private lateinit var btnUpdateAurora: MaterialButton
    private lateinit var btnCheckNow: MaterialButton

    private var currentRelease: ReleaseInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        statusCard = findViewById(R.id.statusCard)
        statusIcon = findViewById(R.id.statusIcon)
        statusTitle = findViewById(R.id.statusTitle)
        statusSubtitle = findViewById(R.id.statusSubtitle)
        gmsInstalledVer = findViewById(R.id.gmsInstalledVer)
        gmsLatestVer = findViewById(R.id.gmsLatestVer)
        vendingInstalledVer = findViewById(R.id.vendingInstalledVer)
        vendingLatestVer = findViewById(R.id.vendingLatestVer)
        auroraInstalledVer = findViewById(R.id.auroraInstalledVer)
        auroraLatestVer = findViewById(R.id.auroraLatestVer)
        btnUpdateGms = findViewById(R.id.btnUpdateGms)
        btnUpdateVending = findViewById(R.id.btnUpdateVending)
        btnUpdateAurora = findViewById(R.id.btnUpdateAurora)
        btnCheckNow = findViewById(R.id.btnCheckNow)

        setSupportActionBar(toolbar)

        swipeRefresh.setOnRefreshListener {
            checkForUpdates()
        }

        btnCheckNow.setOnClickListener {
            checkForUpdates()
        }

        btnUpdateGms.setOnClickListener {
            val url = currentRelease?.gmsUrl
            if (url != null) {
                installComponent(url, "com.google.android.gms", btnUpdateGms)
            }
        }

        btnUpdateVending.setOnClickListener {
            val url = currentRelease?.vendingUrl
            if (url != null) {
                installComponent(url, "com.android.vending", btnUpdateVending)
            }
        }

        btnUpdateAurora.setOnClickListener {
            val url = currentRelease?.auroraUrl
            if (url != null) {
                installComponent(url, "com.aurora.store", btnUpdateAurora)
            }
        }

        UpdateWorker.scheduleWork(this)
        refreshInstalledVersions()
        checkForUpdates()
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

    private fun refreshInstalledVersions() {
        val gmsVer = getInstalledVersion("com.google.android.gms") ?: "Not installed"
        val vendingVer = getInstalledVersion("com.android.vending") ?: "Not installed"
        val auroraVer = getInstalledVersion("com.aurora.store") ?: "Not installed"

        gmsInstalledVer.text = getString(R.string.installed_version, gmsVer)
        vendingInstalledVer.text = getString(R.string.installed_version, vendingVer)
        auroraInstalledVer.text = getString(R.string.installed_version, auroraVer)
    }

    private fun getInstalledVersion(packageName: String): String? {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun checkForUpdates() {
        swipeRefresh.isRefreshing = true
        statusTitle.text = getString(R.string.status_checking)

        lifecycleScope.launch {
            val release = ReleaseChecker.fetchLatestRelease()
            swipeRefresh.isRefreshing = false
            currentRelease = release

            if (release == null) {
                statusTitle.text = "Failed to connect"
                statusSubtitle.text = "Could not check component releases."
                Toast.makeText(this@MainActivity, "Failed to check release updates", Toast.LENGTH_SHORT).show()
                return@launch
            }

            gmsLatestVer.text = getString(R.string.latest_version, release.gmsVersionName ?: "N/A")
            vendingLatestVer.text = getString(R.string.latest_version, release.vendingVersionName ?: "N/A")
            auroraLatestVer.text = getString(R.string.latest_version, release.auroraVersionName ?: "N/A")

            val gmsInstalled = getInstalledVersion("com.google.android.gms")
            val vendingInstalled = getInstalledVersion("com.android.vending")
            val auroraInstalled = getInstalledVersion("com.aurora.store")

            val gmsNeedsUpdate = release.gmsUrl != null && (gmsInstalled == null || gmsInstalled != release.gmsVersionName)
            val vendingNeedsUpdate = release.vendingUrl != null && (vendingInstalled == null || vendingInstalled != release.vendingVersionName)
            val auroraNeedsUpdate = release.auroraUrl != null && (auroraInstalled == null || auroraInstalled != release.auroraVersionName)

            if (gmsNeedsUpdate) {
                btnUpdateGms.isEnabled = true
                btnUpdateGms.text = if (gmsInstalled == null) getString(R.string.btn_install) else getString(R.string.btn_update)
            } else {
                btnUpdateGms.isEnabled = false
                btnUpdateGms.text = getString(R.string.btn_up_to_date)
            }

            if (vendingNeedsUpdate) {
                btnUpdateVending.isEnabled = true
                btnUpdateVending.text = if (vendingInstalled == null) getString(R.string.btn_install) else getString(R.string.btn_update)
            } else {
                btnUpdateVending.isEnabled = false
                btnUpdateVending.text = getString(R.string.btn_up_to_date)
            }

            if (auroraNeedsUpdate) {
                btnUpdateAurora.isEnabled = true
                btnUpdateAurora.text = if (auroraInstalled == null) getString(R.string.btn_install) else getString(R.string.btn_update)
            } else {
                btnUpdateAurora.isEnabled = false
                btnUpdateAurora.text = getString(R.string.btn_up_to_date)
            }

            if (gmsNeedsUpdate || vendingNeedsUpdate || auroraNeedsUpdate) {
                statusTitle.text = getString(R.string.status_update_available)
                statusSubtitle.text = "New component updates are available."
                statusCard.setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.status_amber_bg))
                statusTitle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_amber))
                statusIcon.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.status_amber))
            } else {
                statusTitle.text = getString(R.string.status_up_to_date)
                statusSubtitle.text = "Installed microG & Aurora Store components are up to date."
                statusCard.setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.status_green_bg))
                statusTitle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                statusIcon.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.status_green))
            }
        }
    }

    private fun installComponent(url: String, packageName: String, button: MaterialButton) {
        button.isEnabled = false
        button.text = "Downloading..."

        lifecycleScope.launch {
            val success = SystemInstaller.downloadAndInstall(this@MainActivity, url, packageName) { progress ->
                runOnUiThread {
                    button.text = "$progress%"
                }
            }

            if (success) {
                button.text = "Installing..."
                Toast.makeText(this@MainActivity, "Installation submitted", Toast.LENGTH_SHORT).show()
                delay(3500)
                refreshInstalledVersions()
                checkForUpdates()
            } else {
                Toast.makeText(this@MainActivity, "Installation failed", Toast.LENGTH_SHORT).show()
                button.isEnabled = true
                button.text = getString(R.string.btn_update)
            }
        }
    }
}
