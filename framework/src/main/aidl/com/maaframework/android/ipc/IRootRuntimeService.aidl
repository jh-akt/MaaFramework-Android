package com.maaframework.android.ipc;

import android.view.Surface;

interface IRootRuntimeService {
    String ping();
    boolean prepareRuntime();
    boolean startRun(String runRequestJson);
    void stopRun();
    void setMonitorSurface(in Surface surface);
    boolean startWindowedGame(String resourceId);
    boolean touchDown(int contactId, int x, int y);
    boolean touchMove(int contactId, int x, int y);
    boolean touchUp(int contactId, int x, int y);
    int getWindowedDisplayId();
    void stopWindowedPreview();
    boolean setDisplayPower(boolean on);
    String getState();
    String readLogChunk(long offsetBytes, int maxBytes);
    String exportDiagnostics();
    oneway void destroy();
}
