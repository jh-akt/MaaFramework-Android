package com.maaframework.android.preview;

import android.graphics.Rect;

public record Size(int width, int height) {
    public Rect toRect() {
        return new Rect(0, 0, width, height);
    }
}
