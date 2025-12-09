package com.mcdigital.ecosystem.vnc

import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

typealias FrameCallback = (BufferedImage) -> Unit

/**
 * Simple VNC client implementation using RFB protocol
 * This is a minimal implementation that connects to QEMU's VNC server
 */
class VNCClientWrapper(private val host: String, private val port: Int, private val onFrame: FrameCallback) {
    private var connected = AtomicBoolean(false)
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val frameLock = ReentrantLock()
    private var currentFrame: BufferedImage? = null
    private val lastFrameTime = AtomicLong(0)
    private val targetFrameTime = 1000000000L / 60 // 60 FPS in nanoseconds
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameThread: Thread? = null
    private var fullFrame: BufferedImage? = null

    init {
        connect()
    }

    private fun connect() {
        try {
            println("[VNCClient] Attempting to connect to VNC server at $host:$port")
            
            socket = Socket(host, port)
            input = DataInputStream(socket!!.getInputStream())
            output = DataOutputStream(socket!!.getOutputStream())
            
            // Read VNC protocol version
            val version = ByteArray(12)
            input!!.readFully(version)
            val versionString = String(version)
            println("[VNCClient] Server version: $versionString")
            
            // Send our version (we support 3.8)
            output!!.write("RFB 003.008\n".toByteArray())
            output!!.flush()
            
            // Read security types
            val numSecurityTypes = input!!.readUnsignedByte()
            if (numSecurityTypes == 0) {
                val reasonLength = input!!.readInt()
                val reason = ByteArray(reasonLength)
                input!!.readFully(reason)
                throw RuntimeException("VNC server rejected connection: ${String(reason)}")
            }
            
            val securityTypes = ByteArray(numSecurityTypes)
            input!!.readFully(securityTypes)
            
            // Use None security (type 1) if available, otherwise use first available
            var securityType = 1.toByte() // None
            if (!securityTypes.contains(securityType)) {
                securityType = securityTypes[0]
            }
            
            // Send selected security type
            output!!.writeByte(securityType.toInt())
            output!!.flush()
            
            // Read security result
            val securityResult = input!!.readInt()
            if (securityResult != 0) {
                val reasonLength = input!!.readInt()
                val reason = ByteArray(reasonLength)
                input!!.readFully(reason)
                throw RuntimeException("VNC security failed: ${String(reason)}")
            }
            
            // Send client initialization
            output!!.writeBoolean(true) // Share desktop
            output!!.flush()
            
            // Read server initialization
            frameWidth = input!!.readUnsignedShort()
            frameHeight = input!!.readUnsignedShort()
            val pixelFormat = ByteArray(16)
            input!!.readFully(pixelFormat)
            val nameLength = input!!.readInt()
            val name = ByteArray(nameLength)
            input!!.readFully(name)
            
            println("[VNCClient] Connected successfully. Screen: ${frameWidth}x${frameHeight}, name: ${String(name)}")
            
            // Parse server pixel format
            val bitsPerPixel = pixelFormat[0].toInt() and 0xFF
            val depth = pixelFormat[1].toInt() and 0xFF
            val bigEndian = pixelFormat[2].toInt() != 0
            val trueColor = pixelFormat[3].toInt() != 0
            val redMax = ((pixelFormat[4].toInt() and 0xFF) shl 8) or (pixelFormat[5].toInt() and 0xFF)
            val greenMax = ((pixelFormat[6].toInt() and 0xFF) shl 8) or (pixelFormat[7].toInt() and 0xFF)
            val blueMax = ((pixelFormat[8].toInt() and 0xFF) shl 8) or (pixelFormat[9].toInt() and 0xFF)
            val redShift = pixelFormat[10].toInt() and 0xFF
            val greenShift = pixelFormat[11].toInt() and 0xFF
            val blueShift = pixelFormat[12].toInt() and 0xFF
            
            println("[VNCClient] Server pixel format: ${bitsPerPixel}bpp, depth=$depth, bigEndian=$bigEndian, trueColor=$trueColor")
            println("[VNCClient] Red: max=$redMax, shift=$redShift; Green: max=$greenMax, shift=$greenShift; Blue: max=$blueMax, shift=$blueShift")
            
            // Request our preferred pixel format (32-bit RGB, little-endian)
            output!!.writeByte(0) // SetPixelFormat
            output!!.writeByte(0) // padding
            output!!.writeByte(0) // padding
            output!!.writeByte(0) // padding
            output!!.writeByte(32) // bits-per-pixel
            output!!.writeByte(24) // depth
            output!!.writeByte(0) // big-endian (false = little-endian)
            output!!.writeByte(1) // true-color (true)
            output!!.writeShort(255) // red-max
            output!!.writeShort(255) // green-max
            output!!.writeShort(255) // blue-max
            output!!.writeByte(16) // red-shift
            output!!.writeByte(8) // green-shift
            output!!.writeByte(0) // blue-shift
            output!!.writeByte(0) // padding
            output!!.writeByte(0) // padding
            output!!.writeByte(0) // padding
            output!!.flush()
            
            // Enable continuous updates
            output!!.writeByte(3) // FramebufferUpdateRequest
            output!!.writeByte(1) // incremental = true (continuous updates)
            output!!.writeShort(0) // x
            output!!.writeShort(0) // y
            output!!.writeShort(frameWidth) // width
            output!!.writeShort(frameHeight) // height
            output!!.flush()
            
            // Request incremental updates
            requestUpdate()
            
            // Start frame reading thread
            frameThread = Thread {
                try {
                    while (connected.get() && socket != null && !socket!!.isClosed) {
                        readFrame()
                    }
                } catch (e: Exception) {
                    if (connected.get()) {
                        System.err.println("[VNCClient] Frame reading error: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
            frameThread!!.isDaemon = true
            frameThread!!.start()
            
            connected.set(true)
        } catch (e: Exception) {
            System.err.println("[VNCClient] Connection failed: ${e.message}")
            e.printStackTrace()
            disconnect()
            connected.set(false)
        }
    }
    
    private fun requestUpdate() {
        try {
            output?.writeByte(3) // FramebufferUpdateRequest
            output?.writeBoolean(false) // incremental
            output?.writeShort(0) // x
            output?.writeShort(0) // y
            output?.writeShort(frameWidth.toInt()) // width
            output?.writeShort(frameHeight.toInt()) // height
            output?.flush()
        } catch (e: Exception) {
            System.err.println("[VNCClient] Error requesting update: ${e.message}")
        }
    }
    
    private fun readFrame() {
        val input = this.input ?: return
        
        // Read server message type
        val messageType = input.readUnsignedByte()
        
        when (messageType) {
            0 -> { // FramebufferUpdate
                input.readByte() // padding
                val numRects = input.readUnsignedShort()
                
                for (i in 0 until numRects) {
                    val x = input.readUnsignedShort()
                    val y = input.readUnsignedShort()
                    val width = input.readUnsignedShort()
                    val height = input.readUnsignedShort()
                    val encoding = input.readInt()
                    
                    when (encoding) {
                        0 -> { // Raw encoding
                            val pixels = ByteArray(width * height * 4)
                            input.readFully(pixels)
                            
                            // Ensure we have a full frame buffer
                            if (fullFrame == null || fullFrame!!.width != frameWidth || fullFrame!!.height != frameHeight) {
                                fullFrame = BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_RGB)
                                println("[VNCClient] Created full frame buffer: ${frameWidth}x${frameHeight}")
                            }
                            
                            // Convert pixels (32-bit BGRA or RGBA depending on server)
                            // QEMU typically sends BGRA format
                            for (py in 0 until height) {
                                for (px in 0 until width) {
                                    val idx = (py * width + px) * 4
                                    val b = pixels[idx].toInt() and 0xFF
                                    val g = pixels[idx + 1].toInt() and 0xFF
                                    val r = pixels[idx + 2].toInt() and 0xFF
                                    // val a = pixels[idx + 3].toInt() and 0xFF // Alpha (not used for RGB)
                                    val rgb = (r shl 16) or (g shl 8) or b
                                    fullFrame!!.setRGB(x + px, y + py, rgb)
                                }
                            }
                            
                            // Update with the full frame
                            updateFrame(fullFrame!!)
                        }
                        1 -> { // CopyRect encoding
                            val srcX = input.readUnsignedShort()
                            val srcY = input.readUnsignedShort()
                            
                            if (fullFrame != null) {
                                // Copy rectangle from source to destination
                                val srcImage = fullFrame!!.getSubimage(srcX, srcY, width, height)
                                val g = fullFrame!!.graphics
                                g.drawImage(srcImage, x, y, null)
                                g.dispose()
                                updateFrame(fullFrame!!)
                            }
                        }
                        else -> {
                            // Skip unknown encodings - read and discard the data
                            System.err.println("[VNCClient] Unknown encoding: $encoding for rect ${width}x${height} at ($x, $y)")
                            // For raw-like encodings, skip the pixel data
                            // Note: This is a simplified approach - proper implementation would handle each encoding
                            try {
                                val bytesToSkip = width * height * 4
                                var skipped = 0
                                while (skipped < bytesToSkip) {
                                    val chunk = minOf(8192, bytesToSkip - skipped)
                                    val buffer = ByteArray(chunk)
                                    input.readFully(buffer)
                                    skipped += chunk
                                }
                            } catch (e: Exception) {
                                System.err.println("[VNCClient] Error skipping encoding $encoding: ${e.message}")
                                throw e
                            }
                        }
                    }
                }
                
                // Request next update
                requestUpdate()
            }
            else -> {
                System.err.println("[VNCClient] Unknown message type: $messageType")
            }
        }
    }

    fun update() {
        if (!connected.get()) return
        
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
            if (frame.width > 0 && frame.height > 0 && lastFrameTime.get() == 0L) {
                println("[VNCClient] First frame received: ${frame.width}x${frame.height}")
            }
        }
    }

    fun sendKeyEvent(keyCode: Int, pressed: Boolean) {
        if (!connected.get() || output == null) return
        try {
            output!!.writeByte(4) // KeyEvent
            output!!.writeBoolean(pressed)
            output!!.writeByte(0) // padding
            output!!.writeByte(0) // padding
            output!!.writeInt(keyCode)
            output!!.flush()
        } catch (e: Exception) {
            System.err.println("[VNCClient] Error sending key event: ${e.message}")
        }
    }

    fun sendMouseMove(x: Int, y: Int) {
        if (!connected.get() || output == null) return
        try {
            output!!.writeByte(5) // PointerEvent
            val buttons = 0.toByte()
            output!!.writeByte(buttons.toInt())
            output!!.writeShort(x)
            output!!.writeShort(y)
            output!!.flush()
        } catch (e: Exception) {
            System.err.println("[VNCClient] Error sending mouse move: ${e.message}")
        }
    }

    fun sendMouseButton(button: Int, pressed: Boolean) {
        if (!connected.get() || output == null) return
        try {
            output!!.writeByte(5) // PointerEvent
            val buttonMask = when (button) {
                0 -> if (pressed) 1 else 0 // Left
                1 -> if (pressed) 4 else 0 // Right
                2 -> if (pressed) 2 else 0 // Middle
                else -> 0
            }
            output!!.writeByte(buttonMask)
            // Keep current mouse position (we'd need to track this)
            output!!.writeShort(0)
            output!!.writeShort(0)
            output!!.flush()
        } catch (e: Exception) {
            System.err.println("[VNCClient] Error sending mouse button: ${e.message}")
        }
    }

    fun disconnect() {
        connected.set(false)
        try {
            frameThread?.interrupt()
            socket?.close()
        } catch (e: Exception) {
            System.err.println("[VNCClient] Error disconnecting: ${e.message}")
        }
        socket = null
        input = null
        output = null
    }

    fun isConnected(): Boolean = connected.get() && socket != null && !socket!!.isClosed
    
    fun getWidth(): Int = frameWidth
    
    fun getHeight(): Int = frameHeight
}
