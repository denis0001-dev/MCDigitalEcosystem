package com.mcdigital.ecosystem.vm

import com.mcdigital.ecosystem.config.VMConfig
import net.minecraft.core.BlockPos
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class VMManager {
    private val runningVMs = ConcurrentHashMap<BlockPos, Process>()
    private val vmPorts = ConcurrentHashMap<BlockPos, Int>()
    private var nextPort = 5900
    private var qemuPath: String? = null

    init {
        initializeQEMU()
    }

    private fun initializeQEMU() {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        
        val qemuBinaryName = when {
            os.contains("win") -> "qemu-system-x86_64.exe"
            os.contains("mac") -> "qemu-system-x86_64"
            else -> "qemu-system-x86_64"
        }
        
        val resourcePath = when {
            os.contains("win") -> "qemu/windows/$qemuBinaryName"
            os.contains("mac") && arch.contains("aarch64") || arch.contains("arm64") -> "qemu/macos-arm64/$qemuBinaryName"
            os.contains("mac") -> "qemu/macos-x86_64/$qemuBinaryName"
            else -> "qemu/linux/$qemuBinaryName"
        }
        
        try {
            val qemuFile = extractQEMUBinary(resourcePath, qemuBinaryName)
            if (qemuFile != null && qemuFile.exists() && qemuFile.canExecute()) {
                qemuPath = qemuFile.absolutePath
            } else {
                // Fallback to system QEMU
                qemuPath = "qemu-system-x86_64"
            }
        } catch (e: Exception) {
            println("Failed to extract QEMU binary, using system QEMU: ${e.message}")
            qemuPath = "qemu-system-x86_64"
        }
    }

    private fun extractQEMUBinary(resourcePath: String, binaryName: String): File? {
        val modDataDir = Paths.get(System.getProperty("user.home"), ".minecraft", "mods", "mcdigitalecosystem", "qemu")
        Files.createDirectories(modDataDir)
        
        val qemuFile = modDataDir.resolve(binaryName).toFile()
        
        // Check if already extracted
        if (qemuFile.exists()) {
            return qemuFile
        }
        
        // Try to extract from resources
        val resourceStream = javaClass.classLoader.getResourceAsStream(resourcePath)
        if (resourceStream != null) {
            Files.copy(resourceStream, qemuFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            resourceStream.close()
            qemuFile.setExecutable(true)
            return qemuFile
        }
        
        return null
    }

    fun startVM(blockPos: BlockPos, stateName: String? = null): Int {
        if (runningVMs.containsKey(blockPos)) {
            return vmPorts[blockPos] ?: 0
        }

        val port = nextPort++
        val stateDir = getVMStateDirectory(blockPos)
        Files.createDirectories(stateDir)

        val config = VMConfig.getDefaultConfig()
        val diskImage = stateDir.resolve("disk.qcow2").toFile()
        
        // Create disk image if it doesn't exist
        if (!diskImage.exists()) {
            createDiskImage(diskImage, config.diskSizeGB)
        }

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val qmpSocket = if (isWindows) {
            stateDir.resolve("qmp.sock").toFile() // Will use TCP on Windows
        } else {
            stateDir.resolve("qmp.sock").toFile()
        }
        val monitorSocket = if (isWindows) {
            stateDir.resolve("monitor.sock").toFile() // Will use TCP on Windows
        } else {
            stateDir.resolve("monitor.sock").toFile()
        }

        val command = buildList {
            add(qemuPath ?: "qemu-system-x86_64")
            add("-m")
            add("${config.ramMB}M")
            add("-drive")
            add("file=${diskImage.absolutePath},format=qcow2")
            add("-spice")
            add("port=$port,addr=127.0.0.1,disable-ticketing")
            if (isWindows) {
                // Use TCP sockets on Windows
                add("-qmp")
                add("tcp:127.0.0.1:${port + 1},server,nowait")
                add("-monitor")
                add("tcp:127.0.0.1:${port + 2},server,nowait")
            } else {
                add("-qmp")
                add("unix:${qmpSocket.absolutePath},server,nowait")
                add("-monitor")
                add("unix:${monitorSocket.absolutePath},server,nowait")
            }
            add("-vga")
            add("qxl")
            add("-device")
            add("qxl-vga")
            if (stateName != null) {
                add("-loadvm")
                add(stateName)
            }
            add("-daemonize")
        }

        try {
            val processBuilder = ProcessBuilder(command)
            processBuilder.directory(stateDir.toFile())
            val process = processBuilder.start()
            
            runningVMs[blockPos] = process
            vmPorts[blockPos] = port
            
            // Wait a bit for QEMU to start
            Thread.sleep(1000)
            
            return port
        } catch (e: IOException) {
            e.printStackTrace()
            return 0
        }
    }

    private fun createDiskImage(diskFile: File, sizeGB: Int) {
        val command = listOf(
            qemuPath?.replace("qemu-system-x86_64", "qemu-img") ?: "qemu-img",
            "create",
            "-f", "qcow2",
            diskFile.absolutePath,
            "${sizeGB}G"
        )
        
        try {
            val process = ProcessBuilder(command).start()
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopVM(blockPos: BlockPos) {
        val process = runningVMs.remove(blockPos)
        vmPorts.remove(blockPos)
        
        if (process != null) {
            // Send shutdown command via QMP
            val stateDir = getVMStateDirectory(blockPos)
            val qmpSocket = stateDir.resolve("qmp.sock").toFile()
            
            if (qmpSocket.exists()) {
                try {
                    sendQMPCommand(qmpSocket, """{"execute": "quit"}""")
                } catch (e: Exception) {
                    // Force kill if QMP fails
                    process.destroyForcibly()
                }
            } else {
                process.destroyForcibly()
            }
        }
    }

    fun saveVMState(blockPos: BlockPos, stateName: String) {
        val stateDir = getVMStateDirectory(blockPos)
        val monitorSocket = stateDir.resolve("monitor.sock").toFile()
        
        if (monitorSocket.exists()) {
            sendMonitorCommand(monitorSocket, "savevm $stateName")
        }
    }

    private fun sendQMPCommand(socket: File, command: String) {
        // QMP protocol implementation would go here
        // For now, using monitor socket
    }

    private fun sendMonitorCommand(socket: File, command: String) {
        try {
            if (System.getProperty("os.name").lowercase().contains("win")) {
                // Windows doesn't support Unix domain sockets well, use TCP instead
                // For now, skip monitor commands on Windows
                return
            }
            
            val socketPath = java.net.UnixDomainSocketAddress.of(socket.toPath())
            val channel = java.nio.channels.SocketChannel.open(socketPath)
            val writer = java.io.PrintWriter(
                java.nio.channels.Channels.newWriter(channel, java.nio.charset.StandardCharsets.UTF_8),
                true
            )
            writer.println(command)
            writer.close()
            channel.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getVMStateDirectory(blockPos: BlockPos): Path {
        val worldDir = Paths.get("saves", "world", "data", "mcdigitalecosystem", "vms")
        return worldDir.resolve("${blockPos.x}_${blockPos.y}_${blockPos.z}")
    }

    fun cleanup() {
        runningVMs.keys.forEach { stopVM(it) }
    }
}

