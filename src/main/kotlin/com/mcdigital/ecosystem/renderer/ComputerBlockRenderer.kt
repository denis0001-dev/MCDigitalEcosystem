package com.mcdigital.ecosystem.renderer

import com.mcdigital.ecosystem.blockentity.ComputerBlockEntity
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap

class ScreenTexture(private val blockEntity: ComputerBlockEntity) {
    private var texture: DynamicTexture? = null
    private var textureLocation: ResourceLocation? = null
    private var lastUpdate: Long = 0
    private val targetFrameTime = 16L // ~60 FPS
    
    fun updateFrame(frame: BufferedImage) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < targetFrameTime) {
            return
        }
        lastUpdate = now
        
        if (texture == null) {
            val nativeImage = NativeImage(frame.width, frame.height, false)
            texture = DynamicTexture(nativeImage)
            textureLocation = ResourceLocation("mcdigitalecosystem", "dynamic/screen_${blockEntity.blockPos.asLong()}")
            net.minecraft.client.Minecraft.getInstance().textureManager.register(textureLocation!!, texture!!)
        }
        
        val nativeImage = texture?.pixels
        if (nativeImage != null) {
            for (y in 0 until frame.height) {
                for (x in 0 until frame.width) {
                    val rgb = frame.getRGB(x, y)
                    nativeImage.setPixelRGBA(x, y, rgb)
                }
            }
            texture?.upload()
        }
    }
    
    fun getTextureLocation(): ResourceLocation? = textureLocation
}

object ComputerBlockRendererHelper {
    private val screenTextures = ConcurrentHashMap<ComputerBlockEntity, ScreenTexture>()
    
    fun updateFrameForBlock(blockEntity: ComputerBlockEntity, frame: BufferedImage) {
        val screenTexture = screenTextures.getOrPut(blockEntity) { 
            ScreenTexture(blockEntity)
        }
        screenTexture.updateFrame(frame)
    }
    
    fun getScreenTexture(blockEntity: ComputerBlockEntity): ScreenTexture? {
        return screenTextures[blockEntity]
    }
}

class ComputerBlockRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<ComputerBlockEntity> {
    
    override fun render(
        blockEntity: ComputerBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val screenTexture = ComputerBlockRendererHelper.getScreenTexture(blockEntity)
        val textureLocation = screenTexture?.getTextureLocation() 
            ?: ResourceLocation("mcdigitalecosystem", "textures/block/computer_screen")
        
        poseStack.pushPose()
        
        val state = blockEntity.blockState
        val facing = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING) 
            ?: Direction.NORTH
        
        // Translate to center of block
        poseStack.translate(0.5, 0.5, 0.5)
        
        // Rotate to face the correct direction
        when (facing) {
            Direction.NORTH -> {
                // Default orientation
            }
            Direction.SOUTH -> {
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180f))
            }
            Direction.WEST -> {
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90f))
            }
            Direction.EAST -> {
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-90f))
            }
            else -> {}
        }
        
        // Translate back and position for front face
        poseStack.translate(-0.5, -0.5, -0.5)
        
        // Render front face with screen texture
        renderScreenFace(poseStack, buffer, textureLocation, packedLight, packedOverlay)
        
        poseStack.popPose()
    }
    
    private fun renderScreenFace(
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        textureLocation: ResourceLocation,
        packedLight: Int,
        packedOverlay: Int
    ) {
        RenderSystem.setShader { GameRenderer.getPositionTexShader() }
        RenderSystem.setShaderTexture(0, textureLocation)
        
        val matrix = poseStack.last().pose()
        val normal = poseStack.last().normal()
        
        // Front face (Z = 0.01, slightly in front of block)
        val z = 0.501f
        
        val consumer = buffer.getBuffer(RenderType.entityCutout(textureLocation))
        
        // Render quad for screen - stretched to fill front face
        addVertex(consumer, matrix, normal, 0.0f, 0.0f, z, 0f, 0f, packedLight, packedOverlay)
        addVertex(consumer, matrix, normal, 1.0f, 0.0f, z, 1f, 0f, packedLight, packedOverlay)
        addVertex(consumer, matrix, normal, 1.0f, 1.0f, z, 1f, 1f, packedLight, packedOverlay)
        addVertex(consumer, matrix, normal, 0.0f, 1.0f, z, 0f, 1f, packedLight, packedOverlay)
    }
    
    private fun addVertex(
        consumer: VertexConsumer,
        matrix: Matrix4f,
        normal: org.joml.Matrix3f,
        x: Float,
        y: Float,
        z: Float,
        u: Float,
        v: Float,
        packedLight: Int,
        packedOverlay: Int
    ) {
        consumer.vertex(matrix, x, y, z)
            .color(255, 255, 255, 255)
            .uv(u, v)
            .overlayCoords(packedOverlay)
            .uv2(packedLight)
            .normal(normal, 0f, 0f, 1f)
            .endVertex()
    }
    
    override fun shouldRenderOffScreen(blockEntity: ComputerBlockEntity): Boolean {
        return true
    }
}
