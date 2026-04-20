package com.maaframework.android.bridge;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

public final class InputControlUtils {

    private static final String TAG = "InputControlUtils";

    private static final int DEFAULT_DEVICE_ID = 0;
    private static final int DEFAULT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN;

    private static final MotionEvent.PointerProperties[] POINTER_PROPERTIES = new MotionEvent.PointerProperties[1];
    private static final MotionEvent.PointerCoords[] POINTER_COORDS = new MotionEvent.PointerCoords[1];

    private static volatile InputManagerWrapper manager;
    private static long currentDownTime = 0L;

    static {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = MotionEvent.TOOL_TYPE_FINGER;
        POINTER_PROPERTIES[0] = props;
        POINTER_COORDS[0] = new MotionEvent.PointerCoords();
    }

    private InputControlUtils() {}

    public static void initialize(Context context) {
        if (manager != null) {
            return;
        }
        synchronized (InputControlUtils.class) {
            if (manager == null) {
                manager = InputManagerWrapper.create(context);
            }
        }
    }

    private static InputManagerWrapper getManager() {
        return manager;
    }

    private static void setPointerCoords(float x, float y, float pressure) {
        MotionEvent.PointerCoords coords = POINTER_COORDS[0];
        coords.x = Math.max(0f, x);
        coords.y = Math.max(0f, y);
        coords.pressure = pressure;
        coords.size = 1.0f;
    }

    private static MotionEvent obtainTouchEvent(
            long downTime,
            long eventTime,
            int action,
            float x,
            float y,
            float pressure
    ) {
        setPointerCoords(x, y, pressure);
        return MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                1,
                POINTER_PROPERTIES,
                POINTER_COORDS,
                0,
                0,
                1.0f,
                1.0f,
                DEFAULT_DEVICE_ID,
                0,
                DEFAULT_SOURCE,
                0
        );
    }

    private static boolean setDisplayId(InputEvent event, int displayId) {
        return displayId == 0 || InputManagerWrapper.setDisplayId(event, displayId);
    }

    private static boolean injectInputEvent(MotionEvent event, int displayId, int mode) {
        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();
        try {
            InputManagerWrapper inputManager = getManager();
            if (inputManager == null) {
                Log.w(TAG, "injectTouch skipped: manager unavailable"
                        + " action=" + motionActionName(action)
                        + " display=" + displayId
                        + " x=" + x
                        + " y=" + y
                        + " mode=" + modeName(mode));
                return false;
            }
            if (!setDisplayId(event, displayId)) {
                Log.w(TAG, "injectTouch skipped: setDisplayId failed"
                        + " action=" + motionActionName(action)
                        + " display=" + displayId
                        + " x=" + x
                        + " y=" + y
                        + " mode=" + modeName(mode));
                return false;
            }
            boolean injected = inputManager.injectInputEvent(event, mode);
            Log.i(TAG, "injectTouch action=" + motionActionName(action)
                    + " display=" + displayId
                    + " x=" + x
                    + " y=" + y
                    + " mode=" + modeName(mode)
                    + " injected=" + injected);
            return injected;
        } finally {
            event.recycle();
        }
    }

    public static synchronized boolean down(int x, int y, int displayId) {
        if (currentDownTime != 0L) {
            MotionEvent cancelEvent = obtainTouchEvent(
                    currentDownTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_CANCEL,
                    (float) x,
                    (float) y,
                    0.0f
            );
            injectInputEvent(cancelEvent, displayId, InputManagerWrapper.INJECT_INPUT_EVENT_MODE_ASYNC);
        }

        currentDownTime = SystemClock.uptimeMillis();
        MotionEvent motionEvent = obtainTouchEvent(
                currentDownTime,
                currentDownTime,
                MotionEvent.ACTION_DOWN,
                (float) x,
                (float) y,
                1.0f
        );
        return injectInputEvent(motionEvent, displayId, InputManagerWrapper.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    public static synchronized boolean move(int x, int y, int displayId) {
        if (currentDownTime == 0L) {
            return false;
        }
        long eventTime = SystemClock.uptimeMillis();
        MotionEvent motionEvent = obtainTouchEvent(
                currentDownTime,
                eventTime,
                MotionEvent.ACTION_MOVE,
                (float) x,
                (float) y,
                1.0f
        );
        return injectInputEvent(motionEvent, displayId, InputManagerWrapper.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    public static synchronized boolean up(int x, int y, int displayId) {
        if (currentDownTime == 0L) {
            return false;
        }
        long eventTime = SystemClock.uptimeMillis();
        MotionEvent motionEvent = obtainTouchEvent(
                currentDownTime,
                eventTime,
                MotionEvent.ACTION_UP,
                (float) x,
                (float) y,
                0.0f
        );
        boolean result = injectInputEvent(motionEvent, displayId, InputManagerWrapper.INJECT_INPUT_EVENT_MODE_ASYNC);
        currentDownTime = 0L;
        return result;
    }

    public static boolean keyDown(int keyCode, int displayId) {
        InputManagerWrapper inputManager = getManager();
        if (inputManager == null) {
            Log.w(TAG, "injectKeyDown skipped: manager unavailable keyCode=" + keyCode + " display=" + displayId);
            return false;
        }
        long downTime = SystemClock.uptimeMillis();
        KeyEvent keyEvent = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0);
        if (!setDisplayId(keyEvent, displayId)) {
            Log.w(TAG, "injectKeyDown skipped: setDisplayId failed keyCode=" + keyCode + " display=" + displayId);
            return false;
        }
        boolean injected = inputManager.injectInputEvent(
                keyEvent,
                InputManagerWrapper.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
        );
        Log.i(TAG, "injectKey action=DOWN keyCode=" + keyCode
                + " display=" + displayId
                + " mode=" + modeName(InputManagerWrapper.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)
                + " injected=" + injected);
        return injected;
    }

    public static boolean keyUp(int keyCode, int displayId) {
        InputManagerWrapper inputManager = getManager();
        if (inputManager == null) {
            Log.w(TAG, "injectKeyUp skipped: manager unavailable keyCode=" + keyCode + " display=" + displayId);
            return false;
        }
        long upTime = SystemClock.uptimeMillis();
        KeyEvent keyEvent = new KeyEvent(upTime, upTime, KeyEvent.ACTION_UP, keyCode, 0);
        if (!setDisplayId(keyEvent, displayId)) {
            Log.w(TAG, "injectKeyUp skipped: setDisplayId failed keyCode=" + keyCode + " display=" + displayId);
            return false;
        }
        boolean injected = inputManager.injectInputEvent(
                keyEvent,
                InputManagerWrapper.INJECT_INPUT_EVENT_MODE_ASYNC
        );
        Log.i(TAG, "injectKey action=UP keyCode=" + keyCode
                + " display=" + displayId
                + " mode=" + modeName(InputManagerWrapper.INJECT_INPUT_EVENT_MODE_ASYNC)
                + " injected=" + injected);
        return injected;
    }

    private static String motionActionName(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return "DOWN";
            case MotionEvent.ACTION_MOVE:
                return "MOVE";
            case MotionEvent.ACTION_UP:
                return "UP";
            case MotionEvent.ACTION_CANCEL:
                return "CANCEL";
            default:
                return String.valueOf(action);
        }
    }

    private static String modeName(int mode) {
        switch (mode) {
            case InputManagerWrapper.INJECT_INPUT_EVENT_MODE_ASYNC:
                return "ASYNC";
            case InputManagerWrapper.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT:
                return "WAIT_FOR_RESULT";
            case InputManagerWrapper.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH:
                return "WAIT_FOR_FINISH";
            default:
                return String.valueOf(mode);
        }
    }
}
