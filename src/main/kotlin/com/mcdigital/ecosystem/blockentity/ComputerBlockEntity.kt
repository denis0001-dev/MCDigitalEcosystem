package com.mcdigital.ecosystem.blockentity

import com.mcdigital.ecosystem.MCDigitalEcosystem
import com.mcdigital.ecosystem.input.InputHandler
import com.mcdigital.ecosystem.persistence.VMStateManager
import com.mcdigital.ecosystem.vnc.VNCClientWrapper
import com.mcdigital.ecosystem.vm.VMManager
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

class ComputerBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(
    MCDigitalEcosystem.COMPUTER_BLOCK_ENTITY.get(), pos, state
) {

    private var vmManager: VMManager? = null
    private var vncClient: VNCClientWrapper? = null
    private var inputHandler: InputHandler? = null
    private var stateManager: VMStateManager? = null
    private var focusedPlayer: Player? = null
    private var isRunning = false
    private var vncPort = 0

    init {
        vmManager = VMManager()
        stateManager = VMStateManager(level?.dimension()?.location()?.toString() ?: "unknown", blockPos)
        
        // If this is server-side and we don't have a port, the VM needs to be started
        // But we can't start it in init, so we'll do it on first tick or interaction
    }

    private var connectionAttempts = 0
    private var lastConnectionAttempt = 0L
    
    fun tick() {
        if (level?.isClientSide == true) {
            // Try to connect if port is available but client isn't connected
            if (vncPort > 0 && vncClient == null) {
                val now = System.currentTimeMillis()
                // Retry connection every 2 seconds, with exponential backoff
                val retryDelay = minOf(2000L * (1 shl minOf(connectionAttempts, 4)), 30000L)
                
                if (now - lastConnectionAttempt >= retryDelay) {
                    lastConnectionAttempt = now
                    connectionAttempts++
                    println("[ComputerBlockEntity] Client: Attempting to connect to VNC on port $vncPort (attempt $connectionAttempts)")
                    try {
                        vncClient = VNCClientWrapper("127.0.0.1", vncPort) { frame ->
                            // Frame update callback - will be handled by renderer
                            com.mcdigital.ecosystem.renderer.ComputerBlockRendererHelper.updateFrameForBlock(this, frame)
                        }
                        if (vncClient?.isConnected() == true) {
                            println("[ComputerBlockEntity] Client: Successfully connected to VNC on port $vncPort")
                            inputHandler = InputHandler(vncClient!!)
                            connectionAttempts = 0 // Reset on success
                        } else {
                            println("[ComputerBlockEntity] Client: VNC client created but not connected, will retry")
                            vncClient = null
                        }
                    } catch (e: Exception) {
                        // Connection failed, will retry next tick
                        if (connectionAttempts <= 3 || connectionAttempts % 10 == 0) {
                            // Only log first few attempts and then every 10th attempt to avoid spam
                            System.err.println("[ComputerBlockEntity] Client: Failed to connect to VNC on port $vncPort (attempt $connectionAttempts): ${e.message}")
                        }
                        vncClient = null
                    }
                }
            } else if (vncPort == 0) {
                // Reset connection attempts when port is 0
                connectionAttempts = 0
                lastConnectionAttempt = 0L
            }
            
            vncClient?.update()
            inputHandler?.tick()
        } else {
            // Server side - check if VM needs to be started
            // Auto-start VM if it's not running and port is 0
            if (!isRunning && vncPort == 0) {
                // Auto-start VM on first tick
                println("[ComputerBlockEntity] Server: Auto-starting VM (isRunning=$isRunning, vncPort=$vncPort)...")
                startVM()
            } else if (isRunning && vncPort == 0) {
                // VM was marked as running but port is 0, restart it
                println("[ComputerBlockEntity] Server: Detected VM should be running but port is 0, restarting VM...")
                isRunning = false
                startVM()
            } else if (isRunning && vncPort > 0) {
                // VM should be running - verify it's actually running
                val vmManager = vmManager
                if (vmManager != null && !vmManager.isVMRunning(blockPos)) {
                    println("[ComputerBlockEntity] Server: VM marked as running but process is not alive, restarting...")
                    // Don't reset vncPort here - keep it so we know what port to use
                    // The VM will be restarted without snapshot if it doesn't exist
                    isRunning = false
                    // Add a small delay to prevent rapid restart loops
                    Thread.sleep(100)
                    startVM()
                }
            }
        }
    }

    fun onInteract(player: Player) {
        if (level?.isClientSide == true) {
            if (focusedPlayer == null || focusedPlayer == player) {
                focusedPlayer = player
                // Start input capture if VNC client is connected
                if (vncClient != null && inputHandler == null) {
                    inputHandler = InputHandler(vncClient!!)
                    inputHandler?.startCapturing()
                } else if (vncClient == null && vncPort > 0) {
                    // Try to connect if not already connected
                    try {
                        vncClient = VNCClientWrapper("127.0.0.1", vncPort) { frame ->
                            com.mcdigital.ecosystem.renderer.ComputerBlockRendererHelper.updateFrameForBlock(this, frame)
                        }
                        inputHandler = InputHandler(vncClient!!)
                        inputHandler?.startCapturing()
                    } catch (e: Exception) {
                        System.err.println("Failed to connect to VNC: ${e.message}")
                    }
                }
            }
        } else {
            // Server side - start VM if not running or if port is 0
            if (!isRunning || vncPort == 0) {
                if (vncPort == 0 && isRunning) {
                    println("[ComputerBlockEntity] Server: VM marked as running but port is 0, restarting...")
                    isRunning = false
                }
                startVM()
            }
        }
    }

    fun onRemoved() {
        if (isRunning) {
            stopVM()
        }
        vncClient?.disconnect()
        inputHandler = null
        focusedPlayer = null
    }

    private fun startVM() {
        if (vmManager == null || stateManager == null) return
        
        try {
            println("[ComputerBlockEntity] Server: Starting VM at ${blockPos.x}, ${blockPos.y}, ${blockPos.z}")
            val stateName = stateManager!!.getStateName()
            vncPort = vmManager!!.startVM(
                blockPos = blockPos,
                stateName = if (stateName.isNotEmpty()) stateName else null
            )
            if (vncPort > 0) {
                isRunning = true
                println("[ComputerBlockEntity] Server: VM started successfully on VNC port $vncPort")
                println("[ComputerBlockEntity] Server: VNC server should be available at 127.0.0.1:$vncPort")
                setChanged()
                // Send update packet to clients
                level?.sendBlockUpdated(blockPos, blockState, blockState, 3)
                println("[ComputerBlockEntity] Server: Sent block update packet to clients")
            } else {
                System.err.println("[ComputerBlockEntity] Server: Failed to start VM: QEMU returned port 0")
                isRunning = false
                vncPort = 0
            }
        } catch (e: Exception) {
            System.err.println("[ComputerBlockEntity] Server: Failed to start VM: ${e.message}")
            e.printStackTrace()
            isRunning = false
            vncPort = 0
        }
    }

    private fun stopVM() {
        vmManager?.stopVM(blockPos)
        isRunning = false
        setChanged()
    }

    fun saveVMState() {
        if (isRunning && vmManager != null && stateManager != null) {
            val stateName = stateManager!!.getStateName()
            vmManager!!.saveVMState(blockPos, stateName)
        }
    }

    fun getVNCPort(): Int = vncPort
    fun isVMRunning(): Boolean = isRunning
    fun getFocusedPlayer(): Player? = focusedPlayer

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putBoolean("isRunning", isRunning)
        tag.putInt("vncPort", vncPort)
        
        // Save VM state when block entity is saved
        if (isRunning) {
            saveVMState()
        }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        val oldPort = vncPort
        isRunning = tag.getBoolean("isRunning")
        vncPort = tag.getInt("vncPort")
        
        if (level?.isClientSide == true) {
            println("[ComputerBlockEntity] Client: Loaded - isRunning=$isRunning, vncPort=$vncPort (old=$oldPort)")
        } else {
            println("[ComputerBlockEntity] Server: Loaded - isRunning=$isRunning, vncPort=$vncPort")
        }
        
        // If port changed on client, try to reconnect
        if (level?.isClientSide == true && vncPort != oldPort && vncPort > 0) {
            println("[ComputerBlockEntity] Client: Port changed from $oldPort to $vncPort, reconnecting...")
            vncClient?.disconnect()
            vncClient = null
            inputHandler = null
        }
        
        if (isRunning && level != null && !level!!.isClientSide) {
            println("[ComputerBlockEntity] Server: Load detected - isRunning=$isRunning, vncPort=$vncPort")
            // Always verify VM is actually running when loading
            val vmManager = vmManager
            val isActuallyRunning = vmManager != null && vmManager.isVMRunning(blockPos)
            
            if (!isActuallyRunning) {
                // VM process is not running, restart it
                println("[ComputerBlockEntity] Server: VM was marked as running but process is not alive, restarting...")
                isRunning = false
                vncPort = 0
                startVM()
            } else if (vncPort == 0) {
                // VM process is running but port is 0, something is wrong - restart
                println("[ComputerBlockEntity] Server: VM process is running but port is 0, restarting...")
                vmManager?.stopVM(blockPos)
                isRunning = false
                startVM()
            } else {
                println("[ComputerBlockEntity] Server: VM process is running, VNC should be available on port $vncPort")
            }
        }
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }

    override fun getUpdateTag(): CompoundTag {
        return saveWithoutMetadata()
    }
}

