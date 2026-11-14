package com.maaframework.android.root;

import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public final class RootServiceStarter {

    private static final String TAG = "RootServiceStarter";
    // Keep the BinderProxy alive for the lifetime of this process. A local reference
    // can be collected after sendBinder returns, dropping the death registration.
    private static IBinder appLifecycleBinder;

    private RootServiceStarter() {
    }

    public static void main(String[] args) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        RootUserService.CreatedService createdService = RootUserService.create(args);
        if (createdService == null) {
            System.exit(1);
            return;
        }

        if (!sendBinder(createdService)) {
            System.exit(1);
            return;
        }

        Looper.loop();
        System.exit(0);
    }

    private static boolean sendBinder(RootUserService.CreatedService createdService) {
        IBinder lifecycleBinder = RootServiceBootstrapClient.attachRemoteService(
                createdService.packageName(),
                createdService.userId(),
                createdService.token(),
                createdService.service()
        );
        if (lifecycleBinder == null) {
            return false;
        }

        try {
            lifecycleBinder.linkToDeath(() -> {
                Log.i(TAG, "App process died, destroying root runtime");
                // A blocked native call cannot indefinitely retain an orphan process.
                Thread watchdog = new Thread(() -> {
                    android.os.SystemClock.sleep(30_000);
                    android.os.Process.killProcess(android.os.Process.myPid());
                }, "maa-orphan-watchdog");
                watchdog.setDaemon(true);
                watchdog.start();
                destroyService(createdService.service());
            }, 0);
            appLifecycleBinder = lifecycleBinder;
            return true;
        } catch (Throwable tr) {
            Log.e(TAG, "Failed to link app lifecycle binder", tr);
            return false;
        }
    }

    private static void destroyService(IBinder service) {
        if (service == null || !service.pingBinder()) {
            return;
        }

        try {
            com.maaframework.android.ipc.IRootRuntimeService.Stub.asInterface(service).destroy();
        } catch (Throwable error) {
            Log.w(TAG, "destroy root runtime failed", error);
        }
    }
}
