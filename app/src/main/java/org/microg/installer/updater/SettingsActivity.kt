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
import org.microg.installer.updater.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        val prefs = getSharedPreferences("updater_settings", Context.MODE_PRIVATE)

        binding.switchAutoCheck.isChecked = prefs.getBoolean("auto_check_updates", true)
        binding.switchAutoInstall.isChecked = prefs.getBoolean("auto_install_updates", false)
        binding.switchWifiOnly.isChecked = prefs.getBoolean("wifi_only", true)

        binding.switchAutoCheck.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_check_updates", isChecked).apply()
        }

        binding.switchAutoInstall.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_install_updates", isChecked).apply()
        }

        binding.switchWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("wifi_only", isChecked).apply()
        }
    }
}
