package com.mcdigital.ecosystem.block

import com.mcdigital.ecosystem.blockentity.ComputerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.phys.BlockHitResult

class ComputerBlock : BaseEntityBlock(Properties.of().strength(2.0f).noOcclusion()) {
    companion object {
        val FACING: DirectionProperty = BlockStateProperties.HORIZONTAL_FACING
    }
    
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }
    
    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }
    
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        return defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)
    }
    
    override fun rotate(state: BlockState, rotation: Rotation): BlockState {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)))
    }
    
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? {
        return ComputerBlockEntity(pos, state)
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>
    ): BlockEntityTicker<T>? {
        return createTickerHelper(blockEntityType, com.mcdigital.ecosystem.MCDigitalEcosystem.COMPUTER_BLOCK_ENTITY.get()) { level, pos, state, blockEntity ->
            if (blockEntity is ComputerBlockEntity) {
                blockEntity.tick()
            }
        }
    }

    override fun getRenderShape(state: BlockState): RenderShape {
        return RenderShape.ENTITYBLOCK_ANIMATED
    }

    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult
    ): InteractionResult {
        if (!level.isClientSide) {
            val blockEntity = level.getBlockEntity(pos)
            if (blockEntity is ComputerBlockEntity) {
                blockEntity.onInteract(player)
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    override fun onRemove(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        newState: BlockState,
        isMoving: Boolean
    ) {
        if (!state.`is`(newState.block)) {
            val blockEntity = level.getBlockEntity(pos)
            if (blockEntity is ComputerBlockEntity) {
                blockEntity.onRemoved()
            }
            super.onRemove(state, level, pos, newState, isMoving)
        }
    }
}

