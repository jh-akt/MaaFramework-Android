package com.maaframework.android.preview.hidden;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import com.maaframework.android.preview.DisplayInfo;
import com.maaframework.android.preview.FakeContext;
import com.maaframework.android.preview.Size;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressLint("PrivateApi")
public final class DisplayManager {

    private static final String TAG = "HiddenDisplayManager";
    private static final long EVENT_FLAG_DISPLAY_CHANGED = 1L << 2;

    public interface Listener {
        void onDisplayChanged(int displayId);
    }

    public static final class ListenerHandle {
        private final Object proxy;

        private ListenerHandle(Object proxy) {
            this.proxy = proxy;
        }
    }

    private final Object manager;
    private Method getDisplayInfoMethod;

    static DisplayManager create() {
        try {
            Class<?> clazz = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Method getInstanceMethod = clazz.getDeclaredMethod("getInstance");
            Object dmg = getInstanceMethod.invoke(null);
            return new DisplayManager(dmg);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private DisplayManager(Object manager) {
        this.manager = manager;
    }

    private static DisplayInfo parseDisplayInfo(String dumpsysDisplayOutput, int displayId) {
        Pattern regex = Pattern.compile(
                "^    mOverrideDisplayInfo=DisplayInfo\\{\".*?, displayId " + displayId + ".*?(, FLAG_.*)?, real ([0-9]+) x ([0-9]+).*?, "
                        + "rotation ([0-9]+).*?, density ([0-9]+).*?, layerStack ([0-9]+)",
                Pattern.MULTILINE);
        Matcher matcher = regex.matcher(dumpsysDisplayOutput);
        if (!matcher.find()) {
            return null;
        }

        int flags = parseDisplayFlags(matcher.group(1));
        int width = Integer.parseInt(matcher.group(2));
        int height = Integer.parseInt(matcher.group(3));
        int rotation = Integer.parseInt(matcher.group(4));
        int dpi = Integer.parseInt(matcher.group(5));
        int layerStack = Integer.parseInt(matcher.group(6));
        return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags, dpi, null);
    }

    private static int parseDisplayFlags(String text) {
        if (text == null) {
            return 0;
        }

        int flags = 0;
        Matcher matcher = Pattern.compile("FLAG_[A-Z_]+").matcher(text);
        while (matcher.find()) {
            String flagString = matcher.group();
            try {
                Field field = Display.class.getDeclaredField(flagString);
                flags |= field.getInt(null);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return flags;
    }

    private static DisplayInfo getDisplayInfoFromDumpsys(int displayId) {
        try {
            Process process = new ProcessBuilder("dumpsys", "display").start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            process.waitFor();
            return parseDisplayInfo(output.toString(), displayId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse display info from dumpsys", e);
            return null;
        }
    }

    private synchronized Method getGetDisplayInfoMethod() throws NoSuchMethodException {
        if (getDisplayInfoMethod == null) {
            getDisplayInfoMethod = manager.getClass().getMethod("getDisplayInfo", int.class);
        }
        return getDisplayInfoMethod;
    }

    public DisplayInfo getDisplayInfo(int displayId) {
        try {
            Method method = getGetDisplayInfoMethod();
            Object displayInfo = method.invoke(manager, displayId);
            if (displayInfo == null) {
                return getDisplayInfoFromDumpsys(displayId);
            }
            Class<?> cls = displayInfo.getClass();
            int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
            int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
            int rotation = cls.getDeclaredField("rotation").getInt(displayInfo);
            int layerStack = cls.getDeclaredField("layerStack").getInt(displayInfo);
            int flags = cls.getDeclaredField("flags").getInt(displayInfo);
            int dpi = cls.getDeclaredField("logicalDensityDpi").getInt(displayInfo);
            String uniqueId = (String) cls.getDeclaredField("uniqueId").get(displayInfo);
            return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags, dpi, uniqueId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    public VirtualDisplay createNewVirtualDisplay(Context context, String name, int width, int height, int dpi, Surface surface, int flags) throws Exception {
        Constructor<android.hardware.display.DisplayManager> ctor =
                android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        android.hardware.display.DisplayManager dm = ctor.newInstance(new FakeContext(context));
        return dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
    }

    public ListenerHandle registerDisplayListener(Listener listener, Handler handler) {
        try {
            Class<?> displayListenerClass = Class.forName("android.hardware.display.DisplayManager$DisplayListener");
            Object proxy = Proxy.newProxyInstance(
                    ClassLoader.getSystemClassLoader(),
                    new Class[]{displayListenerClass},
                    (p, method, args) -> {
                        if ("onDisplayChanged".equals(method.getName())) {
                            listener.onDisplayChanged((int) args[0]);
                        }
                        return null;
                    });
            try {
                manager.getClass()
                        .getMethod("registerDisplayListener", displayListenerClass, Handler.class, long.class, String.class)
                        .invoke(manager, proxy, handler, EVENT_FLAG_DISPLAY_CHANGED, FakeContext.PACKAGE_NAME);
            } catch (NoSuchMethodException e) {
                try {
                    manager.getClass()
                            .getMethod("registerDisplayListener", displayListenerClass, Handler.class, long.class)
                            .invoke(manager, proxy, handler, EVENT_FLAG_DISPLAY_CHANGED);
                } catch (NoSuchMethodException ignored) {
                    manager.getClass()
                            .getMethod("registerDisplayListener", displayListenerClass, Handler.class)
                            .invoke(manager, proxy, handler);
                }
            }
            return new ListenerHandle(proxy);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register display listener", e);
            return null;
        }
    }

    public void unregisterDisplayListener(ListenerHandle handle) {
        if (handle == null) {
            return;
        }
        try {
            Class<?> displayListenerClass = Class.forName("android.hardware.display.DisplayManager$DisplayListener");
            manager.getClass().getMethod("unregisterDisplayListener", displayListenerClass).invoke(manager, handle.proxy);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister display listener", e);
        }
    }
}
