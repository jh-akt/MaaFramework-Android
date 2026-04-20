package com.maaframework.android.root;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

public final class RootServiceBootstrapClient {

    private static final String TAG = "RootBootstrapClient";

    private RootServiceBootstrapClient() {
    }

    public static IBinder attachRemoteService(String packageName, int userId, String token, IBinder serviceBinder) {
        String authority = packageName + RootServiceBootstrapRegistry.AUTHORITY_SUFFIX;
        IBinder providerToken = new Binder();
        ActivityManagerBridge bridge = ActivityManagerBridge.create();
        Object provider = null;

        try {
            provider = bridge.getContentProviderExternal(authority, userId, providerToken, authority);
            if (provider == null) {
                Log.e(TAG, "Root bootstrap provider is null: " + authority + " user=" + userId);
                return null;
            }

            Bundle extras = new Bundle();
            extras.putString(RootServiceBootstrapRegistry.KEY_TOKEN, token);
            extras.putBinder(RootServiceBootstrapRegistry.KEY_SERVICE_BINDER, serviceBinder);

            Bundle reply = RootIContentProviderCompat.call(
                    provider,
                    authority,
                    RootServiceBootstrapRegistry.METHOD_ATTACH_REMOTE_SERVICE,
                    extras
            );
            if (reply == null) {
                Log.e(TAG, "Root bootstrap provider returned null");
                return null;
            }

            IBinder lifecycleBinder = reply.getBinder(RootServiceBootstrapRegistry.KEY_APP_BINDER);
            if (lifecycleBinder == null || !lifecycleBinder.pingBinder()) {
                Log.e(TAG, "Root bootstrap app lifecycle binder missing");
                return null;
            }
            return lifecycleBinder;
        } catch (Throwable tr) {
            Log.e(TAG, "Failed to send binder back to app", tr);
            return null;
        } finally {
            bridge.removeContentProviderExternal(authority, providerToken);
        }
    }
}
