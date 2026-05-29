package com.yumemichi.sifhostmapper

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object Prefs {
    private const val PREF_NAME = "host_vpn_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VALIDATE_DOMAINS = "validate_domains"

    private const val KEY_MAPPING_GROUPS_JSON = "mapping_groups_json"
    private const val KEY_SELECTED_GROUP_ID = "selected_group_id"

    private const val LEGACY_KEY_TARGET_IP = "target_ip"
    private const val LEGACY_KEY_HOSTS = "hosts"

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_ENABLED, enabled)
            }
    }

    fun validateDomainsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VALIDATE_DOMAINS, false)
    }

    fun setValidateDomainsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_VALIDATE_DOMAINS, enabled)
            }
    }

    fun mappingGroups(context: Context): MutableList<DomainMappingGroup> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_MAPPING_GROUPS_JSON, null)
        if (json.isNullOrBlank()) {
            val migrated = migrateLegacyGroup(context)
            setMappingGroups(context, migrated)
            setSelectedGroupId(context, migrated.firstOrNull()?.id)
            return migrated.toMutableList()
        }

        val parsed = parseGroupsJson(context, json)
        if (parsed.isEmpty()) {
            val fallback = defaultGroups(context)
            setMappingGroups(context, fallback)
            setSelectedGroupId(context, fallback.firstOrNull()?.id)
            return fallback.toMutableList()
        }
        return parsed.toMutableList()
    }

    fun setMappingGroups(context: Context, groups: Collection<DomainMappingGroup>) {
        val normalized = groups
            .map { group ->
                DomainMappingGroup(
                    id = group.id,
                    name = group.name.trim().ifEmpty { context.getString(R.string.group_name_format, 1) },
                    targetIp = group.targetIp.trim(),
                    domains = group.domains.map(::normalizeDomain).filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
                )
            }

        val jsonArray = JSONArray()
        normalized.forEach { group ->
            jsonArray.put(
                JSONObject()
                    .put("id", group.id)
                    .put("name", group.name)
                    .put("targetIp", group.targetIp)
                    .put("domains", JSONArray(group.domains.toList()))
            )
        }

        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_MAPPING_GROUPS_JSON, jsonArray.toString())
            }
    }

    fun selectedGroupId(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_GROUP_ID, null)
    }

    fun setSelectedGroupId(context: Context, id: String?) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_SELECTED_GROUP_ID, id)
            }
    }

    private fun migrateLegacyGroup(context: Context): List<DomainMappingGroup> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val legacyIp = prefs.getString(LEGACY_KEY_TARGET_IP, "").orEmpty().trim()
        val legacyDomains = prefs.getStringSet(LEGACY_KEY_HOSTS, null)
            ?.map(::normalizeDomain)
            ?.filter { it.isNotEmpty() }
            ?.toCollection(LinkedHashSet())
            ?: HostConfig.DEFAULT_HOSTS
                .map(::normalizeDomain)
                .filter { it.isNotEmpty() }
                .toCollection(LinkedHashSet())

        return listOf(
            DomainMappingGroup(
                id = UUID.randomUUID().toString(),
                name = context.getString(R.string.group_name_format, 1),
                targetIp = legacyIp,
                domains = legacyDomains
            )
        )
    }

    private fun defaultGroups(context: Context): List<DomainMappingGroup> {
        val defaultDomains = HostConfig.DEFAULT_HOSTS
            .map(::normalizeDomain)
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
        return listOf(
            DomainMappingGroup(
                id = UUID.randomUUID().toString(),
                name = context.getString(R.string.group_name_format, 1),
                targetIp = "",
                domains = defaultDomains
            )
        )
    }

    private fun parseGroupsJson(context: Context, json: String): List<DomainMappingGroup> {
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                    val name = obj.optString("name").trim().ifEmpty { context.getString(R.string.group_name_format, i + 1) }
                    val targetIp = obj.optString("targetIp").trim()
                    val domainsArray = obj.optJSONArray("domains") ?: JSONArray()
                    val domains = LinkedHashSet<String>()
                    for (d in 0 until domainsArray.length()) {
                        val normalized = normalizeDomain(domainsArray.optString(d))
                        if (normalized.isNotEmpty()) {
                            domains += normalized
                        }
                    }
                    add(
                        DomainMappingGroup(
                            id = id,
                            name = name,
                            targetIp = targetIp,
                            domains = domains
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeDomain(input: String): String {
        return input.trim()
            .lowercase()
            .trimEnd('.')
    }
}
