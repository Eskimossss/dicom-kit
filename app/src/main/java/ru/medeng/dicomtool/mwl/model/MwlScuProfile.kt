package ru.medeng.dicomtool.mwl.model

data class MwlScuProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val host: String = "127.0.0.1",
    val port: Int = 11112,
    val calledAet: String = "MWL_SCP",
    val callingAet: String = "DICOMKIT_SCU",
    val connectionTimeoutMs: Int = 10000,
    val responseTimeoutMs: Int = 15000,
    val isDefault: Boolean = false
)
