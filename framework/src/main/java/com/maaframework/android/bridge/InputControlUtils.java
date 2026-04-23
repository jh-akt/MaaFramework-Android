package com.maaframework.android.bridge;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class InputControlUtils {

    private static final String TAG = "InputControlUtils";

    private static final int DEFAULT_DEVICE_ID = 0;
    private static final int DEFAULT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN;
    private static final int NO_DISPLAY_ID = -1;
    private static final int MAX_POINTER_ID = 31;

    private static volatile InputManagerWrapper manager;
    private static final LinkedHashMap<Integer, TouchPointerState> activePointers = new LinkedHashMap<>();
    private static long currentDownTime = 0L;
    private static int currentDisplayId = NO_DISPLAY_ID;

    private InputControlUtils() {}

    private static final class TouchPointerState {
        final int pointerId;
        float x;
        float y;

        TouchPointerState(int pointerId, float x, float y) {
            this.pointerId = pointerId;
            this.x = x;
            this.y = y;
        }
    }

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

    private static MotionEvent.PointerCoords createPointerCoords(float x, float y, float pressure) {
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = Math.max(0f, x);
        coords.y = Math.max(0f, y);
        coords.pressure = pressure;
        coords.size = 1.0f;
        return coords;
    }

    private static MotionEvent.PointerProperties createPointerProperties(int pointerId) {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = pointerId;
        props.toolType = MotionEvent.TOOL_TYPE_FINGER;
        return props;
    }

    private static MotionEvent obtainTouchEvent(
            long downTime,
            long eventTime,
            int action,
            List<TouchPointerState> pointers,
            int actionPointerId,
            float actionPressure
    ) {
        int pointerCount = pointers.size();
        MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[pointerCount];
        int actionIndex = 0;

        for (int index = 0; index < pointerCount; index++) {
            TouchPointerState pointer = pointers.get(index);
            pointerProperties[index] = createPointerProperties(pointer.pointerId);

            float pressure = pointer.pointerId == actionPointerId ? actionPressure : 1.0f;
            if (action == MotionEvent.ACTION_CANCEL) {
                pressure = 0.0f;
            }
            pointerCoords[index] = createPointerCoords(pointer.x, pointer.y, pressure);

            if (pointer.pointerId == actionPointerId) {
                actionIndex = index;
            }
        }

        int resolvedAction = action;
        if ((action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) && pointerCount > 1) {
            resolvedAction = action | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }

        return MotionEvent.obtain(
                downTime,
                eventTime,
                resolvedAction,
                pointerCount,
                pointerProperties,
                pointerCoords,
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
        try {
            InputManagerWrapper inputManager = getManager();
            if (inputManager == null) {
                Log.w(TAG, "injectTouch skipped: manager unavailable"
                        + " action=" + motionActionName(action)
                        + " display=" + displayId
                        + " actionIndex=" + event.getActionIndex()
                        + " pointerCount=" + event.getPointerCount()
                        + " pointers=" + pointerSummary(event)
                        + " mode=" + modeName(mode));
                return false;
            }
            if (!setDisplayId(event, displayId)) {
                Log.w(TAG, "injectTouch skipped: setDisplayId failed"
                        + " action=" + motionActionName(action)
                        + " display=" + displayId
                        + " actionIndex=" + event.getActionIndex()
                        + " pointerCount=" + event.getPointerCount()
                        + " pointers=" + pointerSummary(event)
                        + " mode=" + modeName(mode));
                return false;
            }
            boolean injected = inputManager.injectInputEvent(event, mode);
            Log.i(TAG, "injectTouch action=" + motionActionName(action)
                    + " display=" + displayId
                    + " actionIndex=" + event.getActionIndex()
                    + " pointerCount=" + event.getPointerCount()
                    + " pointers=" + pointerSummary(event)
                    + " mode=" + modeName(mode)
                    + " injected=" + injected);
            return injected;
        } finally {
            event.recycle();
        }
    }

    private static int allocatePointerId() {
        boolean[] used = new boolean[MAX_POINTER_ID + 1];
        for (TouchPointerState pointer : activePointers.values()) {
            if (pointer.pointerId >= 0 && pointer.pointerId <= MAX_POINTER_ID) {
                used[pointer.pointerId] = true;
            }
        }
        for (int pointerId = 0; pointerId <= MAX_POINTER_ID; pointerId++) {
            if (!used[pointerId]) {
                return pointerId;
            }
        }
        return -1;
    }

    private static List<TouchPointerState> snapshotActivePointers() {
        return new ArrayList<>(activePointers.values());
    }

    private static void clearTouchState() {
        activePointers.clear();
        currentDownTime = 0L;
        currentDisplayId = NO_DISPLAY_ID;
    }

    private static void cancelActiveTouches(String reason, int displayId) {
        if (activePointers.isEmpty()) {
            clearTouchState();
            return;
        }
        long eventTime = SystemClock.uptimeMillis();
        MotionEvent cancelEvent = obtainTouchEvent(
                currentDownTime != 0L ? currentDownTime : eventTime,
                eventTime,
                MotionEvent.ACTION_CANCEL,
                snapshotActivePointers(),
                -1,
                0.0f
        );
        Log.w(TAG, "Cancelling active touches reason=" + reason
                + " display=" + displayId
                + " pointers=" + activePointers.size());
        injectInputEvent(cancelEvent, displayId, InputManagerWrapper.INJECT_INPUT_EVENT_MODE_ASYNC);
        clearTouchState();
    }

    public static synchronized boolean down(int x, int y, int displayId) {
        return down(0, x, y, displayId);
    }

    public static synchronized boolean down(int contactId, int x, int y, int displayId) {
        if (activePointers.containsKey(contactId)) {
            cancelActiveTouches("duplicate down for contact " + contactId, currentDisplayId != NO_DISPLAY_ID ? currentDisplayId : displayId);
        }
        if (!activePointers.isEmpty() && currentDisplayId != displayId) {
            cancelActiveTouches("display changed from " + currentDisplayId + " to " + displayId, currentDisplayId);
        }

        int pointerId = allocatePointerId();
        if (pointerId < 0) {
            Log.w(TAG, "injectTouch skipped: no free pointer id for contact=" + contactId);
            return false;
        }

        long eventTime = SystemClock.uptimeMillis();
        if (activePointers.isEmpty()) {
            currentDownTime = eventTime;
            currentDisplayId = displayId;
        }

        TouchPointerState pointer = new TouchPointerState(pointerId, (float) x, (float) y);
        activePointers.put(contactId, pointer);
        int action = activePointers.size() == 1 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_POINTER_DOWN;
        MotionEvent motionEvent = obtainTouchEvent(
                currentDownTime,
                eventTime,
                action,
                snapshotActivePointers(),
                pointer.pointerId,
                1.0f
        );
        boolean result = injectInputEvent(motionEvent, displayId, InputManagerWrapper.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
        if (!result) {
            activePointers.remove(contactId);
            if (activePointers.isEmpty()) {
                clearTouchState();
            }
        }
        return result;
    }

    public static synchronized boolean move(int x, int y, int displayId) {
        return move(0, x, y, displayId);
    }

    public static synchronized boolean move(int contactId, int x, int y, int displayId) {
        if (currentDownTime == 0L || activePointers.isEmpty() || currentDisplayId != displayId) {
            return false;
        }
        TouchPointerState pointer = activePointers.get(contactId);
        if (pointer == null) {
            return false;
        }
        pointer.x = x;
        pointer.y = y;
        long eventTime = SystemClock.uptimeMillis();
        MotionEvent motionEvent = obtainTouchEvent(
                currentDownTime,
                eventTime,
                MotionEvent.ACTION_MOVE,
                snapshotActivePointers(),
                -1,
                1.0f
        );
        return injectInputEvent(motionEvent, displayId, InputManagerWrapper.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    public static synchronized boolean up(int x, int y, int displayId) {
        return up(0, x, y, displayId);
    }

    public static synchronized boolean up(int contactId, int x, int y, int displayId) {
        if (currentDownTime == 0L || activePointers.isEmpty() || currentDisplayId != displayId) {
            return false;
        }
        TouchPointerState pointer = activePointers.get(contactId);
        if (pointer == null) {
            return false;
        }
        pointer.x = x;
        pointer.y = y;

        long eventTime = SystemClock.uptimeMillis();
        boolean lastPointer = activePointers.size() == 1;
        MotionEvent motionEvent = obtainTouchEvent(
                currentDownTime,
                eventTime,
                lastPointer ? MotionEvent.ACTION_UP : MotionEvent.ACTION_POINTER_UP,
                snapshotActivePointers(),
                pointer.pointerId,
                0.0f
        );
        boolean result = injectInputEvent(motionEvent, displayId, InputManagerWrapper.INJECT_INPUT_EVENT_MODE_ASYNC);
        activePointers.remove(contactId);
        if (activePointers.isEmpty()) {
            clearTouchState();
        }
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
            case MotionEvent.ACTION_POINTER_DOWN:
                return "POINTER_DOWN";
            case MotionEvent.ACTION_POINTER_UP:
                return "POINTER_UP";
            case MotionEvent.ACTION_UP:
                return "UP";
            case MotionEvent.ACTION_CANCEL:
                return "CANCEL";
            default:
                return String.valueOf(action);
        }
    }

    private static String pointerSummary(MotionEvent event) {
        StringBuilder builder = new StringBuilder();
        int pointerCount = event.getPointerCount();
        for (int index = 0; index < pointerCount; index++) {
            if (index > 0) {
                builder.append(';');
            }
            builder.append(event.getPointerId(index))
                    .append('@')
                    .append((int) event.getX(index))
                    .append(',')
                    .append((int) event.getY(index));
        }
        return builder.toString();
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
