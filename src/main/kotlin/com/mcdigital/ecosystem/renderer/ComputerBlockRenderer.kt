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
    private var initialized = false
    
    fun initializeIfNeeded() {
        if (!initialized && texture == null) {
            // Create a default texture with a visible placeholder (black with slight transparency)
            // This ensures something is visible even before frames arrive
            val width = 64
            val height = 64
            val nativeImage = NativeImage(width, height, true) // true = use alpha channel
            
            // Fill with a dark gray color (slightly visible) so we know the texture is working
            val placeholderColor = 0x80000000.toInt() // Dark gray with 50% opacity
            for (y in 0 until height) {
                for (x in 0 until width) {
                    nativeImage.setPixelRGBA(x, y, placeholderColor)
                }
            }
            
            texture = DynamicTexture(nativeImage)
            textureLocation = ResourceLocation("mcdigitalecosystem", "dynamic/screen_${blockEntity.blockPos.asLong()}")
            net.minecraft.client.Minecraft.getInstance().textureManager.register(textureLocation!!, texture!!)
            texture!!.upload() // Upload immediately so it's available
            initialized = true
            println("[ScreenTexture] Initialized placeholder texture: ${width}x${height}")
        }
    }
    
    fun updateFrame(frame: BufferedImage) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < targetFrameTime) {
            return
        }
        lastUpdate = now
        
        // Ensure texture is initialized
        if (texture == null || texture?.pixels == null) {
            val nativeImage = NativeImage(frame.width, frame.height, false)
            texture = DynamicTexture(nativeImage)
            textureLocation = ResourceLocation("mcdigitalecosystem", "dynamic/screen_${blockEntity.blockPos.asLong()}")
            net.minecraft.client.Minecraft.getInstance().textureManager.register(textureLocation!!, texture!!)
            texture!!.upload() // Upload immediately so transparent texture is available
            initialized = true
            println("[ScreenTexture] Initialized transparent base texture")
            println("[ScreenTexture] Created texture: ${frame.width}x${frame.height}")
        }
        
        val nativeImage = texture?.pixels
        if (nativeImage != null) {
            // Resize if needed
            if (nativeImage.width != frame.width || nativeImage.height != frame.height) {
                println("[ScreenTexture] Resizing texture from ${nativeImage.width}x${nativeImage.height} to ${frame.width}x${frame.height}")
                val newImage = NativeImage(frame.width, frame.height, false)
                texture = DynamicTexture(newImage)
                net.minecraft.client.Minecraft.getInstance().textureManager.register(textureLocation!!, texture!!)
                // Continue with new image
                val updatedImage = texture?.pixels ?: return
                for (y in 0 until frame.height.coerceAtMost(updatedImage.height)) {
                    for (x in 0 until frame.width.coerceAtMost(updatedImage.width)) {
                        val rgb = frame.getRGB(x, y)
                        // Convert RGB to RGBA (add alpha = 255)
                        val rgba = (rgb and 0x00FFFFFF) or 0xFF000000.toInt()
                        updatedImage.setPixelRGBA(x, y, rgba)
                    }
                }
                texture?.upload()
            } else {
                // Same size, just update pixels
                for (y in 0 until frame.height.coerceAtMost(nativeImage.height)) {
                    for (x in 0 until frame.width.coerceAtMost(nativeImage.width)) {
                        val rgb = frame.getRGB(x, y)
                        // Convert RGB to RGBA (add alpha = 255)
                        val rgba = (rgb and 0x00FFFFFF) or 0xFF000000.toInt()
                        nativeImage.setPixelRGBA(x, y, rgba)
                    }
                }
                texture?.upload()
            }
        }
    }
    
    fun getTextureLocation(): ResourceLocation? {
        if (!initialized || texture == null) {
            initializeIfNeeded()
        }
        // Always return a valid texture location (never null)
        if (textureLocation == null) {
            // Fallback: create a minimal texture
            initializeIfNeeded()
        }
        return textureLocation
    }
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
    
    fun getOrCreateDefaultTexture(blockEntity: ComputerBlockEntity): ScreenTexture {
        return screenTextures.getOrPut(blockEntity) {
            ScreenTexture(blockEntity).apply {
                initializeIfNeeded()
            }
        }
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
        // Always ensure we have a texture
        val screenTexture = ComputerBlockRendererHelper.getScreenTexture(blockEntity)
            ?: ComputerBlockRendererHelper.getOrCreateDefaultTexture(blockEntity)
        
        val textureLocation = screenTexture.getTextureLocation()
        if (textureLocation == null) {
            // Last resort: skip rendering this frame
            return
        }
        
        poseStack.pushPose()
        
        val state = blockEntity.blockState
        val facing = if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
        } else {
            Direction.NORTH
        }
        
        // Translate to center of block
        poseStack.translate(0.5, 0.5, 0.5)
        
        // Rotate to face the correct direction
        when (facing) {
            Direction.NORTH -> {
                // Default orientation - screen faces north (negative Z)
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
        
        // Bind the texture - this will load it if not already loaded
        try {
            RenderSystem.setShaderTexture(0, textureLocation)
        } catch (e: Exception) {
            // Texture not registered yet, skip rendering this frame
            System.err.println("[ComputerBlockRenderer] Texture $textureLocation not available: ${e.message}")
            return
        }
        
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(false) // Allow transparency
        
        val matrix = poseStack.last().pose()
        val normal = poseStack.last().normal()
        
        // Front face - render slightly in front of block face (z = 0.501)
        val z = 0.501f
        
        // Use translucent render type for proper transparency handling
        val consumer = buffer.getBuffer(RenderType.entityTranslucent(textureLocation))
        
        // Render quad for screen - stretched to fill front face
        // Note: UV coordinates are flipped (v: 1f, 0f) to match Minecraft's texture coordinate system
        addVertex(consumer, matrix, normal, 0.0f, 0.0f, z, 0f, 1f, packedLight, packedOverlay)
        addVertex(consumer, matrix, normal, 1.0f, 0.0f, z, 1f, 1f, packedLight, packedOverlay)
        addVertex(consumer, matrix, normal, 1.0f, 1.0f, z, 1f, 0f, packedLight, packedOverlay)
        addVertex(consumer, matrix, normal, 0.0f, 1.0f, z, 0f, 0f, packedLight, packedOverlay)
        
        RenderSystem.depthMask(true) // Restore depth mask
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
