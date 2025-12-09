package com.mcdigital.ecosystem.spice;

import java.awt.image.BufferedImage;

/**
 * JNI wrapper for libspice-client-glib
 */
public class SpiceClientJNI {
    static {
        try {
            System.loadLibrary("spiceclient");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load libspiceclient. Make sure libspice-client-glib is installed.");
            e.printStackTrace();
        }
    }

    // Native methods
    public static native long spiceConnect(String host, int port);
    public static native void spiceDisconnect(long handle);
    public static native boolean spiceIsConnected(long handle);
    public static native void spiceSendKey(long handle, int keyCode, boolean pressed);
    public static native void spiceSendMouseMove(long handle, int x, int y);
    public static native void spiceSendMouseButton(long handle, int button, boolean pressed);
    public static native int spiceGetWidth(long handle);
    public static native int spiceGetHeight(long handle);
    public static native void spiceUpdate(long handle);
    
    // Callback interface for frame updates
    public interface FrameCallback {
        void onFrame(BufferedImage frame);
    }
    
    // Called from native code when a frame is ready
    private static void onFrameReady(long handle, int[] pixels, int width, int height) {
        // This will be called from native code via JNI
        // The actual callback is stored per-handle in native code
    }
    
    // Register callback for a specific handle
    public static native void spiceSetFrameCallback(long handle, FrameCallback callback);
}

