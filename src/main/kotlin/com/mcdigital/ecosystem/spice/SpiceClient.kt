package com.mcdigital.ecosystem.spice

import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

typealias FrameCallback = (BufferedImage) -> Unit

class SpiceClient(private val host: String, private val port: Int, private val onFrame: FrameCallback) {
    private var connected = AtomicBoolean(false)
    private var nativeHandle: Long = 0
    private val frameLock = ReentrantLock()
    private var currentFrame: BufferedImage? = null
    private val lastFrameTime = AtomicLong(0)
    private val targetFrameTime = 1000000000L / 60 // 60 FPS in nanoseconds

    init {
        connect()
    }

    private fun connect() {
        try {
            // Connect to SPICE server
            nativeHandle = SpiceClientJNI.spiceConnect(host, port)
            
            if (nativeHandle != 0L) {
                // Set up frame callback
                SpiceClientJNI.spiceSetFrameCallback(nativeHandle, object : SpiceClientJNI.FrameCallback {
                    override fun onFrame(frame: BufferedImage) {
                        updateFrame(frame)
                    }
                })
                connected.set(true)
            } else {
                throw RuntimeException("Failed to connect to SPICE server")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            connected.set(false)
        }
    }

    fun update() {
        if (!connected.get() || nativeHandle == 0L) return
        
        // Update native SPICE client (processes incoming messages)
        SpiceClientJNI.spiceUpdate(nativeHandle)
        
        val now = System.nanoTime()
        val lastTime = lastFrameTime.get()
        
        // Limit to 60 FPS
        if (now - lastTime < targetFrameTime) {
            return
        }
        
        frameLock.withLock {
            currentFrame?.let { frame ->
                if (lastFrameTime.compareAndSet(lastTime, now)) {
                    onFrame(frame)
                }
            }
        }
    }

    private fun updateFrame(frame: BufferedImage) {
        frameLock.withLock {
            currentFrame = frame
        }
    }

    fun sendKeyEvent(keyCode: Int, pressed: Boolean) {
        if (!connected.get() || nativeHandle == 0L) return
        SpiceClientJNI.spiceSendKey(nativeHandle, keyCode, pressed)
    }

    fun sendMouseMove(x: Int, y: Int) {
        if (!connected.get() || nativeHandle == 0L) return
        SpiceClientJNI.spiceSendMouseMove(nativeHandle, x, y)
    }

    fun sendMouseButton(button: Int, pressed: Boolean) {
        if (!connected.get() || nativeHandle == 0L) return
        SpiceClientJNI.spiceSendMouseButton(nativeHandle, button, pressed)
    }

    fun disconnect() {
        if (nativeHandle != 0L) {
            SpiceClientJNI.spiceSetFrameCallback(nativeHandle, null)
            SpiceClientJNI.spiceDisconnect(nativeHandle)
            nativeHandle = 0
        }
        connected.set(false)
    }

    fun isConnected(): Boolean = connected.get() && nativeHandle != 0L
    
    fun getWidth(): Int {
        return if (nativeHandle != 0L) SpiceClientJNI.spiceGetWidth(nativeHandle) else 0
    }
    
    fun getHeight(): Int {
        return if (nativeHandle != 0L) SpiceClientJNI.spiceGetHeight(nativeHandle) else 0
    }
}
