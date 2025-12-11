package com.mcdigital.ecosystem.renderer

import com.mcdigital.ecosystem.blockentity.ComputerBlockEntity
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.client.model.data.ModelData
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
            // Create a default texture - use a proper size (power of 2) and fully opaque black
            // This prevents green particles (missing texture indicator)
            val width = 256
            val height = 256
            val nativeImage = NativeImage(width, height, false) // false = no alpha channel needed
            
            // Fill with fully opaque black (no transparency)
            val placeholderColor = 0xFF000000.toInt() // Fully opaque black
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

class ComputerBlockRenderer() : BlockEntityRenderer<ComputerBlockEntity> {
    override fun render(
        blockEntity: ComputerBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val minecraft = Minecraft.getInstance()
        val blockRenderer = minecraft.blockRenderer
        val state = blockEntity.blockState
        
        poseStack.pushPose()
        
        // Get facing direction
        val facing = if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
        } else {
            Direction.NORTH
        }
        
        // Rotate the model to match the block's facing direction
        // When using RenderShape.ENTITYBLOCK_ANIMATED, we need to manually apply rotations
        // The blockstate defines rotations, but BlockModelShaper.stateToModelLocation
        // already selects the correct variant, so we might not need to rotate manually
        
        // However, since we're using BlockModelRenderer directly, we need to apply rotation
        // Center the model for rotation
        poseStack.translate(0.5, 0.0, 0.5)
        
        // Calculate rotation based on facing direction (matching blockstate)
        // Add 180 degrees offset if the model's default orientation is backwards
        val rotationY = when (facing) {
            Direction.NORTH -> 180f  // 0 + 180
            Direction.SOUTH -> 0f    // 180 + 180 = 360 = 0
            Direction.WEST -> 270f  // 90 + 180 = 270
            Direction.EAST -> 90f   // 270 + 180 = 450 = 90
            else -> 180f
        }
        
        // Apply rotation inverted (clockwise instead of counter-clockwise)
        poseStack.mulPose(Axis.YP.rotationDegrees(-rotationY))
        
        poseStack.translate(-0.5, 0.0, -0.5)
        
        // Render the block model using BlockModelRenderer directly
        // This is the lower-level method that renders model quads
        val level = blockEntity.level
        if (level != null) {
            val modelManager = minecraft.modelManager
            // Use the base model (north-facing variant) and apply rotation manually
            // This ensures we have full control over the rotation
            val baseState = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
            val modelLocation = net.minecraft.client.renderer.block.BlockModelShaper.stateToModelLocation(baseState)
            val model = modelManager.getModel(modelLocation)
            val modelData = ModelData.EMPTY
            val random = net.minecraft.util.RandomSource.create()
            val modelRenderer = minecraft.blockRenderer.modelRenderer
            
            // Get all render types for this model
            val renderTypes = model.getRenderTypes(state, random, modelData)
            
            // Render with each render type using BlockModelRenderer
            // Render in order: solid first, then cutout, then translucent
            val sortedRenderTypes = renderTypes.sortedBy { renderType ->
                when {
                    renderType == RenderType.solid() -> 0
                    renderType == RenderType.cutout() -> 1
                    renderType == RenderType.translucent() -> 2
                    else -> 3
                }
            }
            
            for (renderType in sortedRenderTypes) {
                val vertexConsumer = buffer.getBuffer(renderType)
                modelRenderer.renderModel(
                    poseStack.last(),
                    vertexConsumer,
                    state,
                    model,
                    1.0f, 1.0f, 1.0f, // Red, Green, Blue
                    packedLight,
                    packedOverlay,
                    modelData,
                    renderType
                )
            }
            
            // If no render types, render with solid as fallback
            if (renderTypes.isEmpty()) {
                val vertexConsumer = buffer.getBuffer(RenderType.solid())
                modelRenderer.renderModel(
                    poseStack.last(),
                    vertexConsumer,
                    state,
                    model,
                    1.0f, 1.0f, 1.0f,
                    packedLight,
                    packedOverlay,
                    modelData,
                    RenderType.solid()
                )
            }
        }
        
        // Now overlay the screen texture
        // Note: The pose stack is already rotated, so we render the screen at a fixed position
        // in model space (on the front face, which is NORTH in model coordinates)
        val screenTexture = ComputerBlockRendererHelper.getScreenTexture(blockEntity)
            ?: ComputerBlockRendererHelper.getOrCreateDefaultTexture(blockEntity)

        val textureLocation = screenTexture.getTextureLocation()
        if (textureLocation != null) {
            // Render screen overlay at fixed position (front face in model space)
            // The rotation already applied to poseStack will handle orientation
            // Since the pose stack is rotated, we use fixed model-space coordinates
            renderScreenOverlay(poseStack, buffer, textureLocation, packedLight, packedOverlay)
        }
        
        poseStack.popPose()
    }
    
    private fun renderScreenOverlay(
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
        
        // Enable proper depth testing and blending for the screen overlay
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true) // Write to depth buffer to prevent z-fighting
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc() // Standard alpha blending
        RenderSystem.disableCull() // Render both sides to prevent culling issues
        
        // Monitor screen position based on the model (fixed coordinates in model space):
        // X: 0.5 to 15.5 (full width with small margin)
        // Y: 3.3 to 10.7 (between top and bottom frames)
        // Z: 3.25 (slightly forward of the black frame at 3.2/16)
        // Since the pose stack is already rotated, we use fixed model-space coordinates
        val screenXMin = 0.5f / 16.0f  // 0.03125
        val screenXMax = 15.5f / 16.0f // 0.96875
        val screenYMin = 3.3f / 16.0f  // 0.20625
        val screenYMax = 10.7f / 16.0f // 0.66875
        val screenZ = 3.25f / 16.0f    // 0.203125 (slightly forward of frame at 3.2/16)
        
        // Use translucent render type for proper transparency handling
        val consumer = buffer.getBuffer(RenderType.entityTranslucent(textureLocation))
        val matrix = poseStack.last().pose()
        val normal = poseStack.last().normal()
        
        // Render quad for screen - positioned to match the monitor screen area
        // The pose stack is already rotated, so we use fixed model-space coordinates
        // UV coordinates: (0,1) bottom-left, (1,1) bottom-right, (1,0) top-right, (0,0) top-left
        // Always render on the "front" face in model local coordinates (positive Z)
        addVertex(consumer, matrix, normal, screenXMin, screenYMin, screenZ, 0f, 1f, packedLight, packedOverlay)
        addVertex(consumer, matrix, normal, screenXMax, screenYMin, screenZ, 1f, 1f, packedLight, packedOverlay)
        addVertex(consumer, matrix, normal, screenXMax, screenYMax, screenZ, 1f, 0f, packedLight, packedOverlay)
        addVertex(consumer, matrix, normal, screenXMin, screenYMax, screenZ, 0f, 0f, packedLight, packedOverlay)
        
        // Restore render state
        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
    }
    
    @Suppress("SameParameterValue")
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
