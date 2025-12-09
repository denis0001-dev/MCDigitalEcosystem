#include <jni.h>
#include <spice-client.h>
#include <spice-client-glib-2.0/spice-client.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <glib.h>

// Global reference to JVM for callbacks
static JavaVM *jvm = NULL;

// Structure to hold SPICE client context
typedef struct {
    SpiceSession *session;
    SpiceDisplayChannel *display;
    jobject callback;
    jmethodID onFrameMethod;
    int width;
    int height;
    JNIEnv *env;
} SpiceContext;

// Forward declarations
static void on_display_primary_create(SpiceDisplayChannel *display, 
                                      SpiceDisplayMonitor *monitor,
                                      gint format, gint width, gint height,
                                      gint stride, gint shmid, gpointer data);
static void on_display_invalidate(SpiceDisplayChannel *display,
                                  gint x, gint y, gint width, gint height,
                                  gpointer data);
static void on_channel_new(SpiceSession *session, SpiceChannel *channel, gpointer data);

// Map of native handles to contexts
static GHashTable *contexts = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    jvm = vm;
    contexts = g_hash_table_new(g_direct_hash, g_direct_equal);
    return JNI_VERSION_1_8;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    if (contexts) {
        g_hash_table_destroy(contexts);
        contexts = NULL;
    }
}

// Helper to get JNI environment
static JNIEnv* getEnv() {
    JNIEnv *env;
    if ((*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) {
        return NULL;
    }
    return env;
}

// Callback when display is created
static void on_display_primary_create(SpiceDisplayChannel *display,
                                      SpiceDisplayMonitor *monitor,
                                      gint format, gint width, gint height,
                                      gint stride, gint shmid, gpointer data) {
    SpiceContext *ctx = (SpiceContext *)data;
    ctx->width = width;
    ctx->height = height;
}

// Callback when display region is invalidated (needs update)
static void on_display_invalidate(SpiceDisplayChannel *display,
                                  gint x, gint y, gint width, gint height,
                                  gpointer data) {
    SpiceContext *ctx = (SpiceContext *)data;
    JNIEnv *env = getEnv();
    if (!env || !ctx->callback) {
        return;
    }
    
    // Get the surface from display channel
    SpiceSurface *surface = spice_display_channel_get_primary_surface(display);
    if (!surface) {
        return;
    }
    
    // Get surface data
    gint stride;
    guchar *data_ptr = spice_surface_map(surface, &stride);
    if (!data_ptr) {
        return;
    }
    
    // Convert to RGB and create Java array
    jintArray pixelArray = (*env)->NewIntArray(env, ctx->width * ctx->height);
    if (!pixelArray) {
        spice_surface_unmap(surface);
        return;
    }
    
    jint *pixels = (*env)->GetIntArrayElements(env, pixelArray, NULL);
    
    // Convert BGRA to RGB
    for (int y = 0; y < ctx->height; y++) {
        for (int x = 0; x < ctx->width; x++) {
            guchar *src = data_ptr + y * stride + x * 4;
            guchar b = src[0];
            guchar g = src[1];
            guchar r = src[2];
            
            pixels[y * ctx->width + x] = (r << 16) | (g << 8) | b;
        }
    }
    
    (*env)->ReleaseIntArrayElements(env, pixelArray, pixels, 0);
    
    // Call Java callback
    if (ctx->callback && ctx->onFrameMethod) {
        // Find FrameCallback interface and call onFrame
        jclass callbackClass = (*env)->GetObjectClass(env, ctx->callback);
        jmethodID onFrameMethod = (*env)->GetMethodID(env, callbackClass, "onFrame", "(Ljava/awt/image/BufferedImage;)V");
        
        if (onFrameMethod) {
            // Create BufferedImage from pixels
            jclass bufferedImageClass = (*env)->FindClass(env, "java/awt/image/BufferedImage");
            jmethodID bufferedImageConstructor = (*env)->GetMethodID(env, bufferedImageClass, "<init>", "(III)V");
            jobject image = (*env)->NewObject(env, bufferedImageClass, bufferedImageConstructor,
                                            (jint)ctx->width, (jint)ctx->height, 
                                            (jint)1); // TYPE_INT_RGB = 1
            
            jclass imageClass = (*env)->GetObjectClass(env, image);
            jmethodID setRGBMethod = (*env)->GetMethodID(env, imageClass, "setRGB", "(IIII[III)V");
            (*env)->CallVoidMethod(env, image, setRGBMethod, 0, 0, ctx->width, ctx->height,
                                   pixelArray, 0, ctx->width);
            
            (*env)->CallVoidMethod(env, ctx->callback, onFrameMethod, image);
            
            (*env)->DeleteLocalRef(env, image);
            (*env)->DeleteLocalRef(env, imageClass);
            (*env)->DeleteLocalRef(env, callbackClass);
        }
    }
    
    (*env)->DeleteLocalRef(env, pixelArray);
    spice_surface_unmap(surface);
}

// Callback when new channel is created
static void on_channel_new(SpiceSession *session, SpiceChannel *channel, gpointer data) {
    SpiceContext *ctx = (SpiceContext *)data;
    
    if (SPICE_IS_DISPLAY_CHANNEL(channel)) {
        ctx->display = SPICE_DISPLAY_CHANNEL(channel);
        g_signal_connect(channel, "display-primary-create",
                        G_CALLBACK(on_display_primary_create), ctx);
        g_signal_connect(channel, "display-invalidate",
                        G_CALLBACK(on_display_invalidate), ctx);
    }
}

JNIEXPORT jlong JNICALL
Java_com_mcdigital_ecosystem_spice_SpiceClientJNI_spiceConnect(JNIEnv *env, jclass clazz,
                                                                 jstring host, jint port) {
    SpiceContext *ctx = (SpiceContext *)malloc(sizeof(SpiceContext));
    if (!ctx) {
        return 0;
    }
    
    memset(ctx, 0, sizeof(SpiceContext));
    ctx->env = env;
    
    // Create SPICE session
    ctx->session = spice_session_new();
    if (!ctx->session) {
        free(ctx);
        return 0;
    }
    
    // Set connection parameters
    const char *host_str = (*env)->GetStringUTFChars(env, host, NULL);
    g_object_set(ctx->session,
                 "host", host_str,
                 "port", port,
                 "password", "",
                 NULL);
    (*env)->ReleaseStringUTFChars(env, host, host_str);
    
    // Connect signals
    g_signal_connect(ctx->session, "channel-new",
                    G_CALLBACK(on_channel_new), ctx);
    
    // Connect to server
    if (!spice_session_connect(ctx->session)) {
        g_object_unref(ctx->session);
        free(ctx);
        return 0;
    }
    
    // Store context
    jlong handle = (jlong)ctx;
    g_hash_table_insert(contexts, (gpointer)handle, ctx);
    
    return handle;
}

JNIEXPORT void JNICALL
Java_com_mcdigital_ecosystem_spice_SpiceClientJNI_spiceSetFrameCallback(JNIEnv *env, jclass clazz,
                                                                         jlong handle, jobject callback) {
    SpiceContext *ctx = (SpiceContext *)handle;
    if (!ctx) {
        return;
    }
    
    // Store callback as global reference
    if (ctx->callback) {
        (*env)->DeleteGlobalRef(env, ctx->callback);
    }
    
    if (callback) {
        ctx->callback = (*env)->NewGlobalRef(env, callback);
    } else {
        ctx->callback = NULL;
    }
}

JNIEXPORT void JNICALL
Java_com_mcdigital_ecosystem_spice_SpiceClientJNI_spiceDisconnect(JNIEnv *env, jclass clazz,
                                                                    jlong handle) {
    SpiceContext *ctx = (SpiceContext *)handle;
    if (!ctx) {
        return;
    }
    
    if (ctx->callback) {
        (*env)->DeleteGlobalRef(env, ctx->callback);
        ctx->callback = NULL;
    }
    
    if (ctx->session) {
        spice_session_disconnect(ctx->session);
        g_object_unref(ctx->session);
        ctx->session = NULL;
    }
    
    g_hash_table_remove(contexts, (gpointer)handle);
    free(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_mcdigital_ecosystem_spice_SpiceClientJNI_spiceIsConnected(JNIEnv *env, jclass clazz,
                                                                     jlong handle) {
    SpiceContext *ctx = (SpiceContext *)handle;
    if (!ctx || !ctx->session) {
        return JNI_FALSE;
    }
    
    return spice_session_is_connected(ctx->session) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mcdigital_ecosystem_spice_SpiceClientJNI_spiceSendKey(JNIEnv *env, jclass clazz,
                                                                 jlong handle, jint keyCode,
                                                                 jboolean pressed) {
    SpiceContext *ctx = (SpiceContext *)handle;
    if (!ctx || !ctx->session) {
        return;
    }
    
    SpiceInputsChannel *inputs = spice_session_get_inputs(ctx->session);
    if (inputs) {
        if (pressed) {
            spice_inputs_channel_key_press(inputs, keyCode);
        } else {
            spice_inputs_channel_key_release(inputs, keyCode);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_mcdigital_ecosystem_spice_SpiceClientJNI_spiceSendMouseMove(JNIEnv *env, jclass clazz,
                                                                       jlong handle, jint x, jint y) {
    SpiceContext *ctx = (SpiceContext *)handle;
    if (!ctx || !ctx->session) {
        return;
    }
    
    SpiceInputsChannel *inputs = spice_session_get_inputs(ctx->session);
    if (inputs) {
        spice_inputs_channel_position(inputs, x, y, 0, 0);
    }
}

JNIEXPORT void JNICALL
Java_com_mcdigital_ecosystem_spice_SpiceClientJNI_spiceSendMouseButton(JNIEnv *env, jclass clazz,
                                                                        jlong handle, jint button,
                                                                        jboolean pressed) {
    SpiceContext *ctx = (SpiceContext *)handle;
    if (!ctx || !ctx->session) {
        return;
    }
    
    SpiceInputsChannel *inputs = spice_session_get_inputs(ctx->session);
    if (inputs) {
        guint button_mask = 0;
        if (button == 0) button_mask = SPICE_MOUSE_BUTTON_LEFT;
        else if (button == 1) button_mask = SPICE_MOUSE_BUTTON_RIGHT;
        else if (button == 2) button_mask = SPICE_MOUSE_BUTTON_MIDDLE;
        
        if (pressed) {
            spice_inputs_channel_button_press(inputs, button_mask);
        } else {
            spice_inputs_channel_button_release(inputs, button_mask);
        }
    }
}

JNIEXPORT jint JNICALL
Java_com_mcdigital_ecosystem_spice_SpiceClientJNI_spiceGetWidth(JNIEnv *env, jclass clazz,
                                                                  jlong handle) {
    SpiceContext *ctx = (SpiceContext *)handle;
    if (!ctx) {
        return 0;
    }
    return ctx->width;
}

JNIEXPORT jint JNICALL
Java_com_mcdigital_ecosystem_spice_SpiceClientJNI_spiceGetHeight(JNIEnv *env, jclass clazz,
                                                                   jlong handle) {
    SpiceContext *ctx = (SpiceContext *)handle;
    if (!ctx) {
        return 0;
    }
    return ctx->height;
}

JNIEXPORT void JNICALL
Java_com_mcdigital_ecosystem_spice_SpiceClientJNI_spiceUpdate(JNIEnv *env, jclass clazz,
                                                               jlong handle) {
    SpiceContext *ctx = (SpiceContext *)handle;
    if (!ctx || !ctx->session) {
        return;
    }
    
    // Process GLib main loop events
    while (g_main_context_pending(NULL)) {
        g_main_context_iteration(NULL, FALSE);
    }
}
