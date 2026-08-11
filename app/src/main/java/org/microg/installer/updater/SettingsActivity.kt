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

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val switchAutoCheck = findViewById<MaterialSwitch>(R.id.switchAutoCheck)
        val switchAutoInstall = findViewById<MaterialSwitch>(R.id.switchAutoInstall)
        val switchWifiOnly = findViewById<MaterialSwitch>(R.id.switchWifiOnly)

        toolbar.setNavigationOnClickListener {
            finish()
        }

        val prefs = getSharedPreferences("updater_settings", Context.MODE_PRIVATE)

        switchAutoCheck.isChecked = prefs.getBoolean("auto_check_updates", true)
        switchAutoInstall.isChecked = prefs.getBoolean("auto_install_updates", false)
        switchWifiOnly.isChecked = prefs.getBoolean("wifi_only", true)

        switchAutoCheck.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_check_updates", isChecked).apply()
        }

        switchAutoInstall.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_install_updates", isChecked).apply()
        }

        switchWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("wifi_only", isChecked).apply()
        }
    }
}
