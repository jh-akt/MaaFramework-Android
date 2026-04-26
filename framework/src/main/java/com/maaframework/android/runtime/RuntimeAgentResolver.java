package com.maaframework.android.runtime;

import com.maaframework.android.catalog.JsonWithComments;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class RuntimeAgentResolver {
    private RuntimeAgentResolver() {
    }

    public static final class AgentRuntime {
        private final String kind;
        private final String source;
        private final String executableCommand;
        private final File executableFile;
        private final File workingDirectory;
        private final List<String> arguments;
        private final String logFileName;
        private final boolean runnable;
        private final String detail;
        private final String identifier;
        private final boolean appendIdentifier;

        private AgentRuntime(
                String kind,
                String source,
                String executableCommand,
                File executableFile,
                File workingDirectory,
                List<String> arguments,
                String logFileName,
                boolean runnable,
                String detail,
                String identifier,
                boolean appendIdentifier
        ) {
            this.kind = kind;
            this.source = source;
            this.executableCommand = executableCommand;
            this.executableFile = executableFile;
            this.workingDirectory = workingDirectory;
            this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
            this.logFileName = logFileName;
            this.runnable = runnable;
            this.detail = detail;
            this.identifier = identifier;
            this.appendIdentifier = appendIdentifier;
        }

        public String getKind() {
            return kind;
        }

        public String getSource() {
            return source;
        }

        public String getExecutableCommand() {
            return executableCommand;
        }

        public File getExecutableFile() {
            return executableFile;
        }

        public File getWorkingDirectory() {
            return workingDirectory;
        }

        public List<String> getArguments() {
            return arguments;
        }

        public String getLogFileName() {
            return logFileName;
        }

        public boolean isRunnable() {
            return runnable;
        }

        public String getDetail() {
            return detail;
        }

        public String getIdentifier() {
            return identifier;
        }

        public File getAgentPathForConfig() {
            return executableFile != null && executableFile.isFile() ? executableFile : null;
        }

        public String getDisplayPath() {
            if (executableFile != null && executableFile.isFile()) {
                return executableFile.getAbsolutePath();
            }
            return executableCommand;
        }

        public List<String> buildCommand(String runtimeIdentifier) {
            ArrayList<String> command = new ArrayList<>();
            command.add(executableCommand);
            command.addAll(arguments);
            if (appendIdentifier) {
                command.add(runtimeIdentifier);
            }
            return command;
        }
    }

    public static List<AgentRuntime> detect(File runtimeRoot) {
        ArrayList<AgentRuntime> agents = new ArrayList<>();
        AgentRuntime interfaceAgent = detectInterfaceAgent(runtimeRoot);
        if (interfaceAgent != null) {
            agents.add(interfaceAgent);
        }
        addIfDistinct(agents, detectKnownPathAgent(runtimeRoot, "go-service", "legacy-go-service", "agent/go-service", "go-service.log"));
        addIfDistinct(agents, detectKnownPathAgent(runtimeRoot, "cpp-agent", "known-cpp-agent", "agent/cpp-algo", "cpp-algo.log"));
        addIfDistinct(agents, detectKnownPathAgent(runtimeRoot, "agent-service", "known-agent-service", "agent/agent-service", "agent-service.log"));
        addIfDistinct(agents, detectKnownPathAgent(runtimeRoot, "agent-service", "known-agent-service", "agent/maa-agent-service", "maa-agent-service.log"));
        return agents;
    }

    public static AgentRuntime resolve(File runtimeRoot) {
        List<AgentRuntime> agents = detect(runtimeRoot);
        if (agents.isEmpty()) {
            return null;
        }
        for (AgentRuntime agent : agents) {
            if ("project-interface".equals(agent.getSource())) {
                return agent;
            }
        }
        for (AgentRuntime agent : agents) {
            if (agent.isRunnable()) {
                return agent;
            }
        }
        return agents.get(0);
    }

    private static AgentRuntime detectInterfaceAgent(File runtimeRoot) {
        File interfaceFile = new File(runtimeRoot, "interface.json");
        if (!interfaceFile.isFile()) {
            return null;
        }
        try {
            String text = new String(Files.readAllBytes(interfaceFile.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(JsonWithComments.INSTANCE.stripLineComments(text));
            JSONObject agent = root.optJSONObject("agent");
            if (agent == null) {
                return null;
            }
            String childExec = agent.optString("child_exec", "").trim();
            if (childExec.isEmpty()) {
                return null;
            }
            JSONArray rawArgs = agent.optJSONArray("child_args");
            List<String> arguments = normalizeArguments(runtimeRoot, rawArgs);
            String configuredIdentifier = optNonBlank(agent, "identifier");
            boolean appendIdentifier = configuredIdentifier == null;
            return buildInterfaceAgent(runtimeRoot, childExec, arguments, configuredIdentifier, appendIdentifier);
        } catch (Throwable error) {
            return new AgentRuntime(
                    "project-interface",
                    "project-interface",
                    "interface.json",
                    null,
                    runtimeRoot,
                    Collections.emptyList(),
                    "agent.log",
                    false,
                    "Failed to parse interface.json agent config: " + error.getMessage(),
                    null,
                    false
            );
        }
    }

    private static AgentRuntime buildInterfaceAgent(
            File runtimeRoot,
            String childExec,
            List<String> arguments,
            String configuredIdentifier,
            boolean appendIdentifier
    ) {
        String normalizedExec = childExec.replace('\\', '/').trim();
        String lowerExec = new File(normalizedExec).getName().toLowerCase(Locale.ROOT);
        boolean python = lowerExec.equals("python") || lowerExec.equals("python3") || lowerExec.equals("python.exe");
        File executableFile = python ? findBundledPython(runtimeRoot) : resolveExecutable(runtimeRoot, normalizedExec);
        String executableCommand = executableFile != null ? executableFile.getAbsolutePath() : normalizedExec;
        String kind = python ? "python" : inferKind(normalizedExec);
        String logFileName = logFileNameFor(kind, normalizedExec);

        boolean scriptMissing = false;
        String missingScript = null;
        if (python) {
            File script = findFirstPythonScript(runtimeRoot, arguments);
            scriptMissing = script == null || !script.isFile();
            missingScript = script == null ? "agent script" : script.getPath();
        }

        boolean runnable;
        String detail;
        if (python && executableFile == null) {
            runnable = false;
            detail = "Python agent detected, but no bundled Python interpreter was found under runtime/python or runtime/agent/python";
        } else if (python && scriptMissing) {
            runnable = false;
            detail = "Python agent detected, but " + missingScript + " was not found";
        } else if (!python && executableFile == null && looksLikeFilePath(normalizedExec)) {
            runnable = false;
            detail = "Agent executable not found: " + normalizedExec;
        } else if (!python && executableFile == null) {
            runnable = false;
            detail = "Agent executable must be bundled on Android: " + normalizedExec;
        } else {
            runnable = true;
            detail = "ok";
        }

        return new AgentRuntime(
                kind,
                "project-interface",
                executableCommand,
                executableFile,
                runtimeRoot,
                arguments,
                logFileName,
                runnable,
                detail,
                configuredIdentifier,
                appendIdentifier
        );
    }

    private static AgentRuntime detectKnownPathAgent(
            File runtimeRoot,
            String kind,
            String source,
            String relativePath,
            String logFileName
    ) {
        File executable = new File(runtimeRoot, relativePath);
        if (!executable.isFile()) {
            return null;
        }
        return new AgentRuntime(
                kind,
                source,
                executable.getAbsolutePath(),
                executable,
                runtimeRoot,
                Collections.emptyList(),
                logFileName,
                true,
                "ok",
                null,
                true
        );
    }

    private static void addIfDistinct(List<AgentRuntime> agents, AgentRuntime candidate) {
        if (candidate == null) {
            return;
        }
        for (AgentRuntime existing : agents) {
            if (existing.getDisplayPath().equals(candidate.getDisplayPath())) {
                return;
            }
        }
        agents.add(candidate);
    }

    private static List<String> normalizeArguments(File runtimeRoot, JSONArray rawArgs) {
        if (rawArgs == null) {
            return Collections.emptyList();
        }
        ArrayList<String> args = new ArrayList<>();
        for (int index = 0; index < rawArgs.length(); index++) {
            String value = rawArgs.optString(index, "");
            if (value.isEmpty()) {
                continue;
            }
            File resolved = resolveRuntimeFile(runtimeRoot, value);
            if (looksLikeFilePath(value) && resolved.isFile()) {
                args.add(resolved.getAbsolutePath());
            } else {
                args.add(value);
            }
        }
        return args;
    }

    private static File findFirstPythonScript(File runtimeRoot, List<String> arguments) {
        for (String argument : arguments) {
            if (!argument.replace('\\', '/').endsWith(".py")) {
                continue;
            }
            File direct = new File(argument);
            if (direct.isFile()) {
                return direct;
            }
            return resolveRuntimeFile(runtimeRoot, argument);
        }
        File fallback = new File(runtimeRoot, "agent/main.py");
        return fallback.isFile() ? fallback : null;
    }

    private static File resolveExecutable(File runtimeRoot, String childExec) {
        File direct = resolveRuntimeFile(runtimeRoot, childExec);
        if (direct.isFile()) {
            return direct;
        }
        if (!looksLikeFilePath(childExec)) {
            File inAgent = new File(runtimeRoot, "agent/" + childExec);
            if (inAgent.isFile()) {
                return inAgent;
            }
        }
        return null;
    }

    private static File findBundledPython(File runtimeRoot) {
        String[] candidates = {
                "agent/python/bin/python3",
                "agent/python/python3",
                "python/bin/python3",
                "python/python3",
                "MaaAgentBinary/python/bin/python3",
                "MaaAgentBinary/python3"
        };
        for (String candidate : candidates) {
            File file = new File(runtimeRoot, candidate);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    private static File resolveRuntimeFile(File runtimeRoot, String rawPath) {
        String path = rawPath.replace('\\', '/').trim();
        File file = new File(path);
        if (file.isAbsolute()) {
            return file;
        }
        File relative = new File(runtimeRoot, path);
        if (relative.exists()) {
            return relative;
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.startsWith("../agent/")) {
            return new File(runtimeRoot, path.substring(3));
        }
        while (path.startsWith("../")) {
            path = path.substring(3);
        }
        return new File(runtimeRoot, path);
    }

    private static boolean looksLikeFilePath(String value) {
        String normalized = value.replace('\\', '/');
        return normalized.startsWith("./")
                || normalized.startsWith("../")
                || normalized.startsWith("/")
                || normalized.contains("/");
    }

    private static String inferKind(String childExec) {
        String name = new File(childExec).getName().toLowerCase(Locale.ROOT);
        if (name.equals("go-service")) {
            return "go-service";
        }
        if (name.equals("cpp-algo")) {
            return "cpp-agent";
        }
        return "agent-service";
    }

    private static String logFileNameFor(String kind, String childExec) {
        if ("python".equals(kind)) {
            return "python-agent.log";
        }
        String name = new File(childExec).getName();
        if (name.isBlank()) {
            name = kind;
        }
        return name.replaceAll("[^A-Za-z0-9._-]", "_") + ".log";
    }

    private static String optNonBlank(JSONObject object, String key) {
        String value = object.optString(key, "").trim();
        return value.isEmpty() ? null : value;
    }
}
