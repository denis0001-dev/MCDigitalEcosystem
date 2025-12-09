package com.mcdigital.ecosystem.persistence

import net.minecraft.core.BlockPos
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class VMStateManager(private val worldId: String, private val blockPos: BlockPos) {
    
    fun getStateName(): String {
        return "state_${blockPos.x}_${blockPos.y}_${blockPos.z}"
    }
    
    fun getStateDirectory(): Path {
        return Paths.get("saves", worldId, "data", "mcdigitalecosystem", "vms", 
            "${blockPos.x}_${blockPos.y}_${blockPos.z}")
    }
    
    fun saveState() {
        val stateDir = getStateDirectory()
        Files.createDirectories(stateDir)
        // State is saved by VMManager via QEMU monitor
    }
    
    fun loadState(): Boolean {
        val stateDir = getStateDirectory()
        return Files.exists(stateDir)
    }
    
    fun cleanup() {
        val stateDir = getStateDirectory()
        if (Files.exists(stateDir)) {
            try {
                Files.walk(stateDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.delete(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

