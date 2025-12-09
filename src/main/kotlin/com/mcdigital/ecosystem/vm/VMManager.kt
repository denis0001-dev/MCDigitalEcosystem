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
    private val vmQMPPorts = ConcurrentHashMap<BlockPos, Int>()
    private val vmMonitorPorts = ConcurrentHashMap<BlockPos, Int>()
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
        
        // Try to find QEMU (VNC is standard, no special build needed)
        // Priority: 1. Bundled QEMU, 2. System QEMU
        val possiblePaths = when {
            os.contains("mac") -> listOf(
                "/opt/homebrew/bin/$qemuBinaryName",
                "/usr/local/bin/$qemuBinaryName"
            )
            os.contains("win") -> listOf(
                "C:\\Program Files\\qemu\\$qemuBinaryName",
                "qemu-system-x86_64.exe"
            )
            else -> listOf(
                "/usr/bin/$qemuBinaryName",
                "/usr/local/bin/$qemuBinaryName"
            )
        }
        
        // First try bundled QEMU
        val resourcePath = when {
            os.contains("win") -> "qemu/windows/$qemuBinaryName"
            os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> "qemu/macos-arm64/$qemuBinaryName"
            os.contains("mac") -> "qemu/macos-x86_64/$qemuBinaryName"
            else -> "qemu/linux/$qemuBinaryName"
        }
        
        try {
            val qemuFile = extractQEMUBinary(resourcePath, qemuBinaryName)
            if (qemuFile != null && qemuFile.exists() && qemuFile.canExecute()) {
                qemuPath = qemuFile.absolutePath
                println("[VMManager] Using bundled QEMU: $qemuPath")
                return
            }
        } catch (e: Exception) {
            println("[VMManager] Failed to extract bundled QEMU: ${e.message}")
        }
        
        // Try system paths
        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                qemuPath = file.absolutePath
                println("[VMManager] Using system QEMU: $qemuPath")
                return
            }
        }
        
        // Fallback to PATH
        qemuPath = "qemu-system-x86_64"
        println("[VMManager] Using QEMU from PATH: $qemuPath")
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

        // Use TCP sockets instead of UNIX sockets to avoid path length limitations
        // QEMU has a 104-byte limit for UNIX socket paths, which can be exceeded
        // TCP sockets work on all platforms and don't have this limitation
        val qmpPort = port + 1
        val monitorPort = port + 2

        // Build QEMU command with VNC support
        // Format: -vnc :display (where display = port - 5900)
        val command = buildList {
            add(qemuPath ?: "qemu-system-x86_64")
            add("-m")
            add("${config.ramMB}M")
            add("-drive")
            add("file=${diskImage.absolutePath},format=qcow2")
            
            // VNC display - format: -vnc :display (where display = port - 5900)
            // VNC ports start at 5900, so display 0 = port 5900, display 1 = port 5901, etc.
            // QEMU VNC format: -vnc :display (binds to all interfaces) or -vnc host:display
            // We use :display which binds to 0.0.0.0:5900+display, accessible via 127.0.0.1
            val display = port - 5900
            add("-vnc")
            add(":$display")
            println("[VMManager] VNC display: $display (port $port)")
            
            // Use standard VGA (VNC works with any VGA device)
            add("-vga")
            add("std")
            
            // Use TCP sockets for QMP and monitor to avoid UNIX socket path length limitations
            // QEMU has a 104-byte limit for UNIX socket paths
            add("-qmp")
            add("tcp:127.0.0.1:$qmpPort,server,nowait")
            add("-monitor")
            add("tcp:127.0.0.1:$monitorPort,server,nowait")
            // Only load snapshot if it exists
            if (stateName != null && snapshotExists(diskImage, stateName)) {
                add("-loadvm")
                add(stateName)
                println("[VMManager] Loading snapshot: $stateName")
            } else if (stateName != null) {
                println("[VMManager] Snapshot '$stateName' does not exist, starting VM fresh")
            }
            add("-daemonize")
        }

        try {
            println("[VMManager] Starting QEMU with command: ${command.joinToString(" ")}")
            println("[VMManager] Working directory: ${stateDir.toAbsolutePath()}")
            
            val processBuilder = ProcessBuilder(command)
            processBuilder.directory(stateDir.toFile())
            // Redirect stderr to stdout to capture all output
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            
            // Start a thread to read output in real-time
            val outputThread = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        println("[VMManager] QEMU output: $line")
                    }
                } catch (e: Exception) {
                    // Stream closed, that's okay
                }
            }
            outputThread.isDaemon = true
            outputThread.start()
            
            // Wait a bit for QEMU to daemonize (it forks and exits immediately)
            Thread.sleep(500)
            
            // For daemonized processes, the parent process exits immediately
            // We need to check if the VNC port is listening instead
            // Store the port mapping even though the process is gone
            runningVMs[blockPos] = process // Keep reference for cleanup, but it will be dead
            vmPorts[blockPos] = port
            vmQMPPorts[blockPos] = qmpPort
            vmMonitorPorts[blockPos] = monitorPort
            
            // Wait a bit more for QEMU to start and VNC server to be ready
            Thread.sleep(2000) // Give VNC server time to start
            
            // Check if VNC port is listening (better check for daemonized QEMU)
            val portListening = try {
                val testSocket = java.net.Socket()
                testSocket.connect(java.net.InetSocketAddress("127.0.0.1", port), 100)
                testSocket.close()
                true
            } catch (e: Exception) {
                false
            }
            
            if (!portListening) {
                // Port not listening, QEMU might have failed
                // Check if process exited with error
                if (!process.isAlive) {
                    val exitCode = process.exitValue()
                    if (exitCode != 0) {
                        val error = try {
                            process.errorStream.bufferedReader().use { it.readText() }
                        } catch (e: Exception) {
                            "Could not read error stream: ${e.message}"
                        }
                        val output = try {
                            process.inputStream.bufferedReader().use { it.readText() }
                        } catch (e: Exception) {
                            "Could not read output stream: ${e.message}"
                        }
                        
                        // Provide helpful error message for VNC issues
                        if (error.contains("vnc") || error.contains("invalid option") || output.contains("vnc")) {
                            throw IOException(
                                "QEMU VNC support error (exit code $exitCode).\n" +
                                "This QEMU build may not have VNC enabled.\n" +
                                "Error: $error\n" +
                                "Output: $output\n" +
                                "Command: ${command.joinToString(" ")}\n" +
                                "Please ensure QEMU has VNC support (standard QEMU builds include it).",
                                null
                            )
                        }
                        
                        throw IOException("QEMU process exited immediately (exit code $exitCode). Error: $error\nOutput: $output\nCommand: ${command.joinToString(" ")}")
                    }
                }
                // If process is alive or exited with 0, but port not listening, QEMU might still be starting
                // Try one more time after a delay
                Thread.sleep(1000)
                val portListeningRetry = try {
                    val testSocket = java.net.Socket()
                    testSocket.connect(java.net.InetSocketAddress("127.0.0.1", port), 100)
                    testSocket.close()
                    true
                } catch (e: Exception) {
                    false
                }
                if (!portListeningRetry) {
                    val error = try {
                        process.errorStream.bufferedReader().use { it.readText() }
                    } catch (e: Exception) {
                        ""
                    }
                    val output = try {
                        process.inputStream.bufferedReader().use { it.readText() }
                    } catch (e: Exception) {
                        ""
                    }
                    throw IOException("QEMU daemonized but VNC port $port is not listening. QEMU may have failed to start.\nError: $error\nOutput: $output")
                }
            }
            
            println("[VMManager] VM should be running on VNC port $port (display ${port - 5900})")
            
            return port
        } catch (e: IOException) {
            if (e.message?.contains("No such file or directory") == true || 
                e.message?.contains("error=2") == true) {
                val errorMsg = 
                    "QEMU not found. Please install QEMU or bundle it with the mod.\n" +
                    "Attempted to run: ${qemuPath ?: "qemu-system-x86_64"}\n" +
                    "You can install QEMU via:\n" +
                    "  macOS: brew install qemu\n" +
                    "  Linux: sudo apt-get install qemu-system-x86 qemu-utils\n" +
                    "  Windows: Download from https://www.qemu.org/download/"
                System.err.println(errorMsg)
                throw IOException(errorMsg, e)
            }
            e.printStackTrace()
            throw e
        }
    }

    private fun snapshotExists(diskFile: File, snapshotName: String): Boolean {
        if (!diskFile.exists()) {
            return false
        }
        
        val qemuImgPath = qemuPath?.replace("qemu-system-x86_64", "qemu-img")
            ?: qemuPath?.replace("qemu-system-x86_64.exe", "qemu-img.exe")
            ?: "qemu-img"
        
        val command = listOf(
            qemuImgPath,
            "snapshot",
            "-l",
            diskFile.absolutePath
        )
        
        return try {
            val process = ProcessBuilder(command).start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                // Check if snapshot name appears in the output
                // qemu-img snapshot -l output format:
                // Snapshot list:
                // ID        TAG               VM SIZE                DATE       VM CLOCK
                // 1         snapshot_name     100M 2024-01-01 12:00:00   00:00:00.000
                output.lines().any { line -> 
                    line.contains(snapshotName) && !line.contains("TAG") // Exclude header line
                }
            } else {
                false
            }
        } catch (e: Exception) {
            // If we can't check, assume it doesn't exist to avoid loading a non-existent snapshot
            println("[VMManager] Could not check if snapshot exists: ${e.message}")
            false
        }
    }

    private fun createDiskImage(diskFile: File, sizeGB: Int) {
        val qemuImgPath = qemuPath?.replace("qemu-system-x86_64", "qemu-img")
            ?: qemuPath?.replace("qemu-system-x86_64.exe", "qemu-img.exe")
            ?: "qemu-img"
        
        val command = listOf(
            qemuImgPath,
            "create",
            "-f", "qcow2",
            diskFile.absolutePath,
            "${sizeGB}G"
        )
        
        try {
            val process = ProcessBuilder(command).start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                throw IOException("qemu-img failed with exit code $exitCode: $error")
            }
        } catch (e: IOException) {
            if (e.message?.contains("No such file or directory") == true || 
                e.message?.contains("error=2") == true) {
                throw IOException(
                    "QEMU not found. Please install QEMU or bundle it with the mod.\n" +
                    "Attempted to run: $qemuImgPath\n" +
                    "You can install QEMU via:\n" +
                    "  macOS: brew install qemu\n" +
                    "  Linux: sudo apt-get install qemu-system-x86 qemu-utils\n" +
                    "  Windows: Download from https://www.qemu.org/download/",
                    e
                )
            }
            throw e
        } catch (e: Exception) {
            throw IOException("Failed to create disk image: ${e.message}", e)
        }
    }

    fun stopVM(blockPos: BlockPos) {
        val process = runningVMs.remove(blockPos)
        val qmpPort = vmQMPPorts.remove(blockPos)
        vmPorts.remove(blockPos)
        vmMonitorPorts.remove(blockPos)
        
        if (process != null) {
            // Send shutdown command via QMP over TCP
            if (qmpPort != null) {
                try {
                    sendQMPCommandTCP(qmpPort, """{"execute": "quit"}""")
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
        val monitorPort = vmMonitorPorts[blockPos]
        
        if (monitorPort != null) {
            sendMonitorCommandTCP(monitorPort, "savevm $stateName")
        }
    }

    private fun sendQMPCommandTCP(port: Int, command: String) {
        try {
            val socket = java.net.Socket("127.0.0.1", port)
            socket.soTimeout = 5000 // 5 second timeout
            val writer = java.io.PrintWriter(
                java.io.OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8),
                true
            )
            // QMP requires a greeting handshake first
            val reader = java.io.BufferedReader(
                java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)
            )
            // Read QMP greeting
            reader.readLine()
            // Send capabilities negotiation
            writer.println("""{"execute": "qmp_capabilities"}""")
            reader.readLine()
            // Send actual command
            writer.println(command)
            reader.readLine()
            writer.close()
            reader.close()
            socket.close()
        } catch (e: Exception) {
            System.err.println("[VMManager] Error sending QMP command: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun sendMonitorCommandTCP(port: Int, command: String) {
        try {
            val socket = java.net.Socket("127.0.0.1", port)
            socket.soTimeout = 5000 // 5 second timeout
            val writer = java.io.PrintWriter(
                java.io.OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8),
                true
            )
            writer.println(command)
            writer.flush()
            // Read response (monitor commands echo back)
            val reader = java.io.BufferedReader(
                java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)
            )
            // Read and discard response
            try {
                reader.readLine()
            } catch (e: Exception) {
                // Ignore read errors
            }
            writer.close()
            reader.close()
            socket.close()
        } catch (e: Exception) {
            System.err.println("[VMManager] Error sending monitor command: ${e.message}")
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
    
    fun isVMRunning(blockPos: BlockPos): Boolean {
        val port = vmPorts[blockPos] ?: return false
        
        // For daemonized QEMU, the process exits immediately but QEMU keeps running
        // Check if the VNC port is listening instead
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 100)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}

