package com.maaframework.android.root;

import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class ActivityManagerBridge {

    private final IInterface manager;
    private Method getContentProviderExternalMethod;
    private boolean getContentProviderExternalMethodNewVersion = true;
    private Method removeContentProviderExternalMethod;

    static ActivityManagerBridge create() {
        try {
            Class<?> cls = Class.forName("android.app.ActivityManagerNative");
            Method getDefaultMethod = cls.getDeclaredMethod("getDefault");
            IInterface am = (IInterface) getDefaultMethod.invoke(null);
            return new ActivityManagerBridge(am);
        } catch (ReflectiveOperationException ignored) {
            try {
                Class<?> cls = Class.forName("android.app.ActivityManager");
                Method getServiceMethod = cls.getDeclaredMethod("getService");
                getServiceMethod.setAccessible(true);
                IInterface am = (IInterface) getServiceMethod.invoke(null);
                return new ActivityManagerBridge(am);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    private ActivityManagerBridge(IInterface manager) {
        this.manager = manager;
    }

    private Method getGetContentProviderExternalMethod() throws NoSuchMethodException {
        if (getContentProviderExternalMethod == null) {
            try {
                getContentProviderExternalMethod = manager.getClass()
                        .getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class);
            } catch (NoSuchMethodException e) {
                getContentProviderExternalMethod = manager.getClass()
                        .getMethod("getContentProviderExternal", String.class, int.class, IBinder.class);
                getContentProviderExternalMethodNewVersion = false;
            }
        }
        return getContentProviderExternalMethod;
    }

    private Method getRemoveContentProviderExternalMethod() throws NoSuchMethodException {
        if (removeContentProviderExternalMethod == null) {
            removeContentProviderExternalMethod = manager.getClass()
                    .getMethod("removeContentProviderExternal", String.class, IBinder.class);
        }
        return removeContentProviderExternalMethod;
    }

    Object getContentProviderExternal(String authority, int userId, IBinder token, String tag) {
        try {
            Method method = getGetContentProviderExternalMethod();
            Object[] args;
            if (getContentProviderExternalMethodNewVersion) {
                args = new Object[] { authority, userId, token, tag };
            } else {
                args = new Object[] { authority, userId, token };
            }
            Object providerHolder = method.invoke(manager, args);
            if (providerHolder == null) {
                return null;
            }
            Field providerField = providerHolder.getClass().getDeclaredField("provider");
            providerField.setAccessible(true);
            return providerField.get(providerHolder);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    void removeContentProviderExternal(String authority, IBinder token) {
        try {
            Method method = getRemoveContentProviderExternalMethod();
            method.invoke(manager, authority, token);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
