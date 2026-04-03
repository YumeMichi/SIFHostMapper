package com.yumemichi.sifhostmapper

import android.content.Context
import androidx.core.content.edit

object Prefs {
    private const val PREF_NAME = "host_vpn_prefs"
    private const val KEY_TARGET_IP = "target_ip"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOSTS = "hosts"

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

    fun hosts(context: Context): LinkedHashSet<String> {
        val prefSet = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_HOSTS, null)
            ?: return HostConfig.DEFAULT_HOSTS
                .map(::normalizeHost)
                .filter { it.isNotEmpty() }
                .toCollection(LinkedHashSet())

        return prefSet
            .map(::normalizeHost)
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
    }

    fun setHosts(context: Context, hosts: Collection<String>) {
        val normalized = hosts
            .map(::normalizeHost)
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit {
                putStringSet(KEY_HOSTS, normalized)
            }
    }

    private fun normalizeHost(input: String): String {
        return input.trim()
            .lowercase()
            .trimEnd('.')
    }
}
