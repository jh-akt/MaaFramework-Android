package com.maaframework.android.bridge;

import android.graphics.Bitmap;
import android.view.Surface;

public final class NativeBridgeLib {
    private NativeBridgeLib() {}

    public static boolean LOADED;

    static {
        try {
            System.loadLibrary("bridge");
            LOADED = true;
        } catch (Throwable e) {
            LOADED = false;
        }
    }

    public static native String ping();

    public static native Surface setupNativeCapturer(int width, int height);

    public static native void releaseNativeCapturer();

    public static native void setPreviewSurface(Object surface);

    public static native long getFrameCount();

    public static native Bitmap capturePreviewFrame();
}
