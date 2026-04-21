package com.maaframework.android.bridge;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.Surface;

public final class NativeBridgeLib {
    private static final String TAG = "NativeBridgeLib";

    private NativeBridgeLib() {}

    public static boolean LOADED;

    static {
        try {
            System.loadLibrary("bridge");
            bootstrap(DriverClass.class);
            LOADED = true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load bridge library", e);
            LOADED = false;
        }
    }

    private static native void bootstrap(Class<?> driverClass);

    public static native String ping();

    public static native Surface setupNativeCapturer(int width, int height);

    public static native void releaseNativeCapturer();

    public static native void setPreviewSurface(Object surface);

    public static native long getFrameCount();

    public static native Bitmap capturePreviewFrame();
}
