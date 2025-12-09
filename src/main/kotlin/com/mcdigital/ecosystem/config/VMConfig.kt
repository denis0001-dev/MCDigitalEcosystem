package com.mcdigital.ecosystem.config

data class VMConfig(
    val ramMB: Int = 512,
    val diskSizeGB: Int = 10,
    val osImagePath: String? = null
) {
    companion object {
        fun getDefaultConfig(): VMConfig {
            return VMConfig()
        }
    }
}

