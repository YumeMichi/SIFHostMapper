package com.yumemichi.sifhostmapper

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.VpnService
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import java.net.InetAddress

class MainActivity : AppCompatActivity() {
    private lateinit var ipInput: TextInputEditText
    private lateinit var vpnSwitch: MaterialSwitch
    private lateinit var statusText: TextView
    private lateinit var switchListener: CompoundButton.OnCheckedChangeListener

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
        vpnSwitch = findViewById(R.id.vpnSwitch)
        statusText = findViewById(R.id.statusText)
        findViewById<TextView>(R.id.titleText).text =
            getString(R.string.host_label_format, HostConfig.TARGET_DOMAIN)

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

        ipInput.setText(Prefs.targetIp(this))

        if (!HostMapVpnService.isRunning) {
            Prefs.setEnabled(this, false)
        }

        vpnSwitch.setOnCheckedChangeListener(null)
        vpnSwitch.isChecked = HostMapVpnService.isRunning
        vpnSwitch.setOnCheckedChangeListener(switchListener)
        updateStatus(vpnSwitch.isChecked)
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

        Prefs.setTargetIp(this, ipText)
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
            getString(R.string.status_on_format, HostConfig.TARGET_DOMAIN, ip)
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

    private fun applySystemBarStyle() {
        val root = findViewById<android.view.View>(R.id.rootContainer)
        val barColor = (root.background as? ColorDrawable)?.color
            ?: MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface)
        window.statusBarColor = barColor
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            ColorUtils.calculateLuminance(barColor) > 0.5
    }
}
