package com.yumemichi.sifhostmapper

import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import java.net.InetAddress

class MainActivity : AppCompatActivity() {
    private lateinit var ipInput: TextInputEditText
    private lateinit var hostInput: TextInputEditText
    private lateinit var addHostButton: MaterialButton
    private lateinit var hostChipGroup: ChipGroup
    private lateinit var vpnSwitch: MaterialSwitch
    private lateinit var statusText: TextView
    private lateinit var switchListener: CompoundButton.OnCheckedChangeListener
    private val hosts = linkedSetOf<String>()

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                startVpnFromUi()
            } else {
                vpnSwitch.setOnCheckedChangeListener(null)
                vpnSwitch.isChecked = false
                vpnSwitch.setOnCheckedChangeListener(switchListener)
                updateStatus(false)
                Toast.makeText(this, getString(R.string.toast_vpn_permission_required), Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        applySystemBarStyle()

        ipInput = findViewById(R.id.ipInput)
        hostInput = findViewById(R.id.hostInput)
        addHostButton = findViewById(R.id.addHostButton)
        hostChipGroup = findViewById(R.id.hostChipGroup)
        vpnSwitch = findViewById(R.id.vpnSwitch)
        statusText = findViewById(R.id.statusText)
        findViewById<TextView>(R.id.titleText).text = getString(R.string.host_list_title)

        switchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val preparedIntent = VpnService.prepare(this)
                if (preparedIntent != null) {
                    vpnPermissionLauncher.launch(preparedIntent)
                } else {
                    startVpnFromUi()
                }
            } else {
                stopVpnFromUi()
            }
        }

        addHostButton.setOnClickListener { addHostFromInput() }
        hostInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                addHostFromInput()
                true
            } else {
                false
            }
        }

        ipInput.setText(Prefs.targetIp(this))
        hosts.clear()
        hosts.addAll(Prefs.hosts(this))
        renderHostChips()

        if (!HostMapVpnService.isRunning) {
            Prefs.setEnabled(this, false)
        }

        vpnSwitch.setOnCheckedChangeListener(null)
        vpnSwitch.isChecked = HostMapVpnService.isRunning
        vpnSwitch.setOnCheckedChangeListener(switchListener)
        updateStatus(vpnSwitch.isChecked)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is TextInputEditText) {
                val rect = Rect()
                focused.getGlobalVisibleRect(rect)
                if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    clearInputFocusAndKeyboard(focused)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun startVpnFromUi() {
        val ipText = ipInput.text?.toString()?.trim().orEmpty()
        if (!isValidIpv4(ipText)) {
            vpnSwitch.setOnCheckedChangeListener(null)
            vpnSwitch.isChecked = false
            vpnSwitch.setOnCheckedChangeListener(switchListener)
            updateStatus(false)
            Toast.makeText(this, getString(R.string.toast_invalid_ipv4), Toast.LENGTH_SHORT).show()
            return
        }
        if (hosts.isEmpty()) {
            vpnSwitch.setOnCheckedChangeListener(null)
            vpnSwitch.isChecked = false
            vpnSwitch.setOnCheckedChangeListener(switchListener)
            updateStatus(false)
            Toast.makeText(this, getString(R.string.toast_empty_hosts), Toast.LENGTH_SHORT).show()
            return
        }

        Prefs.setTargetIp(this, ipText)
        Prefs.setHosts(this, hosts)
        val intent = Intent(this, HostMapVpnService::class.java).apply {
            action = HostMapVpnService.ACTION_START
            putExtra(HostMapVpnService.EXTRA_TARGET_IP, ipText)
        }
        ContextCompat.startForegroundService(this, intent)
        updateStatus(true)
    }

    private fun stopVpnFromUi() {
        val intent = Intent(this, HostMapVpnService::class.java).apply {
            action = HostMapVpnService.ACTION_STOP
        }
        startService(intent)
        updateStatus(false)
    }

    private fun updateStatus(enabled: Boolean) {
        val ip = ipInput.text?.toString()?.trim().orEmpty()
        statusText.text = if (enabled) {
            getString(R.string.status_on_hosts_format, hosts.size, ip)
        } else {
            getString(R.string.status_off)
        }
    }

    private fun isValidIpv4(input: String): Boolean {
        return try {
            val addr = InetAddress.getByName(input)
            addr.hostAddress == input && addr.address.size == 4
        } catch (_: Exception) {
            false
        }
    }

    private fun addHostFromInput() {
        val raw = hostInput.text?.toString().orEmpty()
        val host = normalizeHost(raw)
        if (!isValidHost(host)) {
            Toast.makeText(this, getString(R.string.toast_invalid_host), Toast.LENGTH_SHORT).show()
            return
        }
        if (hosts.add(host)) {
            Prefs.setHosts(this, hosts)
            renderHostChips()
            refreshVpnIfRunning()
        }
        hostInput.setText("")
    }

    private fun renderHostChips() {
        hostChipGroup.removeAllViews()
        hosts.forEach { host ->
            val chip = Chip(this).apply {
                text = host
                isCloseIconVisible = true
                setEnsureMinTouchTargetSize(false)
                setOnCloseIconClickListener {
                    removeHost(host)
                }
            }
            hostChipGroup.addView(chip)
        }
        updateStatus(vpnSwitch.isChecked)
    }

    private fun removeHost(host: String) {
        if (!hosts.remove(host)) return
        Prefs.setHosts(this, hosts)
        renderHostChips()

        if (hosts.isEmpty()) {
            if (vpnSwitch.isChecked) {
                vpnSwitch.setOnCheckedChangeListener(null)
                vpnSwitch.isChecked = false
                vpnSwitch.setOnCheckedChangeListener(switchListener)
                stopVpnFromUi()
            }
            Toast.makeText(this, getString(R.string.toast_empty_hosts), Toast.LENGTH_SHORT).show()
            return
        }
        refreshVpnIfRunning()
    }

    private fun refreshVpnIfRunning() {
        if (!vpnSwitch.isChecked) return
        val ipText = ipInput.text?.toString()?.trim().orEmpty()
        if (!isValidIpv4(ipText) || hosts.isEmpty()) return
        Prefs.setTargetIp(this, ipText)
        Prefs.setHosts(this, hosts)
        val intent = Intent(this, HostMapVpnService::class.java).apply {
            action = HostMapVpnService.ACTION_START
            putExtra(HostMapVpnService.EXTRA_TARGET_IP, ipText)
        }
        ContextCompat.startForegroundService(this, intent)
        updateStatus(true)
    }

    private fun normalizeHost(raw: String): String {
        return raw.trim()
            .lowercase()
            .trimEnd('.')
    }

    private fun isValidHost(host: String): Boolean {
        if (host.length !in 3..253) return false
        val pattern = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")
        return pattern.matches(host)
    }

    private fun applySystemBarStyle() {
        val root = findViewById<android.view.View>(R.id.rootContainer)
        val barColor = (root.background as? ColorDrawable)?.color
            ?: MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface)
        window.statusBarColor = barColor
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            ColorUtils.calculateLuminance(barColor) > 0.5
    }

    private fun clearInputFocusAndKeyboard(focusedView: android.view.View) {
        ipInput.clearFocus()
        hostInput.clearFocus()
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(focusedView.windowToken, 0)
    }
}
