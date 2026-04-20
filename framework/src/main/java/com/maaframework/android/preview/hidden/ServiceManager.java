package com.maaframework.android.preview.hidden;

public final class ServiceManager {
    private static DisplayManager displayManager;
    private static ActivityManager activityManager;

    private ServiceManager() {}

    public static synchronized DisplayManager getDisplayManager() {
        if (displayManager == null) {
            displayManager = DisplayManager.create();
        }
        return displayManager;
    }

    public static synchronized ActivityManager getActivityManager() {
        if (activityManager == null) {
            activityManager = ActivityManager.create();
        }
        return activityManager;
    }
}
