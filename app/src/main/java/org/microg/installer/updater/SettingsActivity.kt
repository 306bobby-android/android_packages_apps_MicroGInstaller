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

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.microg.installer.updater.worker.UpdateWorker
import org.microg.installer.updater.worker.UpdateWorker.Settings

class SettingsActivity : AppCompatActivity() {

    private lateinit var frequencyValue: TextView
    private lateinit var frequencyEntries: Array<String>
    private lateinit var frequencyHours: IntArray

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        frequencyEntries = resources.getStringArray(R.array.check_frequency_entries)
        frequencyHours = resources.getIntArray(R.array.check_frequency_values)

        val prefs = Settings.prefs(this)
        val switchAutoCheck = findViewById<MaterialSwitch>(R.id.switchAutoCheck)
        val switchAutoInstall = findViewById<MaterialSwitch>(R.id.switchAutoInstall)
        val switchWifiOnly = findViewById<MaterialSwitch>(R.id.switchWifiOnly)
        val frequencyRow = findViewById<MaterialCardView>(R.id.frequencyRow)
        frequencyValue = findViewById(R.id.frequencyValue)

        switchAutoCheck.isChecked = prefs.getBoolean(Settings.KEY_AUTO_CHECK, true)
        switchAutoInstall.isChecked = prefs.getBoolean(Settings.KEY_AUTO_INSTALL, false)
        switchWifiOnly.isChecked = prefs.getBoolean(Settings.KEY_WIFI_ONLY, true)
        updateFrequencySummary()
        setFrequencyRowEnabled(switchAutoCheck.isChecked)

        // Every switch reschedules: the periodic check reads these at enqueue time, so a
        // preference that is only written to disk would not take effect until reboot.
        switchAutoCheck.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Settings.KEY_AUTO_CHECK, isChecked).apply()
            setFrequencyRowEnabled(isChecked)
            UpdateWorker.scheduleWork(this, reschedule = true)
        }

        switchAutoInstall.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Settings.KEY_AUTO_INSTALL, isChecked).apply()
        }

        switchWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Settings.KEY_WIFI_ONLY, isChecked).apply()
            UpdateWorker.scheduleWork(this, reschedule = true)
        }

        frequencyRow.setOnClickListener { showFrequencyDialog() }
    }

    private fun setFrequencyRowEnabled(enabled: Boolean) {
        val row = findViewById<MaterialCardView>(R.id.frequencyRow)
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.5f
    }

    private fun currentFrequencyIndex(): Int {
        val hours = Settings.prefs(this)
            .getInt(Settings.KEY_FREQUENCY_HOURS, Settings.DEFAULT_FREQUENCY_HOURS)
        val index = frequencyHours.indexOf(hours)
        return if (index >= 0) index else frequencyHours.indexOf(Settings.DEFAULT_FREQUENCY_HOURS)
    }

    private fun updateFrequencySummary() {
        frequencyValue.text = frequencyEntries.getOrElse(currentFrequencyIndex()) {
            getString(R.string.pref_frequency_summary)
        }
    }

    private fun showFrequencyDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pref_frequency_title)
            .setSingleChoiceItems(frequencyEntries, currentFrequencyIndex()) { dialog, which ->
                Settings.prefs(this)
                    .edit()
                    .putInt(Settings.KEY_FREQUENCY_HOURS, frequencyHours[which])
                    .apply()
                updateFrequencySummary()
                UpdateWorker.scheduleWork(this, reschedule = true)
                dialog.dismiss()
            }
            .show()
    }
}
