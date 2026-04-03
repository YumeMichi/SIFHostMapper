package com.yumemichi.sifhostmapper

import android.content.Context
import androidx.core.content.edit

object Prefs {
    private const val PREF_NAME = "host_vpn_prefs"
    private const val KEY_TARGET_IP = "target_ip"
    private const val KEY_ENABLED = "enabled"

    fun targetIp(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TARGET_IP, "")
            .orEmpty()
    }

    fun setTargetIp(context: Context, ip: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_TARGET_IP, ip)
            }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_ENABLED, enabled)
            }
    }
}
