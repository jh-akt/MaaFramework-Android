package com.maaframework.android.root;

import android.content.AttributionSource;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;

import java.lang.reflect.Method;

final class RootIContentProviderCompat {

    private RootIContentProviderCompat() {
    }

    static Bundle call(
            Object provider,
            String authority,
            String method,
            Bundle extras
    ) throws Exception {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Method m = provider.getClass().getMethod(
                        "call",
                        AttributionSource.class,
                        String.class,
                        String.class,
                        String.class,
                        Bundle.class
                );
                AttributionSource attributionSource = new AttributionSource.Builder(Os.getuid())
                        .setPackageName(null)
                        .setAttributionTag(null)
                        .build();
                return (Bundle) m.invoke(provider, attributionSource, authority, method, null, extras);
            } catch (NoSuchMethodException ignored) {
                Method m = provider.getClass().getMethod(
                        "call",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        Bundle.class
                );
                return (Bundle) m.invoke(provider, null, null, authority, method, null, extras);
            }
        } else if (Build.VERSION.SDK_INT == 30) {
            Method m = provider.getClass().getMethod(
                    "call",
                    String.class,
                    String.class,
                    String.class,
                    String.class,
                    Bundle.class
            );
            return (Bundle) m.invoke(provider, null, null, authority, method, null, extras);
        } else if (Build.VERSION.SDK_INT == 29) {
            Method m = provider.getClass().getMethod(
                    "call",
                    String.class,
                    String.class,
                    String.class,
                    Bundle.class
            );
            return (Bundle) m.invoke(provider, null, authority, method, null, extras);
        } else {
            Method m = provider.getClass().getMethod(
                    "call",
                    String.class,
                    String.class,
                    Bundle.class
            );
            return (Bundle) m.invoke(provider, null, method, null, extras);
        }
    }
}
