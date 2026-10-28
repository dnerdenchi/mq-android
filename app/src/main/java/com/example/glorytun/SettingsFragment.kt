package com.example.glorytun

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mqvpn.sdk.core.MqvpnSdk
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<LinearLayout>(R.id.menu_network_protocol)?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, NetworkProtocolFragment(), "network_protocol")
                .addToBackStack("network_protocol")
                .commit()
        }

        // 現在のモードをサブタイトルに反映
        updateNetworkModeSubtitle(view)

        view.findViewById<LinearLayout>(R.id.menu_bandwidth)?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, BandwidthFragment(), "bandwidth")
                .addToBackStack("bandwidth")
                .commit()
        }

        view.findViewById<LinearLayout>(R.id.menu_speed_test)?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SpeedTestFragment(), "speed_test")
                .addToBackStack("speed_test")
                .commit()
        }

        updateBandwidthSubtitle(view)
        setupUpdateControls(view)

        val proxyPref = requireContext().getSharedPreferences(GlorytunConstants.PREFS_PROXY, Context.MODE_PRIVATE)
        val switchProxy = view.findViewById<SwitchMaterial>(R.id.switch_adguard_proxy)
        val proxyPort = proxyPref.getInt(
            GlorytunConstants.KEY_ADGUARD_PROXY_PORT,
            GlorytunConstants.DEFAULT_ADGUARD_PROXY_PORT
        )
        switchProxy.isChecked = proxyPref.getBoolean(GlorytunConstants.KEY_ADGUARD_PROXY_MODE_ENABLED, false)
        view.findViewById<TextView>(R.id.tv_adguard_proxy_subtitle)?.text =
            if (switchProxy.isChecked) "有効: AdGuard 側で BondVPN を除外し 127.0.0.1:$proxyPort を設定"
            else "AdGuard 側で BondVPN を除外し 127.0.0.1:$proxyPort を設定"
        switchProxy.setOnCheckedChangeListener { _, isChecked ->
            proxyPref.edit().putBoolean(GlorytunConstants.KEY_ADGUARD_PROXY_MODE_ENABLED, isChecked).apply()
            view.findViewById<TextView>(R.id.tv_adguard_proxy_subtitle)?.text =
                if (isChecked) "有効: AdGuard 側で BondVPN を除外し 127.0.0.1:$proxyPort を設定"
                else "AdGuard 側で BondVPN を除外し 127.0.0.1:$proxyPort を設定"
        }

        setupAdGuardAutomation(view, proxyPort)

        // ダークモード / ライトモード トグル
        val appearancePref = requireContext().getSharedPreferences(GlorytunConstants.PREFS_APPEARANCE, Context.MODE_PRIVATE)
        val switchDarkMode = view.findViewById<SwitchMaterial>(R.id.switch_dark_mode)
        val isDark = appearancePref.getBoolean(GlorytunConstants.KEY_DARK_MODE_ENABLED, true)
        switchDarkMode.isChecked = isDark
        view.findViewById<TextView>(R.id.tv_dark_mode_subtitle)?.text =
            if (isDark) "ダークモード" else "ライトモード"
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            appearancePref.edit().putBoolean(GlorytunConstants.KEY_DARK_MODE_ENABLED, isChecked).apply()
            view.findViewById<TextView>(R.id.tv_dark_mode_subtitle)?.text =
                if (isChecked) "ダークモード" else "ライトモード"
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private fun setupAdGuardAutomation(view: View, proxyPort: Int) {
        val context = requireContext()
        val autoSwitch = view.findViewById<SwitchMaterial>(R.id.switch_adguard_automation)
        val subtitle = view.findViewById<TextView>(R.id.tv_adguard_automation_subtitle)
        val passwordButton = view.findViewById<MaterialButton>(R.id.btn_adguard_automation_password)
        var updatingSwitch = false

        fun updateSubtitle(enabled: Boolean) {
            val passwordReady = AdGuardAutomation.hasPassword(context)
            subtitle.text = when {
                enabled && passwordReady -> "接続時にAdGuardをON、切断時にOFF。AdGuard側の「詳細設定 → 自動化」をONにし、送信プロキシに127.0.0.1:${proxyPort}を登録してください"
                enabled -> "ONですがパスワード未設定です。AdGuard側の自動化と送信プロキシ設定も確認してください"
                else -> "自動切替OFFです。このスイッチ、AdGuard側の自動化、送信プロキシの設定が必要です"
            }
            passwordButton.text = if (passwordReady) {
                "AdGuard自動化パスワードを変更"
            } else {
                "AdGuard自動化パスワードを設定"
            }
        }

        autoSwitch.isChecked = AdGuardAutomation.isEnabled(context)
        updateSubtitle(autoSwitch.isChecked)
        autoSwitch.setOnCheckedChangeListener { button, enabled ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (enabled && !AdGuardAutomation.hasPassword(context)) {
                updatingSwitch = true
                button.isChecked = false
                updatingSwitch = false
                showAdGuardPasswordDialog(proxyPort) {
                    AdGuardAutomation.setEnabled(context, true)
                    updatingSwitch = true
                    button.isChecked = true
                    updatingSwitch = false
                    updateSubtitle(true)
                }
                return@setOnCheckedChangeListener
            }

            AdGuardAutomation.setEnabled(context, enabled)
            updateSubtitle(enabled)
        }

        passwordButton.setOnClickListener {
            showAdGuardPasswordDialog(proxyPort) { updateSubtitle(autoSwitch.isChecked) }
        }
    }

    private fun showAdGuardPasswordDialog(proxyPort: Int, onSaved: () -> Unit) {
        val context = requireContext()
        val input = EditText(context).apply {
            hint = "AdGuardの自動化パスワード"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val inputContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        AlertDialog.Builder(context)
            .setTitle("AdGuard自動化パスワード")
            .setMessage("AdGuard側の「設定 → 一般設定 → 詳細設定 → 自動化」に表示されたパスワードを入力します。端末内に暗号化して保存します。AdGuard側の自動化をONにし、送信プロキシとしてSOCKS5 127.0.0.1:${proxyPort}を登録してください。保存後、この画面の「AdGuardプロキシを自動切替」もONにしてください。")
            .setView(inputContainer)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存") { _, _ ->
                val password = input.text?.toString().orEmpty()
                if (password.isBlank()) {
                    Toast.makeText(context, "パスワードを入力してください", Toast.LENGTH_SHORT).show()
                } else if (AdGuardAutomation.savePassword(context, password)) {
                    onSaved()
                    Toast.makeText(context, "パスワードを保存しました", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "パスワードを保存できませんでした", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun setupUpdateControls(view: View) {
        val updateManager = AppUpdateManager(requireContext().applicationContext)
        val statusText = view.findViewById<TextView>(R.id.tv_update_status)
        val checkButton = view.findViewById<MaterialButton>(R.id.btn_check_update)
        val prereleaseSwitch = view.findViewById<SwitchMaterial>(R.id.switch_include_prereleases)
        val prereleaseRow = view.findViewById<View>(R.id.row_include_prereleases)

        view.findViewById<TextView>(R.id.tv_app_version)?.text = BuildConfig.VERSION_NAME
        view.findViewById<TextView>(R.id.tv_mqvpn_version)?.text = MqvpnSdk.getVersion()
        prereleaseSwitch.isChecked = updateManager.includePrereleases()
        statusText?.text = if (prereleaseSwitch.isChecked) {
            "プレリリース版を含めて更新を確認します"
        } else {
            "安定版の更新だけを確認します"
        }
        prereleaseSwitch.setOnCheckedChangeListener { _, enabled ->
            updateManager.setIncludePrereleases(enabled)
            statusText?.text = if (enabled) {
                "プレリリース版を含めて更新を確認します"
            } else {
                "安定版の更新だけを確認します"
            }
        }
        prereleaseRow.setOnClickListener {
            prereleaseSwitch.isChecked = !prereleaseSwitch.isChecked
        }

        checkButton?.setOnClickListener {
            val activity = activity as? AppCompatActivity ?: return@setOnClickListener
            checkButton.isEnabled = false
            statusText?.text = "アップデートを確認しています..."

            viewLifecycleOwner.lifecycleScope.launch {
                when (val result = updateManager.checkForUpdate()) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        statusText?.text = if (result.updateInfo.isDevelopmentRelease) {
                            "開発版のアップデートが利用できます"
                        } else {
                            val channel = if (result.updateInfo.isPrerelease) "（プレリリース）" else ""
                            "バージョン ${result.updateInfo.versionName}$channel が利用できます"
                        }
                        updateManager.showUpdateDialog(
                            activity,
                            result.updateInfo,
                            viewLifecycleOwner.lifecycleScope
                        )
                    }
                    UpdateCheckResult.NoUpdate -> {
                        statusText?.text = if (updateManager.includePrereleases()) {
                            "現在のバージョン ${BuildConfig.VERSION_NAME} は選択した更新チャンネルの最新版です"
                        } else {
                            "現在のバージョン ${BuildConfig.VERSION_NAME} が最新版です"
                        }
                    }
                    is UpdateCheckResult.Error -> {
                        statusText?.text = result.message
                    }
                }
                checkButton.isEnabled = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        view?.let {
            updateNetworkModeSubtitle(it)
            updateBandwidthSubtitle(it)
        }
    }

    private fun updateBandwidthSubtitle(view: View) {
        val prefs = requireContext().getSharedPreferences(GlorytunConstants.PREFS_BANDWIDTH, Context.MODE_PRIVATE)
        val wifiAlways  = prefs.getBoolean(GlorytunConstants.KEY_WIFI_ALWAYS_ENABLED, false)
        val wifiMonthly = prefs.getBoolean(GlorytunConstants.KEY_WIFI_MONTHLY_ENABLED, false)
        val wifiDaily   = prefs.getBoolean(GlorytunConstants.KEY_WIFI_DAILY_ENABLED,   false)
        val simAlways   = prefs.getBoolean(GlorytunConstants.KEY_SIM_ALWAYS_ENABLED, false)
        val simMonthly  = prefs.getBoolean(GlorytunConstants.KEY_SIM_MONTHLY_ENABLED,  false)
        val simDaily    = prefs.getBoolean(GlorytunConstants.KEY_SIM_DAILY_ENABLED,    false)
        val anyEnabled  = wifiAlways || wifiMonthly || wifiDaily || simAlways || simMonthly || simDaily
        val label = if (!anyEnabled) {
            "制限なし"
        } else {
            buildList {
                if (wifiAlways || wifiMonthly || wifiDaily) add("Wi-Fi")
                if (simAlways || simMonthly || simDaily) add("SIM")
            }.joinToString("・") + " 制限 有効"
        }
        view.findViewById<TextView>(R.id.tv_bandwidth_subtitle)?.text = label
    }

    private fun updateNetworkModeSubtitle(view: View) {
        val prefs = requireContext().getSharedPreferences(
            MqvpnRoutingMode.PREFS_NAME, Context.MODE_PRIVATE
        )
        val mode = prefs.getString(MqvpnRoutingMode.KEY_MODE, MqvpnRoutingMode.WLB)
        val label = MqvpnRoutingMode.displayName(mode)
        view.findViewById<android.widget.TextView>(R.id.tv_network_mode_subtitle)?.text = label
    }
}
