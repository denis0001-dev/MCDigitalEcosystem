package com.mcdigital.ecosystem.blockentity

import com.mcdigital.ecosystem.MCDigitalEcosystem
import com.mcdigital.ecosystem.input.InputHandler
import com.mcdigital.ecosystem.persistence.VMStateManager
import com.mcdigital.ecosystem.spice.SpiceClient
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
    private var spiceClient: SpiceClient? = null
    private var inputHandler: InputHandler? = null
    private var stateManager: VMStateManager? = null
    private var focusedPlayer: Player? = null
    private var isRunning = false
    private var spicePort = 0

    init {
        vmManager = VMManager()
        stateManager = VMStateManager(level?.dimension()?.location()?.toString() ?: "unknown", blockPos)
    }

    fun tick() {
        if (level?.isClientSide == true) {
            spiceClient?.update()
            inputHandler?.tick()
        }
    }

    fun onInteract(player: Player) {
        if (level?.isClientSide == true) {
            if (focusedPlayer == null || focusedPlayer == player) {
                focusedPlayer = player
                if (spiceClient == null && spicePort > 0) {
                    spiceClient = SpiceClient("127.0.0.1", spicePort) { frame ->
                        // Frame update callback - will be handled by renderer
                        com.mcdigital.ecosystem.renderer.ComputerBlockRendererHelper.updateFrameForBlock(this, frame)
                    }
                    inputHandler = InputHandler(spiceClient!!)
                    inputHandler?.startCapturing()
                }
            }
        } else {
            // Server side - start VM if not running
            if (!isRunning) {
                startVM()
            }
        }
    }

    fun onRemoved() {
        if (isRunning) {
            stopVM()
        }
        spiceClient?.disconnect()
        inputHandler = null
        focusedPlayer = null
    }

    private fun startVM() {
        if (vmManager == null || stateManager == null) return
        
        val stateName = stateManager!!.getStateName()
        spicePort = vmManager!!.startVM(
            blockPos = blockPos,
            stateName = if (stateName.isNotEmpty()) stateName else null
        )
        isRunning = true
        setChanged()
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

    fun getSpicePort(): Int = spicePort
    fun isVMRunning(): Boolean = isRunning
    fun getFocusedPlayer(): Player? = focusedPlayer

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putBoolean("isRunning", isRunning)
        tag.putInt("spicePort", spicePort)
        
        // Save VM state when block entity is saved
        if (isRunning) {
            saveVMState()
        }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        isRunning = tag.getBoolean("isRunning")
        spicePort = tag.getInt("spicePort")
        
        if (isRunning && level != null && !level!!.isClientSide) {
            // Restore VM state on load
            startVM()
        }
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }

    override fun getUpdateTag(): CompoundTag {
        return saveWithoutMetadata()
    }
}

