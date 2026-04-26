package com.maaframework.android.maa;

import com.maaframework.android.bridge.NativeBridgeLib;
import com.maaframework.android.preview.DefaultDisplayConfig;
import com.maaframework.android.preview.VirtualDisplayManager;
import com.maaframework.android.runtime.RuntimeAgentResolver;

import android.content.Context;
import android.content.res.Resources;
import android.system.Os;
import android.util.DisplayMetrics;
import android.util.Log;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

public final class MaaFrameworkBridge {

    private static final int STATUS_SUCCEEDED = 3000;
    private static final Object LOAD_LOCK = new Object();
    private static final String TAG = "MaaFrameworkBridge";

    private static volatile boolean loaded = false;
    private static volatile MaaFrameworkLibrary library;
    private static volatile MaaAgentClientLibrary agentClientLibrary;

    private Pointer controller;
    private Pointer resource;
    private Pointer tasker;
    private Pointer agentClient;
    private Process agentProcess;
    private File runtimeRootDir;
    private String resourceName = "官服";
    private String resourceLabel = "官服";
    private List<String> resourcePaths = java.util.Collections.singletonList("./resource");
    private List<String> attachResourcePaths = java.util.Collections.emptyList();
    private String logLevel = "info";
    private long controllerSinkId = 0L;
    private long taskerSinkId = 0L;
    private long contextSinkId = 0L;
    private final Deque<String> eventTrace = new ArrayDeque<>();
    private final Object eventTraceLock = new Object();
    private final MaaEventCallback controllerEventCallback = this::onMaaEvent;
    private final MaaEventCallback taskerEventCallback = this::onMaaEvent;
    private final MaaEventCallback contextEventCallback = this::onMaaEvent;

    public void init(
            Context context,
            File runtimeRoot,
            String selectedResourceName,
            String selectedResourceLabel,
            List<String> selectedResourcePaths,
            List<String> selectedAttachResourcePaths,
            String selectedLogLevel
    ) {
        Log.i(TAG, "init start, runtimeRoot=" + runtimeRoot);
        if (!NativeBridgeLib.LOADED) {
            throw new IllegalStateException("bridge library failed to load");
        }
        runtimeRootDir = runtimeRoot;
        resourceName = selectedResourceName == null || selectedResourceName.isBlank() ? "官服" : selectedResourceName;
        resourceLabel = selectedResourceLabel == null || selectedResourceLabel.isBlank() ? resourceName : selectedResourceLabel;
        resourcePaths = normalizeResourcePaths(selectedResourcePaths);
        attachResourcePaths = normalizeAttachResourcePaths(selectedAttachResourcePaths);
        logLevel = normalizeLogLevel(selectedLogLevel);
        ensureLoaded(context, runtimeRoot);
        applyFrameworkLogLevel();
        destroy();

        resource = requireHandle(library.MaaResourceCreate(), "create resource");
        Log.i(TAG, "resource created");
        for (String path : resourcePaths) {
            bundleResource(new File(runtimeRoot, normalizeRuntimeRelativePath(path)));
        }
        File resourceAdb = new File(runtimeRoot, "resource_adb");
        if (resourceAdb.isDirectory()) {
            bundleResource(resourceAdb);
        }
        Log.i(TAG, "resource bundles loaded");

        controller = requireHandle(
                library.MaaAndroidNativeControllerCreate(buildControllerConfig(runtimeRoot, context)),
                "create Android native controller"
        );
        waitControllerSuccess(library.MaaControllerPostConnection(controller), "connect controller");
        attachControllerSink();
        Log.i(TAG, "controller connected");

        tasker = requireHandle(library.MaaTaskerCreate(), "create tasker");
        checkTrue(library.MaaTaskerBindResource(tasker, resource), "bind resource");
        checkTrue(library.MaaTaskerBindController(tasker, controller), "bind controller");
        checkTrue(library.MaaTaskerInited(tasker), "initialize tasker");
        attachTaskerSinks();
        Log.i(TAG, "tasker initialized");
    }

    public String version() {
        return library == null ? "" : library.MaaVersion();
    }

    public RunResult runTask(String entry, String overrideJson) {
        if (tasker == null) {
            return new RunResult(false, "tasker not initialized");
        }
        clearEventTrace();
        try {
            connectAgentIfNeeded();
        } catch (Throwable error) {
            return new RunResult(false, "agent setup failed: " + error.getMessage());
        }
        Log.i(TAG, "runTask start, entry=" + entry + ", override=" + overrideJson);
        long taskId = library.MaaTaskerPostTask(tasker, entry, overrideJson);
        Log.i(TAG, "task posted, taskId=" + taskId);
        if (taskId <= 0) {
            return new RunResult(false, "task post failed, taskId=" + taskId);
        }
        int status = library.MaaTaskerWait(tasker, taskId);
        Log.i(TAG, "task wait finished, status=" + status);
        boolean ok = status == STATUS_SUCCEEDED;
        String diagnostic = ok ? "" : describeTask(taskId);
        if (!diagnostic.isEmpty()) {
            Log.w(TAG, "task diagnostic: " + diagnostic);
        }
        String eventSummary = ok ? "" : summarizeRecentEvents();
        if (!eventSummary.isEmpty()) {
            Log.w(TAG, "task recent events: " + eventSummary);
        }
        String message = ok
                ? "task completed"
                : diagnostic.isEmpty()
                    ? buildFailureMessage(status, "", eventSummary)
                    : buildFailureMessage(status, diagnostic, eventSummary);
        return new RunResult(ok, message);
    }

    public void stop() {
        if (tasker != null) {
            library.MaaTaskerPostStop(tasker);
        }
    }

    public void destroy() {
        disconnectAgent();
        detachTaskerSinks();
        detachControllerSink();
        if (tasker != null) {
            library.MaaTaskerDestroy(tasker);
            tasker = null;
        }
        if (controller != null) {
            library.MaaControllerDestroy(controller);
            controller = null;
        }
        if (resource != null) {
            library.MaaResourceDestroy(resource);
            resource = null;
        }
    }

    private static void ensureLoaded(Context context, File runtimeRoot) {
        if (loaded) {
            return;
        }
        synchronized (LOAD_LOCK) {
            if (loaded) {
                return;
            }

            System.setProperty("jna.tmpdir", "/data/local/tmp");
            System.setProperty("java.io.tmpdir", "/data/local/tmp");
            setTempEnv("TMPDIR", "/data/local/tmp");
            setTempEnv("TMP", "/data/local/tmp");
            setTempEnv("TEMP", "/data/local/tmp");
            setTempEnv("TEMPDIR", "/data/local/tmp");

            File nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
            File runtimeLibDir = new File(runtimeRoot, "maafw");
            File pluginsDir = new File(runtimeLibDir, "plugins");
            if (pluginsDir.exists()) {
                File disabledPluginsDir = new File(runtimeLibDir, "plugins.disabled");
                if (disabledPluginsDir.exists()) {
                    deleteRecursively(disabledPluginsDir);
                }
                if (!pluginsDir.renameTo(disabledPluginsDir)) {
                    throw new IllegalStateException("failed to disable default plugins dir: " + pluginsDir);
                }
                Log.i(TAG, "disabled default plugins dir: " + disabledPluginsDir.getAbsolutePath());
            }
            List<String> preloadOrder = new ArrayList<>();
            preloadOrder.add("libc++_shared.so");
            preloadOrder.add("libonnxruntime.so");
            preloadOrder.add("libfastdeploy_ppocr.so");
            preloadOrder.add("libopencv_world4.so");
            preloadOrder.add("libMaaUtils.so");
            preloadOrder.add("libMaaFramework.so");
            preloadOrder.add("libMaaToolkit.so");
            preloadOrder.add("libMaaAgentClient.so");
            preloadOrder.add("libMaaAndroidNativeControlUnit.so");

            for (String name : preloadOrder) {
                File file;
                if (name.equals("libMaaFramework.so") || name.equals("libMaaToolkit.so") || name.equals("libMaaAndroidNativeControlUnit.so")) {
                    file = new File(nativeLibDir, name);
                } else {
                    file = new File(runtimeLibDir, name);
                }
                if (file.exists()) {
                    Log.i(TAG, "System.load " + file.getAbsolutePath());
                    System.load(file.getAbsolutePath());
                }
            }

            File frameworkFile = new File(nativeLibDir, "libMaaFramework.so");
            Log.i(TAG, "Native.load " + frameworkFile.getAbsolutePath());
            library = Native.load(frameworkFile.getAbsolutePath(), MaaFrameworkLibrary.class);
            File agentClientFile = new File(runtimeLibDir, "libMaaAgentClient.so");
            if (agentClientFile.exists()) {
                Log.i(TAG, "Native.load " + agentClientFile.getAbsolutePath());
                agentClientLibrary = Native.load(agentClientFile.getAbsolutePath(), MaaAgentClientLibrary.class);
            }
            loaded = true;
            Log.i(TAG, "libraries loaded");
        }
    }

    private static void setTempEnv(String key, String value) {
        try {
            Os.setenv(key, value, true);
        } catch (Throwable error) {
            Log.w(TAG, "Failed to set env " + key, error);
        }
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            throw new IllegalStateException("failed to delete " + file);
        }
    }

    private void bundleResource(File dir) {
        Log.i(TAG, "bundle resource " + dir.getAbsolutePath());
        waitResourceSuccess(library.MaaResourcePostBundle(resource, dir.getAbsolutePath()), "load resource bundle: " + dir);
    }

    private static Pointer requireHandle(Pointer pointer, String action) {
        if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
            throw new IllegalStateException("failed to " + action);
        }
        return pointer;
    }

    private void waitResourceSuccess(long id, String action) {
        int status = library.MaaResourceWait(resource, id);
        if (status != STATUS_SUCCEEDED) {
            throw new IllegalStateException("failed to " + action + ", status=" + status);
        }
    }

    private void waitControllerSuccess(long id, String action) {
        int status = library.MaaControllerWait(controller, id);
        if (status != STATUS_SUCCEEDED) {
            throw new IllegalStateException("failed to " + action + ", status=" + status);
        }
    }

    private static void checkTrue(byte value, String action) {
        if (value == 0) {
            throw new IllegalStateException("failed to " + action);
        }
    }

    private void attachTaskerSinks() {
        if (tasker == null) {
            return;
        }
        detachTaskerSinks();
        taskerSinkId = library.MaaTaskerAddSink(tasker, taskerEventCallback, Pointer.NULL);
        contextSinkId = library.MaaTaskerAddContextSink(tasker, contextEventCallback, Pointer.NULL);
        Log.i(TAG, "attached tasker sinks: taskerSinkId=" + taskerSinkId + ", contextSinkId=" + contextSinkId);
    }

    private void attachControllerSink() {
        if (controller == null) {
            return;
        }
        detachControllerSink();
        controllerSinkId = library.MaaControllerAddSink(controller, controllerEventCallback, Pointer.NULL);
        Log.i(TAG, "attached controller sink: controllerSinkId=" + controllerSinkId);
    }

    private void detachTaskerSinks() {
        if (tasker == null) {
            taskerSinkId = 0L;
            contextSinkId = 0L;
            return;
        }
        if (taskerSinkId > 0L) {
            try {
                library.MaaTaskerRemoveSink(tasker, taskerSinkId);
            } catch (Throwable ignored) {
            }
            taskerSinkId = 0L;
        }
        if (contextSinkId > 0L) {
            try {
                library.MaaTaskerRemoveContextSink(tasker, contextSinkId);
            } catch (Throwable ignored) {
            }
            contextSinkId = 0L;
        }
    }

    private void detachControllerSink() {
        if (controller == null) {
            controllerSinkId = 0L;
            return;
        }
        if (controllerSinkId > 0L) {
            try {
                library.MaaControllerRemoveSink(controller, controllerSinkId);
            } catch (Throwable ignored) {
            }
            controllerSinkId = 0L;
        }
    }

    private long onMaaEvent(Pointer handle, Pointer messagePtr, Pointer detailsPtr, Pointer transArg) {
        String message = pointerToString(messagePtr);
        String details = pointerToString(detailsPtr);
        recordEvent(message, details);
        return 0L;
    }

    private String pointerToString(Pointer pointer) {
        if (pointer == null) {
            return "";
        }
        return pointer.getString(0);
    }

    private void recordEvent(String message, String detailsJson) {
        String compact = compactEvent(message, detailsJson);
        synchronized (eventTraceLock) {
            if (eventTrace.size() >= 160) {
                eventTrace.removeFirst();
            }
            eventTrace.addLast(compact);
        }
        Log.i(TAG, "event " + compact);
    }

    private void clearEventTrace() {
        synchronized (eventTraceLock) {
            eventTrace.clear();
        }
    }

    private String summarizeRecentEvents() {
        synchronized (eventTraceLock) {
            if (eventTrace.isEmpty()) {
                return "";
            }
            StringBuilder summary = new StringBuilder();
            int start = Math.max(0, eventTrace.size() - 12);
            int index = 0;
            for (String event : eventTrace) {
                if (index++ < start) {
                    continue;
                }
                if (summary.length() > 0) {
                    summary.append(" | ");
                }
                summary.append(event);
            }
            return summary.toString();
        }
    }

    private String compactEvent(String message, String detailsJson) {
        StringBuilder event = new StringBuilder(message == null ? "" : message);
        if (detailsJson == null || detailsJson.isBlank()) {
            return event.toString();
        }
        try {
            JSONObject details = new JSONObject(detailsJson);
            appendJsonField(event, details, "entry");
            appendJsonField(event, details, "name");
            appendJsonField(event, details, "action");
            appendJsonField(event, details, "uuid");
            appendJsonField(event, details, "ctrl_id");
            appendJsonField(event, details, "task_id");
            appendJsonField(event, details, "node_id");
            appendJsonField(event, details, "reco_id");
            appendJsonField(event, details, "action_id");
            appendJsonField(event, details, "param");
            appendJsonField(event, details, "info");
            appendJsonField(event, details, "list");
            appendJsonField(event, details, "focus");
            appendRecognitionSummary(event, message, details);
            appendActionSummary(event, message, details);
        } catch (Throwable ignored) {
            String compactRaw = detailsJson.replace('\n', ' ').trim();
            if (!compactRaw.isEmpty()) {
                event.append(" {").append(truncate(compactRaw, 180)).append("}");
            }
        }
        return event.toString();
    }

    private void appendRecognitionSummary(StringBuilder event, String message, JSONObject details) {
        if (message == null || !message.startsWith("Node.Recognition")) {
            return;
        }
        long recoId = details.optLong("reco_id", 0L);
        if (recoId <= 0L) {
            return;
        }
        String summary = describeRecognition(recoId);
        if (!summary.isBlank()) {
            event.append(' ').append(summary);
        }
    }

    private void appendActionSummary(StringBuilder event, String message, JSONObject details) {
        if (message == null || !message.startsWith("Node.Action")) {
            return;
        }
        long actionId = details.optLong("action_id", 0L);
        if (actionId <= 0L) {
            return;
        }
        String summary = describeAction(actionId);
        if (!summary.isBlank()) {
            event.append(' ').append(summary);
        }
    }

    private void appendJsonField(StringBuilder builder, JSONObject details, String key) {
        if (!details.has(key)) {
            return;
        }
        Object value = details.opt(key);
        if (value == null) {
            return;
        }
        builder.append(' ').append(key).append('=').append(truncate(String.valueOf(value), 120));
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private String buildFailureMessage(int status, String diagnostic, String eventSummary) {
        StringBuilder message = new StringBuilder("task failed with status=").append(status);
        if (diagnostic != null && !diagnostic.isBlank()) {
            message.append(" (").append(diagnostic).append(')');
        }
        if (eventSummary != null && !eventSummary.isBlank()) {
            message.append(" [events: ").append(eventSummary).append(']');
        }
        return message.toString();
    }

    private void connectAgentIfNeeded() throws IOException {
        if (agentClient != null && agentClientLibrary != null && agentClientLibrary.MaaAgentClientConnected(agentClient) != 0) {
            return;
        }
        if (runtimeRootDir == null || agentClientLibrary == null) {
            return;
        }

        RuntimeAgentResolver.AgentRuntime agentRuntime = RuntimeAgentResolver.resolve(runtimeRootDir);
        if (agentRuntime == null) {
            return;
        }
        if (!agentRuntime.isRunnable()) {
            throw new IllegalStateException(agentRuntime.getDetail());
        }

        disconnectAgent();

        String configuredIdentifier = agentRuntime.getIdentifier();
        String identifier = configuredIdentifier == null || configuredIdentifier.isBlank()
                ? "maaframework-android-" + UUID.randomUUID()
                : configuredIdentifier;
        Pointer identifierBuffer = library.MaaStringBufferCreate();
        if (identifierBuffer == null || Pointer.nativeValue(identifierBuffer) == 0L) {
            throw new IllegalStateException("failed to create identifier buffer");
        }
        try {
            checkTrue(library.MaaStringBufferSet(identifierBuffer, identifier), "set agent identifier");
            agentClient = requireHandle(agentClientLibrary.MaaAgentClientCreateV2(identifierBuffer), "create agent client");
        } finally {
            library.MaaStringBufferDestroy(identifierBuffer);
        }

        checkTrue(agentClientLibrary.MaaAgentClientBindResource(agentClient, resource), "bind resource to agent client");
        startAgentRuntime(agentRuntime, identifier);
        checkTrue(agentClientLibrary.MaaAgentClientSetTimeout(agentClient, 20_000L), "set agent timeout");

        long deadline = System.currentTimeMillis() + 20_000L;
        while (true) {
            if (agentClientLibrary.MaaAgentClientConnect(agentClient) != 0) {
                Log.i(TAG, "agent client connected: " + agentRuntime.getKind());
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("failed to connect agent client");
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("agent client connect interrupted", error);
            }
        }
    }

    private void disconnectAgent() {
        if (agentClient != null && agentClientLibrary != null) {
            try {
                agentClientLibrary.MaaAgentClientDisconnect(agentClient);
            } catch (Throwable ignored) {
            }
            try {
                agentClientLibrary.MaaAgentClientDestroy(agentClient);
            } catch (Throwable ignored) {
            }
            agentClient = null;
        }

        if (agentProcess != null) {
            agentProcess.destroy();
            try {
                agentProcess.waitFor();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            agentProcess = null;
        }
    }

    private void startAgentRuntime(RuntimeAgentResolver.AgentRuntime agentRuntime, String identifier) throws IOException {
        File logsDir = new File(runtimeRootDir, "logs");
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            throw new IOException("failed to create logs dir: " + logsDir);
        }
        File logFile = new File(logsDir, agentRuntime.getLogFileName());

        ProcessBuilder builder = new ProcessBuilder(agentRuntime.buildCommand(identifier));
        builder.directory(agentRuntime.getWorkingDirectory());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

        java.util.Map<String, String> environment = builder.environment();
        environment.put("LD_LIBRARY_PATH", buildAgentLibraryPath(agentRuntime));
        environment.putAll(buildPythonEnv(agentRuntime));
        environment.putAll(buildPiEnv());
        environment.put("MAAEND_GO_LOG_LEVEL", normalizeGoServiceLogLevel(logLevel));
        environment.put("MAA_AGENT_LOG_LEVEL", normalizeGoServiceLogLevel(logLevel));

        agentProcess = builder.start();
        Log.i(TAG, "started agent process: " + agentRuntime.getKind() + " " + agentRuntime.getDisplayPath());
    }

    private String buildAgentLibraryPath(RuntimeAgentResolver.AgentRuntime agentRuntime) {
        ArrayList<String> paths = new ArrayList<>();
        addDirectoryIfExists(paths, new File(runtimeRootDir, "maafw"));

        File pythonHome = findPythonHome(agentRuntime);
        if (pythonHome != null) {
            addDirectoryIfExists(paths, new File(pythonHome, "lib"));
        }

        String inherited = System.getenv("LD_LIBRARY_PATH");
        if (inherited != null && !inherited.isBlank()) {
            paths.add(inherited);
        }
        return String.join(File.pathSeparator, paths);
    }

    private java.util.Map<String, String> buildPythonEnv(RuntimeAgentResolver.AgentRuntime agentRuntime) {
        java.util.HashMap<String, String> env = new java.util.HashMap<>();
        if (!"python".equals(agentRuntime.getKind())) {
            return env;
        }

        File pythonHome = findPythonHome(agentRuntime);
        if (pythonHome == null) {
            return env;
        }

        ArrayList<String> pythonPath = new ArrayList<>();
        addDirectoryIfExists(pythonPath, runtimeRootDir);
        addDirectoryIfExists(pythonPath, new File(runtimeRootDir, "agent"));
        addDirectoryIfExists(pythonPath, findPythonSitePackages(pythonHome));

        env.put("PYTHONHOME", pythonHome.getAbsolutePath());
        env.put("PYTHONNOUSERSITE", "1");
        env.put("PYTHONUNBUFFERED", "1");
        env.put("PYTHONUTF8", "1");
        addDirectoryEnv(env, "MAAFW_BINARY_PATH", new File(runtimeRootDir, "maafw"));
        if (!pythonPath.isEmpty()) {
            env.put("PYTHONPATH", String.join(File.pathSeparator, pythonPath));
        }
        return env;
    }

    private File findPythonHome(RuntimeAgentResolver.AgentRuntime agentRuntime) {
        File executable = agentRuntime.getExecutableFile();
        if (executable != null) {
            File parent = executable.getParentFile();
            if (parent != null && "bin".equals(parent.getName())) {
                File home = parent.getParentFile();
                if (home != null && home.isDirectory()) {
                    return home;
                }
            }
            if (parent != null && parent.isDirectory()) {
                return parent;
            }
        }

        File bundled = new File(runtimeRootDir, "python");
        return bundled.isDirectory() ? bundled : null;
    }

    private File findPythonSitePackages(File pythonHome) {
        File lib = new File(pythonHome, "lib");
        File[] children = lib.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (!child.isDirectory() || !child.getName().startsWith("python")) {
                continue;
            }
            File sitePackages = new File(child, "site-packages");
            if (sitePackages.isDirectory()) {
                return sitePackages;
            }
        }
        return null;
    }

    private static void addDirectoryIfExists(List<String> paths, File directory) {
        if (directory != null && directory.isDirectory()) {
            paths.add(directory.getAbsolutePath());
        }
    }

    private static void addDirectoryEnv(java.util.Map<String, String> env, String key, File directory) {
        if (directory != null && directory.isDirectory()) {
            env.put(key, directory.getAbsolutePath());
        }
    }

    private void applyFrameworkLogLevel() {
        IntByReference levelValue = new IntByReference(mapFrameworkStdoutLevel(logLevel));
        boolean ok = library.MaaGlobalSetOption(
                MAA_GLOBAL_OPTION_STDOUT_LEVEL,
                levelValue.getPointer(),
                Integer.BYTES
        );
        if (!ok) {
            Log.w(TAG, "Failed to apply framework stdout log level: " + logLevel);
        } else {
            Log.i(TAG, "Applied framework stdout log level: " + logLevel);
        }
    }

    private static int mapFrameworkStdoutLevel(String level) {
        return switch (normalizeLogLevel(level)) {
            case "error" -> MAA_LOG_LEVEL_ERROR;
            case "warn" -> MAA_LOG_LEVEL_WARN;
            case "debug" -> MAA_LOG_LEVEL_DEBUG;
            default -> MAA_LOG_LEVEL_INFO;
        };
    }

    private static String normalizeGoServiceLogLevel(String level) {
        return switch (normalizeLogLevel(level)) {
            case "error" -> "error";
            case "warn" -> "warn";
            case "debug" -> "debug";
            default -> "info";
        };
    }

    private static String normalizeLogLevel(String level) {
        if (level == null) {
            return "info";
        }
        String normalized = level.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "error", "warn", "info", "debug" -> normalized;
            default -> "info";
        };
    }

    private java.util.Map<String, String> buildPiEnv() {
        java.util.HashMap<String, String> env = new java.util.HashMap<>();
        env.put("PI_INTERFACE_VERSION", "v2.5.0");
        env.put("PI_CLIENT_NAME", "MaaFrameworkAndroid");
        env.put("PI_CLIENT_VERSION", "dev");
        env.put("PI_CLIENT_LANGUAGE", "zh_cn");
        env.put("PI_CLIENT_MAAFW_VERSION", version());
        env.put("PI_VERSION", "dev");
        env.put("PI_CONTROLLER", buildPiControllerJson());
        env.put("PI_RESOURCE", buildPiResourceJson());
        return env;
    }

    private String buildPiControllerJson() {
        try {
            JSONObject controller = new JSONObject();
            controller.put("name", "ADB");
            controller.put("label", "ADB");
            controller.put("type", "Adb");
            if (!attachResourcePaths.isEmpty()) {
                org.json.JSONArray attachResourcePathArray = new org.json.JSONArray();
                for (String path : attachResourcePaths) {
                    attachResourcePathArray.put(path);
                }
                controller.put("attach_resource_path", attachResourcePathArray);
            }
            return controller.toString();
        } catch (Exception error) {
            throw new IllegalStateException("failed to build PI_CONTROLLER", error);
        }
    }

    private String buildPiResourceJson() {
        try {
            JSONObject resource = new JSONObject();
            resource.put("name", resourceName);
            resource.put("label", resourceLabel);
            org.json.JSONArray paths = new org.json.JSONArray();
            for (String path : resourcePaths) {
                paths.put(path);
            }
            resource.put("path", paths);
            return resource.toString();
        } catch (Exception error) {
            throw new IllegalStateException("failed to build PI_RESOURCE", error);
        }
    }

    private static List<String> normalizeResourcePaths(List<String> rawPaths) {
        List<String> normalized = new ArrayList<>();
        if (rawPaths != null) {
            for (String rawPath : rawPaths) {
                if (rawPath == null || rawPath.isBlank()) {
                    continue;
                }
                String normalizedPath = rawPath.startsWith("./") ? rawPath : "./" + rawPath;
                normalized.add(normalizedPath);
            }
        }
        if (normalized.isEmpty()) {
            normalized.add("./resource");
        }
        return normalized;
    }

    private static List<String> normalizeAttachResourcePaths(List<String> rawPaths) {
        List<String> normalized = new ArrayList<>();
        if (rawPaths != null) {
            for (String rawPath : rawPaths) {
                if (rawPath == null || rawPath.isBlank()) {
                    continue;
                }
                String normalizedPath = rawPath.startsWith("./") ? rawPath : "./" + rawPath;
                normalized.add(normalizedPath);
            }
        }
        return normalized;
    }

    private static String normalizeRuntimeRelativePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "resource";
        }
        if (rawPath.startsWith("./")) {
            return rawPath.substring(2);
        }
        return rawPath;
    }

    private static String buildControllerConfig(File runtimeRoot, Context context) {
        int displayId = VirtualDisplayManager.INSTANCE.getDisplayId();
        int[] resolution = displayId != DefaultDisplayConfig.DISPLAY_NONE
                ? new int[] { DefaultDisplayConfig.WIDTH, DefaultDisplayConfig.HEIGHT }
                : queryResolution(context);
        int effectiveDisplayId = displayId == DefaultDisplayConfig.DISPLAY_NONE ? 0 : displayId;
        boolean usingVirtualDisplay = displayId != DefaultDisplayConfig.DISPLAY_NONE;
        Log.i(
                TAG,
                "buildControllerConfig displayId=" + effectiveDisplayId
                        + " virtualDisplay=" + usingVirtualDisplay
                        + " resolution=" + resolution[0] + "x" + resolution[1]
        );
        try {
            JSONObject root = new JSONObject();
            root.put("library_path", new File(context.getApplicationInfo().nativeLibraryDir, "libbridge.so").getAbsolutePath());
            RuntimeAgentResolver.AgentRuntime agentRuntime = RuntimeAgentResolver.resolve(runtimeRoot);
            File agentPath = agentRuntime == null ? null : agentRuntime.getAgentPathForConfig();
            if (agentPath != null && agentPath.isFile()) {
                root.put("agent_path", agentPath.getAbsolutePath());
                root.put("root_path", runtimeRoot.getAbsolutePath());
                root.put("client_type", "MaaFrameworkAndroid");
            }
            JSONObject screen = new JSONObject();
            screen.put("width", resolution[0]);
            screen.put("height", resolution[1]);
            root.put("screen_resolution", screen);
            root.put("display_id", effectiveDisplayId);
            root.put("force_stop", false);
            return root.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to build controller config", e);
        }
    }

    private static int[] queryResolution(Context context) {
        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        if (context != null && width > 0 && height > 0) {
            return new int[] { width, height };
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("invalid display resolution");
        }
        return new int[] { width, height };
    }

    private String describeTask(long taskId) {
        if (tasker == null) {
            return "";
        }
        Pointer entryBuffer = library.MaaStringBufferCreate();
        if (entryBuffer == null || Pointer.nativeValue(entryBuffer) == 0L) {
            return "";
        }
        try {
            LongByReference nodeCountRef = new LongByReference();
            if (!library.MaaTaskerGetTaskDetail(tasker, taskId, Pointer.NULL, Pointer.NULL, nodeCountRef, null)) {
                return "";
            }

            long nodeCount = nodeCountRef.getValue();
            Memory nodeIds = nodeCount > 0 ? new Memory(nodeCount * Native.LONG_SIZE) : null;
            IntByReference statusRef = new IntByReference();
            if (!library.MaaTaskerGetTaskDetail(tasker, taskId, entryBuffer, nodeIds, nodeCountRef, statusRef)) {
                return "";
            }

            StringBuilder summary = new StringBuilder();
            summary.append("entry=").append(library.MaaStringBufferGet(entryBuffer));
            summary.append(", taskStatus=").append(statusRef.getValue());
            summary.append(", nodeCount=").append(nodeCountRef.getValue());

            if (nodeCount > 0 && nodeIds != null) {
                summary.append(", lastNodes=[");
                int start = Math.max(0, (int) nodeCount - 8);
                for (int i = start; i < (int) nodeCount; i++) {
                    if (i > start) {
                        summary.append("; ");
                    }
                    summary.append(describeNode(nodeIds.getLong((long) i * Native.LONG_SIZE)));
                }
                summary.append("]");
            }
            return summary.toString();
        } finally {
            library.MaaStringBufferDestroy(entryBuffer);
        }
    }

    private String describeNode(long nodeId) {
        Pointer nodeNameBuffer = library.MaaStringBufferCreate();
        if (nodeNameBuffer == null || Pointer.nativeValue(nodeNameBuffer) == 0L) {
            return Long.toString(nodeId);
        }
        try {
            Memory recognitionId = new Memory(Native.LONG_SIZE);
            Memory actionId = new Memory(Native.LONG_SIZE);
            Memory completed = new Memory(1);
            if (!library.MaaTaskerGetNodeDetail(tasker, nodeId, nodeNameBuffer, recognitionId, actionId, completed)) {
                return "nodeId=" + nodeId;
            }
            return library.MaaStringBufferGet(nodeNameBuffer) + (completed.getByte(0) != 0 ? ":done" : ":pending");
        } finally {
            library.MaaStringBufferDestroy(nodeNameBuffer);
        }
    }

    private String describeRecognition(long recoId) {
        if (tasker == null || recoId <= 0L) {
            return "";
        }
        Pointer nodeNameBuffer = library.MaaStringBufferCreate();
        Pointer algorithmBuffer = library.MaaStringBufferCreate();
        Pointer detailBuffer = library.MaaStringBufferCreate();
        Pointer rectBuffer = library.MaaRectCreate();
        if (nodeNameBuffer == null || algorithmBuffer == null || detailBuffer == null || rectBuffer == null) {
            destroyIfNeeded(nodeNameBuffer);
            destroyIfNeeded(algorithmBuffer);
            destroyIfNeeded(detailBuffer);
            destroyRectIfNeeded(rectBuffer);
            return "";
        }
        try {
            Memory hit = new Memory(1);
            if (!library.MaaTaskerGetRecognitionDetail(
                    tasker,
                    recoId,
                    nodeNameBuffer,
                    algorithmBuffer,
                    hit,
                    rectBuffer,
                    detailBuffer,
                    Pointer.NULL,
                    Pointer.NULL
            )) {
                return "";
            }
            StringBuilder summary = new StringBuilder();
            String recognitionName = library.MaaStringBufferGet(nodeNameBuffer);
            String algorithmName = library.MaaStringBufferGet(algorithmBuffer);
            summary.append("recoDetail(")
                .append("name=").append(recognitionName)
                .append(", algo=").append(algorithmName)
                .append(", hit=").append(hit.getByte(0) != 0)
                .append(", box=[")
                .append(library.MaaRectGetX(rectBuffer)).append(',')
                .append(library.MaaRectGetY(rectBuffer)).append(',')
                .append(library.MaaRectGetW(rectBuffer)).append(',')
                .append(library.MaaRectGetH(rectBuffer)).append(']');
            String detailJson = library.MaaStringBufferGet(detailBuffer);
            if (detailJson != null && !detailJson.isBlank() && !"{}".equals(detailJson)) {
                summary.append(", detail=").append(shouldLogFullRecognitionDetail(recognitionName)
                    ? detailJson
                    : truncate(detailJson, 220));
            }
            summary.append(')');
            return summary.toString();
        } finally {
            destroyIfNeeded(nodeNameBuffer);
            destroyIfNeeded(algorithmBuffer);
            destroyIfNeeded(detailBuffer);
            destroyRectIfNeeded(rectBuffer);
        }
    }

    private String describeAction(long actionId) {
        if (tasker == null || actionId <= 0L) {
            return "";
        }
        Pointer nodeNameBuffer = library.MaaStringBufferCreate();
        Pointer actionNameBuffer = library.MaaStringBufferCreate();
        Pointer detailBuffer = library.MaaStringBufferCreate();
        Pointer rectBuffer = library.MaaRectCreate();
        if (nodeNameBuffer == null || actionNameBuffer == null || detailBuffer == null || rectBuffer == null) {
            destroyIfNeeded(nodeNameBuffer);
            destroyIfNeeded(actionNameBuffer);
            destroyIfNeeded(detailBuffer);
            destroyRectIfNeeded(rectBuffer);
            return "";
        }
        try {
            Memory success = new Memory(1);
            if (!library.MaaTaskerGetActionDetail(
                    tasker,
                    actionId,
                    nodeNameBuffer,
                    actionNameBuffer,
                    rectBuffer,
                    success,
                    detailBuffer
            )) {
                return "";
            }
            StringBuilder summary = new StringBuilder();
            summary.append("actionDetail(")
                .append("name=").append(library.MaaStringBufferGet(nodeNameBuffer))
                .append(", action=").append(library.MaaStringBufferGet(actionNameBuffer))
                .append(", success=").append(success.getByte(0) != 0)
                .append(", box=[")
                .append(library.MaaRectGetX(rectBuffer)).append(',')
                .append(library.MaaRectGetY(rectBuffer)).append(',')
                .append(library.MaaRectGetW(rectBuffer)).append(',')
                .append(library.MaaRectGetH(rectBuffer)).append(']');
            String detailJson = library.MaaStringBufferGet(detailBuffer);
            if (detailJson != null && !detailJson.isBlank() && !"{}".equals(detailJson)) {
                summary.append(", detail=").append(shouldLogFullActionDetail(library.MaaStringBufferGet(nodeNameBuffer))
                    ? detailJson
                    : truncate(detailJson, 220));
            }
            summary.append(')');
            return summary.toString();
        } finally {
            destroyIfNeeded(nodeNameBuffer);
            destroyIfNeeded(actionNameBuffer);
            destroyIfNeeded(detailBuffer);
            destroyRectIfNeeded(rectBuffer);
        }
    }

    private void destroyIfNeeded(Pointer buffer) {
        if (buffer != null && Pointer.nativeValue(buffer) != 0L) {
            library.MaaStringBufferDestroy(buffer);
        }
    }

    private void destroyRectIfNeeded(Pointer rect) {
        if (rect != null && Pointer.nativeValue(rect) != 0L) {
            library.MaaRectDestroy(rect);
        }
    }

    private boolean shouldLogFullRecognitionDetail(String recognitionName) {
        return "__ScenePrivateWorldEnterMapAny".equals(recognitionName)
            || "__ScenePrivateAnyEnterMapSuccess".equals(recognitionName);
    }

    private boolean shouldLogFullActionDetail(String actionName) {
        return "__ScenePrivateWorldEnterMapAny".equals(actionName);
    }

    public static final class RunResult {
        public final boolean success;
        public final String message;

        public RunResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public interface MaaFrameworkLibrary extends Library {
        String MaaVersion();

        boolean MaaGlobalSetOption(int key, Pointer value, long valSize);

        Pointer MaaResourceCreate();

        void MaaResourceDestroy(Pointer resource);

        long MaaResourcePostBundle(Pointer resource, String path);

        int MaaResourceWait(Pointer resource, long id);

        Pointer MaaAndroidNativeControllerCreate(String configJson);

        void MaaControllerDestroy(Pointer controller);

        long MaaControllerPostConnection(Pointer controller);

        int MaaControllerWait(Pointer controller, long id);

        long MaaControllerAddSink(Pointer controller, MaaEventCallback sink, Pointer transArg);

        void MaaControllerRemoveSink(Pointer controller, long sinkId);

        Pointer MaaTaskerCreate();

        void MaaTaskerDestroy(Pointer tasker);

        byte MaaTaskerBindResource(Pointer tasker, Pointer resource);

        byte MaaTaskerBindController(Pointer tasker, Pointer controller);

        byte MaaTaskerInited(Pointer tasker);

        long MaaTaskerPostTask(Pointer tasker, String entry, String pipelineOverride);

        int MaaTaskerWait(Pointer tasker, long id);

        long MaaTaskerPostStop(Pointer tasker);

        long MaaTaskerAddSink(Pointer tasker, MaaEventCallback sink, Pointer transArg);

        void MaaTaskerRemoveSink(Pointer tasker, long sinkId);

        long MaaTaskerAddContextSink(Pointer tasker, MaaEventCallback sink, Pointer transArg);

        void MaaTaskerRemoveContextSink(Pointer tasker, long sinkId);

        boolean MaaTaskerGetTaskDetail(Pointer tasker, long taskId, Pointer entry, Pointer nodeIdList, LongByReference nodeIdListSize, IntByReference status);

        boolean MaaTaskerGetNodeDetail(Pointer tasker, long nodeId, Pointer nodeName, Pointer recoId, Pointer actionId, Pointer completed);

        boolean MaaTaskerGetRecognitionDetail(Pointer tasker, long recoId, Pointer nodeName, Pointer algorithm, Pointer hit, Pointer box, Pointer detailJson, Pointer raw, Pointer draws);

        boolean MaaTaskerGetActionDetail(Pointer tasker, long actionId, Pointer nodeName, Pointer action, Pointer box, Pointer success, Pointer detailJson);

        Pointer MaaStringBufferCreate();

        void MaaStringBufferDestroy(Pointer handle);

        byte MaaStringBufferSet(Pointer handle, String value);

        String MaaStringBufferGet(Pointer handle);

        Pointer MaaRectCreate();

        void MaaRectDestroy(Pointer handle);

        int MaaRectGetX(Pointer handle);

        int MaaRectGetY(Pointer handle);

        int MaaRectGetW(Pointer handle);

        int MaaRectGetH(Pointer handle);
    }

    private static final int MAA_GLOBAL_OPTION_STDOUT_LEVEL = 4;
    private static final int MAA_LOG_LEVEL_ERROR = 2;
    private static final int MAA_LOG_LEVEL_WARN = 3;
    private static final int MAA_LOG_LEVEL_INFO = 4;
    private static final int MAA_LOG_LEVEL_DEBUG = 5;

    public interface MaaEventCallback extends Callback {
        long invoke(Pointer handle, Pointer message, Pointer detailsJson, Pointer transArg);
    }

    public interface MaaAgentClientLibrary extends Library {
        Pointer MaaAgentClientCreateV2(Pointer identifierBuffer);

        void MaaAgentClientDestroy(Pointer client);

        byte MaaAgentClientBindResource(Pointer client, Pointer resource);

        byte MaaAgentClientConnect(Pointer client);

        byte MaaAgentClientDisconnect(Pointer client);

        byte MaaAgentClientConnected(Pointer client);

        byte MaaAgentClientSetTimeout(Pointer client, long milliseconds);
    }
}
