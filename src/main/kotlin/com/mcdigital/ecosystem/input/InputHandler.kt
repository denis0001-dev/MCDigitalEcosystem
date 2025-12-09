package com.mcdigital.ecosystem.input

import com.mcdigital.ecosystem.spice.SpiceClient
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import org.lwjgl.glfw.GLFW

class InputHandler(private val spiceClient: SpiceClient) {
    private var isCapturing = false
    private val keyStates = mutableMapOf<Int, Boolean>()
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0

    fun startCapturing() {
        isCapturing = true
        keyStates.clear()
    }

    fun stopCapturing() {
        isCapturing = false
        // Release all pressed keys
        keyStates.forEach { (key, _) ->
            if (key != -1) {
                spiceClient.sendKeyEvent(key, false)
            }
        }
        keyStates.clear()
    }

    fun tick() {
        if (!isCapturing) return
        
        val minecraft = Minecraft.getInstance()
        val window = minecraft.window
        
        // Handle keyboard
        handleKeyboard()
        
        // Handle mouse
        handleMouse(window)
    }

    private fun handleKeyboard() {
        val window = Minecraft.getInstance().window.window
        val keyMap = getKeyMap()
        
        keyMap.forEach { (mcKey, spiceKey) ->
            val isPressed = GLFW.glfwGetKey(window, mcKey) == GLFW.GLFW_PRESS
            val wasPressed = keyStates[spiceKey] ?: false
            
            if (isPressed != wasPressed) {
                spiceClient.sendKeyEvent(spiceKey, isPressed)
                keyStates[spiceKey] = isPressed
            }
        }
    }

    private fun handleMouse(window: com.mojang.blaze3d.platform.Window) {
        val mouseX = window.guiScaledWidth / 2.0
        val mouseY = window.guiScaledHeight / 2.0
        
        if (mouseX != lastMouseX || mouseY != lastMouseY) {
            // Convert to relative movement
            val deltaX = (mouseX - lastMouseX).toInt()
            val deltaY = (mouseY - lastMouseY).toInt()
            
            if (deltaX != 0 || deltaY != 0) {
                spiceClient.sendMouseMove(deltaX, deltaY)
            }
            
            lastMouseX = mouseX
            lastMouseY = mouseY
        }
        
        // Handle mouse buttons
        val windowHandle = window.window
        val leftButton = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        val rightButton = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS
        val middleButton = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS
        
        handleMouseButton(0, leftButton)
        handleMouseButton(1, rightButton)
        handleMouseButton(2, middleButton)
    }

    private fun handleMouseButton(button: Int, pressed: Boolean) {
        val key = -button - 1 // Use negative values for mouse buttons
        val wasPressed = keyStates[key] ?: false
        
        if (pressed != wasPressed) {
            spiceClient.sendMouseButton(button, pressed)
            keyStates[key] = pressed
        }
    }

    private fun getKeyMap(): Map<Int, Int> {
        // Map Minecraft/GLFW key codes to SPICE key codes
        // This is a simplified mapping - full implementation would map all keys
        return mapOf(
            GLFW.GLFW_KEY_A to 0x1E, // A
            GLFW.GLFW_KEY_B to 0x30, // B
            GLFW.GLFW_KEY_C to 0x2E, // C
            GLFW.GLFW_KEY_D to 0x20, // D
            GLFW.GLFW_KEY_E to 0x12, // E
            GLFW.GLFW_KEY_F to 0x21, // F
            GLFW.GLFW_KEY_G to 0x22, // G
            GLFW.GLFW_KEY_H to 0x23, // H
            GLFW.GLFW_KEY_I to 0x17, // I
            GLFW.GLFW_KEY_J to 0x24, // J
            GLFW.GLFW_KEY_K to 0x25, // K
            GLFW.GLFW_KEY_L to 0x26, // L
            GLFW.GLFW_KEY_M to 0x32, // M
            GLFW.GLFW_KEY_N to 0x31, // N
            GLFW.GLFW_KEY_O to 0x18, // O
            GLFW.GLFW_KEY_P to 0x19, // P
            GLFW.GLFW_KEY_Q to 0x10, // Q
            GLFW.GLFW_KEY_R to 0x13, // R
            GLFW.GLFW_KEY_S to 0x1F, // S
            GLFW.GLFW_KEY_T to 0x14, // T
            GLFW.GLFW_KEY_U to 0x16, // U
            GLFW.GLFW_KEY_V to 0x2F, // V
            GLFW.GLFW_KEY_W to 0x11, // W
            GLFW.GLFW_KEY_X to 0x2D, // X
            GLFW.GLFW_KEY_Y to 0x15, // Y
            GLFW.GLFW_KEY_Z to 0x2C, // Z
            GLFW.GLFW_KEY_SPACE to 0x39, // Space
            GLFW.GLFW_KEY_ENTER to 0x1C, // Enter
            GLFW.GLFW_KEY_ESCAPE to 0x01, // Escape
            GLFW.GLFW_KEY_BACKSPACE to 0x0E, // Backspace
            GLFW.GLFW_KEY_TAB to 0x0F, // Tab
            GLFW.GLFW_KEY_LEFT_SHIFT to 0x2A, // Left Shift
            GLFW.GLFW_KEY_RIGHT_SHIFT to 0x36, // Right Shift
            GLFW.GLFW_KEY_LEFT_CONTROL to 0x1D, // Left Control
            GLFW.GLFW_KEY_RIGHT_CONTROL to 0x1D, // Right Control
            GLFW.GLFW_KEY_LEFT_ALT to 0x38, // Left Alt
            GLFW.GLFW_KEY_RIGHT_ALT to 0x38, // Right Alt
        )
    }
}

