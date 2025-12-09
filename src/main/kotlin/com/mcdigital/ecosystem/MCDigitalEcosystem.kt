package com.mcdigital.ecosystem

import com.mcdigital.ecosystem.block.ComputerBlock
import com.mcdigital.ecosystem.blockentity.ComputerBlockEntity
import com.mcdigital.ecosystem.item.ComputerBlockItem
import com.mcdigital.ecosystem.renderer.ComputerBlockRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject
import thedarkcolour.kotlinforforge.KotlinModLoadingContext

@Mod(MCDigitalEcosystem.MOD_ID)
object MCDigitalEcosystem {
    const val MOD_ID = "mcdigitalecosystem"

    private val BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID)
    private val ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID)
    private val BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID)
    private val CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID)

    val COMPUTER_BLOCK: RegistryObject<ComputerBlock> = BLOCKS.register("computer_block") { ComputerBlock() }
    val COMPUTER_BLOCK_ITEM: RegistryObject<Item> = ITEMS.register("computer_block") {
        ComputerBlockItem(COMPUTER_BLOCK.get(), Item.Properties())
    }
    val COMPUTER_BLOCK_ENTITY: RegistryObject<BlockEntityType<ComputerBlockEntity>> = BLOCK_ENTITIES.register("computer_block") {
        BlockEntityType.Builder.of({ pos, state -> ComputerBlockEntity(pos, state) }, COMPUTER_BLOCK.get()).build(null)
    }
    val CREATIVE_TAB: RegistryObject<CreativeModeTab> = CREATIVE_TABS.register("main") {
        CreativeModeTab.builder()
            .icon { ItemStack(COMPUTER_BLOCK.get()) }
            .title(net.minecraft.network.chat.Component.translatable("itemGroup.${MOD_ID}.main"))
            .displayItems { _, output ->
                output.accept(COMPUTER_BLOCK_ITEM.get())
            }
            .build()
    }

    init {
        val modEventBus = KotlinModLoadingContext.get().getKEventBus()
        
        BLOCKS.register(modEventBus)
        ITEMS.register(modEventBus)
        BLOCK_ENTITIES.register(modEventBus)
        CREATIVE_TABS.register(modEventBus)
    }
    
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
    object ClientModEvents {
        @SubscribeEvent
        fun onClientSetup(event: FMLClientSetupEvent) {
            BlockEntityRenderers.register(COMPUTER_BLOCK_ENTITY.get()) { ComputerBlockRenderer(it) }
        }
    }
}

