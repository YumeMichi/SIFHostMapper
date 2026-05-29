package com.yumemichi.sifhostmapper

import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.ImageView
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.net.InetAddress
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var groupSelectInput: MaterialAutoCompleteTextView
    private lateinit var addGroupButton: MaterialButton
    private lateinit var ipInput: TextInputEditText
    private lateinit var domainInput: TextInputEditText
    private lateinit var addDomainButton: MaterialButton
    private lateinit var domainChipGroup: ChipGroup
    private lateinit var validateDomainSwitch: MaterialSwitch
    private lateinit var vpnSwitch: MaterialSwitch
    private lateinit var statusText: TextView

    private lateinit var switchListener: CompoundButton.OnCheckedChangeListener
    private val groups = mutableListOf<DomainMappingGroup>()
    private val currentDomains = linkedSetOf<String>()
    private var selectedGroupIndex = 0
    private var suppressGroupSelection = false
    private var ignoreNextGroupSelection = false
    private data class DomainIpConflict(val domain: String, val firstIp: String, val secondIp: String)

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
        bindViews()
        bindEvents()
        loadGroupsFromPrefs()
        initSwitches()
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

    private fun bindViews() {
        groupSelectInput = findViewById(R.id.groupSelectInput)
        addGroupButton = findViewById(R.id.addGroupButton)
        ipInput = findViewById(R.id.ipInput)
        domainInput = findViewById(R.id.hostInput)
        addDomainButton = findViewById(R.id.addHostButton)
        domainChipGroup = findViewById(R.id.hostChipGroup)
        validateDomainSwitch = findViewById(R.id.validateHostSwitch)
        vpnSwitch = findViewById(R.id.vpnSwitch)
        statusText = findViewById(R.id.statusText)
        findViewById<TextView>(R.id.titleText).text = getString(R.string.host_list_title)
    }

    private fun bindEvents() {
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

        groupSelectInput.setOnItemClickListener { _, _, position, _ ->
            if (suppressGroupSelection) return@setOnItemClickListener
            if (ignoreNextGroupSelection) {
                ignoreNextGroupSelection = false
                return@setOnItemClickListener
            }
            switchToGroup(position, persistCurrent = true, refreshIfRunning = true)
        }

        addGroupButton.setOnClickListener { addGroup() }

        addDomainButton.setOnClickListener { addDomainFromInput() }
        domainInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                addDomainFromInput()
                true
            } else {
                false
            }
        }
    }

    private fun loadGroupsFromPrefs() {
        groups.clear()
        groups.addAll(Prefs.mappingGroups(this))
        if (groups.isEmpty()) {
            groups += DomainMappingGroup(
                id = UUID.randomUUID().toString(),
                name = getString(R.string.group_name_format, 1),
                targetIp = "",
                domains = linkedSetOf()
            )
        }

        val selectedId = Prefs.selectedGroupId(this)
        selectedGroupIndex = groups.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
        renderGroupSelector()
        switchToGroup(selectedGroupIndex, persistCurrent = false, refreshIfRunning = false)
    }

    private fun initSwitches() {
        validateDomainSwitch.setOnCheckedChangeListener(null)
        validateDomainSwitch.isChecked = Prefs.validateDomainsEnabled(this)
        validateDomainSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setValidateDomainsEnabled(this, isChecked)
            updateStatus(vpnSwitch.isChecked)
        }

        if (!HostMapVpnService.isRunning) {
            Prefs.setEnabled(this, false)
        }

        vpnSwitch.setOnCheckedChangeListener(null)
        vpnSwitch.isChecked = HostMapVpnService.isRunning
        vpnSwitch.setOnCheckedChangeListener(switchListener)
        updateStatus(vpnSwitch.isChecked)
    }

    private fun renderGroupSelector() {
        val adapter = GroupDropdownAdapter()
        groupSelectInput.setAdapter(adapter)
    }

    private fun switchToGroup(index: Int, persistCurrent: Boolean, refreshIfRunning: Boolean) {
        if (index !in groups.indices) return

        if (persistCurrent) {
            persistCurrentGroupFromUi()
        }

        selectedGroupIndex = index
        val group = groups[selectedGroupIndex]

        suppressGroupSelection = true
        groupSelectInput.setText(group.name, false)
        suppressGroupSelection = false

        ipInput.setText(group.targetIp)
        currentDomains.clear()
        currentDomains.addAll(group.domains)
        renderDomainChips()

        Prefs.setSelectedGroupId(this, group.id)

        if (refreshIfRunning) {
            refreshVpnIfRunning()
        } else {
            updateStatus(vpnSwitch.isChecked)
        }
    }

    private fun addGroup() {
        persistCurrentGroupFromUi()
        val nextGroupNumber = groups.size + 1
        val defaultName = getString(R.string.group_name_format, nextGroupNumber)
        showGroupNameDialog(
            title = getString(R.string.dialog_add_group_title),
            initialName = defaultName
        ) { inputName ->
            val normalizedName = inputName.trim()
            if (normalizedName.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_group_name_required), Toast.LENGTH_SHORT).show()
                return@showGroupNameDialog false
            }
            if (isGroupNameUsed(normalizedName)) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_duplicate_group_name_format, normalizedName),
                    Toast.LENGTH_SHORT
                ).show()
                return@showGroupNameDialog false
            }

            val newGroup = DomainMappingGroup(
                id = UUID.randomUUID().toString(),
                name = normalizedName,
                targetIp = "",
                domains = linkedSetOf()
            )
            groups += newGroup
            Prefs.setMappingGroups(this, groups)
            renderGroupSelector()
            switchToGroup(groups.lastIndex, persistCurrent = false, refreshIfRunning = true)
            true
        }
    }

    private fun removeGroupAt(position: Int) {
        if (position !in groups.indices) return
        if (groups.size <= 1) {
            Toast.makeText(this, getString(R.string.toast_cannot_remove_last_group), Toast.LENGTH_SHORT).show()
            return
        }

        persistCurrentGroupFromUi()
        groups.removeAt(position)
        if (position < selectedGroupIndex) {
            selectedGroupIndex -= 1
        } else if (selectedGroupIndex > groups.lastIndex) {
            selectedGroupIndex = groups.lastIndex
        }

        Prefs.setMappingGroups(this, groups)
        renderGroupSelector()
        switchToGroup(selectedGroupIndex, persistCurrent = false, refreshIfRunning = true)
    }

    private fun renameGroupAt(position: Int) {
        if (position !in groups.indices) return
        val group = groups[position]
        showGroupNameDialog(
            title = getString(R.string.dialog_edit_group_title),
            initialName = group.name
        ) { inputName ->
            val normalizedName = inputName.trim()
            if (normalizedName.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_group_name_required), Toast.LENGTH_SHORT).show()
                return@showGroupNameDialog false
            }
            if (isGroupNameUsed(normalizedName, excludeIndex = position)) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_duplicate_group_name_format, normalizedName),
                    Toast.LENGTH_SHORT
                ).show()
                return@showGroupNameDialog false
            }

            group.name = normalizedName
            Prefs.setMappingGroups(this, groups)
            renderGroupSelector()
            if (position == selectedGroupIndex) {
                suppressGroupSelection = true
                groupSelectInput.setText(group.name, false)
                suppressGroupSelection = false
            }
            true
        }
    }

    private fun persistCurrentGroupFromUi() {
        if (selectedGroupIndex !in groups.indices) return
        val group = groups[selectedGroupIndex]
        group.targetIp = ipInput.text?.toString()?.trim().orEmpty()
        group.domains.clear()
        group.domains.addAll(currentDomains)
        Prefs.setMappingGroups(this, groups)
        Prefs.setSelectedGroupId(this, group.id)
    }

    private fun startVpnFromUi() {
        persistCurrentGroupFromUi()

        if (hasAnyIllegalDomainChars()) {
            vpnSwitch.setOnCheckedChangeListener(null)
            vpnSwitch.isChecked = false
            vpnSwitch.setOnCheckedChangeListener(switchListener)
            updateStatus(false)
            Toast.makeText(this, getString(R.string.toast_illegal_domain_chars), Toast.LENGTH_SHORT).show()
            return
        }

        val duplicateName = findDuplicateGroupName()
        if (duplicateName != null) {
            vpnSwitch.setOnCheckedChangeListener(null)
            vpnSwitch.isChecked = false
            vpnSwitch.setOnCheckedChangeListener(switchListener)
            updateStatus(false)
            Toast.makeText(
                this,
                getString(R.string.toast_duplicate_group_name_format, duplicateName),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (validateDomainSwitch.isChecked && hasAnyInvalidDomain()) {
            vpnSwitch.setOnCheckedChangeListener(null)
            vpnSwitch.isChecked = false
            vpnSwitch.setOnCheckedChangeListener(switchListener)
            updateStatus(false)
            Toast.makeText(this, getString(R.string.toast_invalid_host), Toast.LENGTH_SHORT).show()
            return
        }

        val conflict = findDomainIpConflict()
        if (conflict != null) {
            vpnSwitch.setOnCheckedChangeListener(null)
            vpnSwitch.isChecked = false
            vpnSwitch.setOnCheckedChangeListener(switchListener)
            updateStatus(false)
            Toast.makeText(
                this,
                getString(
                    R.string.toast_domain_ip_conflict_format,
                    conflict.domain,
                    conflict.firstIp,
                    conflict.secondIp
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val active = collectActiveGroups()
        if (active.isEmpty()) {
            vpnSwitch.setOnCheckedChangeListener(null)
            vpnSwitch.isChecked = false
            vpnSwitch.setOnCheckedChangeListener(switchListener)
            updateStatus(false)
            Toast.makeText(this, getString(R.string.toast_no_active_groups), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, HostMapVpnService::class.java).apply {
            action = HostMapVpnService.ACTION_START
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

    private fun addDomainFromInput() {
        val domain = normalizeDomain(domainInput.text?.toString().orEmpty())
        val shouldValidate = validateDomainSwitch.isChecked
        if (domain.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_invalid_host), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasOnlyAllowedDomainChars(domain)) {
            Toast.makeText(this, getString(R.string.toast_illegal_domain_chars), Toast.LENGTH_SHORT).show()
            return
        }
        if (shouldValidate && !isValidDomain(domain)) {
            Toast.makeText(this, getString(R.string.toast_invalid_host), Toast.LENGTH_SHORT).show()
            return
        }

        val selectedGroupIp = ipInput.text?.toString()?.trim().orEmpty()
        if (isValidIpv4(selectedGroupIp)) {
            val conflict = findDomainConflictInOtherGroups(domain, selectedGroupIp, selectedGroupIndex)
            if (conflict != null) {
                Toast.makeText(
                    this,
                    getString(
                        R.string.toast_domain_ip_conflict_format,
                        conflict.domain,
                        conflict.firstIp,
                        conflict.secondIp
                    ),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }

        if (currentDomains.add(domain)) {
            renderDomainChips()
            persistCurrentGroupFromUi()
            refreshVpnIfRunning()
        }
        domainInput.setText("")
    }

    private fun renderDomainChips() {
        domainChipGroup.removeAllViews()
        currentDomains.forEach { domain ->
            val chip = Chip(this).apply {
                text = domain
                isCloseIconVisible = true
                setEnsureMinTouchTargetSize(false)
                setOnCloseIconClickListener {
                    removeDomain(domain)
                }
            }
            domainChipGroup.addView(chip)
        }
        updateStatus(vpnSwitch.isChecked)
    }

    private fun removeDomain(domain: String) {
        if (!currentDomains.remove(domain)) return
        renderDomainChips()
        persistCurrentGroupFromUi()
        refreshVpnIfRunning()
    }

    private fun refreshVpnIfRunning() {
        if (!vpnSwitch.isChecked) return
        persistCurrentGroupFromUi()
        val intent = Intent(this, HostMapVpnService::class.java).apply {
            action = HostMapVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        updateStatus(true)
    }

    private fun updateStatus(enabled: Boolean) {
        if (!enabled) {
            statusText.text = getString(R.string.status_off)
            return
        }
        val active = collectActiveGroups()
        val domainCount = active.sumOf { it.domains.size }
        statusText.text = getString(R.string.status_on_groups_format, domainCount, active.size)
    }

    private fun collectActiveGroups(): List<DomainMappingGroup> {
        val validateDomain = validateDomainSwitch.isChecked
        return groups.filter { group ->
            val ipValid = isValidIpv4(group.targetIp)
            val domains = group.domains.filter { it.isNotBlank() }
            val domainsValid = if (!validateDomain) true else domains.all(::isValidDomain)
            ipValid && domains.isNotEmpty() && domainsValid
        }
    }

    private fun hasAnyInvalidDomain(): Boolean {
        return groups.any { group ->
            group.domains.any { !isValidDomain(it) }
        }
    }

    private fun isValidIpv4(input: String): Boolean {
        return try {
            val addr = InetAddress.getByName(input.trim())
            addr.hostAddress == input.trim() && addr.address.size == 4
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeDomain(raw: String): String {
        return raw.trim()
            .lowercase()
            .trimEnd('.')
    }

    private fun canonicalDomain(domain: String): String {
        return domain
    }

    private fun normalizeGroupName(name: String): String {
        return name.trim().lowercase()
    }

    private fun isGroupNameUsed(name: String, excludeIndex: Int? = null): Boolean {
        val normalized = normalizeGroupName(name)
        groups.forEachIndexed { index, group ->
            if (excludeIndex == index) return@forEachIndexed
            if (normalizeGroupName(group.name) == normalized) {
                return true
            }
        }
        return false
    }

    private fun findDuplicateGroupName(): String? {
        val seen = linkedSetOf<String>()
        groups.forEach { group ->
            val normalized = normalizeGroupName(group.name)
            if (normalized.isEmpty()) return@forEach
            if (!seen.add(normalized)) {
                return group.name
            }
        }
        return null
    }

    private fun findDomainIpConflict(): DomainIpConflict? {
        val domainToIp = linkedMapOf<String, String>()
        groups.forEach { group ->
            val groupIp = group.targetIp.trim()
            if (!isValidIpv4(groupIp)) return@forEach

            group.domains.forEach domainLoop@{ rawDomain ->
                val normalizedDomain = normalizeDomain(rawDomain)
                if (normalizedDomain.isEmpty()) return@domainLoop
                val canonical = canonicalDomain(normalizedDomain)

                val existingIp = domainToIp[canonical]
                if (existingIp == null) {
                    domainToIp[canonical] = groupIp
                    return@domainLoop
                }
                if (existingIp != groupIp) {
                    return DomainIpConflict(canonical, existingIp, groupIp)
                }
            }
        }
        return null
    }

    private fun hasAnyIllegalDomainChars(): Boolean {
        return groups.any { group ->
            group.domains.any { domain ->
                val normalized = normalizeDomain(domain)
                normalized.isEmpty() || !hasOnlyAllowedDomainChars(normalized)
            }
        }
    }

    private fun findDomainConflictInOtherGroups(
        domain: String,
        currentGroupIp: String,
        currentGroupIndex: Int
    ): DomainIpConflict? {
        val canonical = canonicalDomain(normalizeDomain(domain))
        if (canonical.isEmpty()) return null

        groups.forEachIndexed { index, group ->
            if (index == currentGroupIndex) return@forEachIndexed
            val otherIp = group.targetIp.trim()
            if (!isValidIpv4(otherIp)) return@forEachIndexed
            if (otherIp == currentGroupIp) return@forEachIndexed

            val hasSameDomain = group.domains.any { existing ->
                canonicalDomain(normalizeDomain(existing)) == canonical
            }
            if (hasSameDomain) {
                return DomainIpConflict(canonical, otherIp, currentGroupIp)
            }
        }
        return null
    }

    private fun hasOnlyAllowedDomainChars(domain: String): Boolean {
        if (domain.length !in 1..253) return false
        if (domain.startsWith(".") || domain.endsWith(".")) return false
        if (domain.contains("..")) return false
        if (!domain.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }) return false

        val labels = domain.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return false
        return true
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain.length !in 3..253) return false
        val pattern = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")
        return pattern.matches(domain)
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
        domainInput.clearFocus()
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(focusedView.windowToken, 0)
    }

    private fun showGroupNameDialog(title: String, initialName: String, onConfirm: (String) -> Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_group_name, null, false)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.groupNameInputLayout)
        val inputEdit = dialogView.findViewById<TextInputEditText>(R.id.groupNameInputEdit)
        inputEdit.setText(initialName)
        inputEdit.setSelection(inputEdit.text?.length ?: 0)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val name = inputEdit.text?.toString().orEmpty().trim()
                if (name.isEmpty()) {
                    inputLayout.error = getString(R.string.toast_group_name_required)
                    return@setOnClickListener
                }
                inputLayout.error = null
                val success = onConfirm(name)
                if (success) {
                    dialog.dismiss()
                }
            }
            inputEdit.requestFocus()
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(inputEdit, InputMethodManager.SHOW_IMPLICIT)
        }

        dialog.show()
    }

    private inner class GroupDropdownAdapter : ArrayAdapter<DomainMappingGroup>(this@MainActivity, 0, groups) {
        override fun getCount(): Int = groups.size

        override fun getItem(position: Int): DomainMappingGroup = groups[position]

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return buildRow(position, convertView, parent, showDelete = true)
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            return buildRow(position, convertView, parent, showDelete = true)
        }

        private fun buildRow(position: Int, convertView: View?, parent: ViewGroup, showDelete: Boolean): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_group_dropdown, parent, false)
            val nameText = view.findViewById<TextView>(R.id.groupNameText)
            val editButton = view.findViewById<ImageView>(R.id.groupEditButton)
            val deleteButton = view.findViewById<ImageView>(R.id.groupDeleteButton)
            val group = getItem(position)

            nameText.text = group.name
            deleteButton.visibility = if (showDelete) View.VISIBLE else View.GONE
            editButton.visibility = if (showDelete) View.VISIBLE else View.GONE

            if (showDelete) {
                val canDelete = groups.size > 1
                editButton.setOnClickListener {
                    ignoreNextGroupSelection = true
                    groupSelectInput.dismissDropDown()
                    renameGroupAt(position)
                }
                deleteButton.isEnabled = canDelete
                deleteButton.alpha = if (canDelete) 1f else 0.35f
                deleteButton.setOnClickListener {
                    if (!canDelete) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.toast_cannot_remove_last_group),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    ignoreNextGroupSelection = true
                    removeGroupAt(position)
                    groupSelectInput.dismissDropDown()
                }
            } else {
                editButton.setOnClickListener(null)
                deleteButton.setOnClickListener(null)
            }

            return view
        }
    }
}
