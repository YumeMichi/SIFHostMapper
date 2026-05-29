package com.yumemichi.sifhostmapper

data class DomainMappingGroup(
    val id: String,
    var name: String,
    var targetIp: String,
    val domains: LinkedHashSet<String>
)
