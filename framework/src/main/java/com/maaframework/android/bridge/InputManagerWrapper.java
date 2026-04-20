package com.maaframework.android.bridge;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.InputEvent;

import com.maaframework.android.preview.FakeContext;

import java.lang.reflect.Method;

@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
public final class InputManagerWrapper {

    public static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1;
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2;

    private static Method injectInputEventMethod;
    private static Method setDisplayIdMethod;

    private final android.hardware.input.InputManager manager;

    public static InputManagerWrapper create(Context context) {
        Context fakeContext = new FakeContext(context.getApplicationContext());
        android.hardware.input.InputManager manager =
                (android.hardware.input.InputManager) fakeContext.getSystemService(Context.INPUT_SERVICE);
        return new InputManagerWrapper(manager);
    }

    private InputManagerWrapper(android.hardware.input.InputManager manager) {
        this.manager = manager;
    }

    private static Method getInjectInputEventMethod() throws NoSuchMethodException {
        if (injectInputEventMethod == null) {
            injectInputEventMethod = android.hardware.input.InputManager.class
                    .getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        return injectInputEventMethod;
    }

    public boolean injectInputEvent(InputEvent inputEvent, int mode) {
        if (manager == null) {
            return false;
        }
        try {
            Method method = getInjectInputEventMethod();
            return (boolean) method.invoke(manager, inputEvent, mode);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static Method getSetDisplayIdMethod() throws NoSuchMethodException {
        if (setDisplayIdMethod == null) {
            setDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", int.class);
        }
        return setDisplayIdMethod;
    }

    public static boolean setDisplayId(InputEvent inputEvent, int displayId) {
        try {
            Method method = getSetDisplayIdMethod();
            method.invoke(inputEvent, displayId);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
