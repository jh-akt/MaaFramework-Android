# Claude Notes

This is the primary agent guidance file for this repository. Read this file before making changes.

## Project Context

`MaaFramework Android` is the reusable Android Root host framework for MAA projects. It provides runtime preparation, Root Runtime startup, task/resource parsing, virtual display preview, touch forwarding, and diagnostics.

The Maa_bbb host app has been split into the sibling project:

```text
/Users/haojiang/Code/
  MaaFramework-Android/
  Maa-bbb-Android/
```

`Maa-bbb-Android` references this repository's `framework/` module as a Gradle subproject. Do not put Maa_bbb app UI or app-specific settings back into this repository unless the user explicitly asks to merge them.

## Current Modules

- `framework/`: Android Library module that exposes MaaFramework Android APIs and Root Runtime implementation.
- `runtime/`: runtime binary staging area consumed by app projects during packaging.
- `tools/`: runtime preparation scripts, Go runner, Android Python launcher, and Python shims.
- `docs/SESSION_SUMMARY.md`: historical extraction and debugging notes.
- `IMPLEMENTATION_OVERVIEW.md`: detailed implementation notes for the framework architecture.

## Before Editing

- Read `docs/SESSION_SUMMARY.md` before deeper runtime changes.
- For architecture or runtime-flow changes, also read `IMPLEMENTATION_OVERVIEW.md`.
- Preserve existing Gradle/Kotlin/Java patterns.
- Keep app-specific changes in `../Maa-bbb-Android`.
- Do not remove or overwrite user changes in the working tree.

## Important Files

Start with these when changing framework behavior:

1. `framework/src/main/java/com/maaframework/android/session/MaaFrameworkSession.kt`
2. `framework/src/main/java/com/maaframework/android/session/MaaRuntimeClient.kt`
3. `framework/src/main/java/com/maaframework/android/root/RootRuntimeConnector.kt`
4. `framework/src/main/java/com/maaframework/android/root/RootRuntimeService.kt`
5. `framework/src/main/java/com/maaframework/android/runtime/RuntimeBootstrapper.kt`
6. `framework/src/main/java/com/maaframework/android/maa/MaaFrameworkBridge.java`
7. `framework/src/main/java/com/maaframework/android/preview/VirtualDisplayManager.kt`
8. `framework/src/main/java/com/maaframework/android/catalog/InterfaceCatalogLoader.kt`
9. `framework/src/main/java/com/maaframework/android/project/MaaProjectManifest.kt`

## Public Host API

The intended high-level integration surface is small:

- `MaaFrameworkAndroid.initialize(context)`: installs framework context and initializes root support.
- `MaaFrameworkSession`: root diagnostics, root permission request, Root Runtime connection lifecycle.
- `MaaRuntimeClient`: host-facing runtime operations such as `prepareRuntime()`, `startRun(RunRequest)`, `stopRun()`, `setMonitorSurface(surface)`, `startWindowedGame(resourceId)`, `getState()`, `readLogChunk(...)`, `exportDiagnostics()`, and touch forwarding.

Prefer improving this API layer over making app code call lower-level AIDL or root internals directly.

## Build Notes

Known working framework verification command:

```bash
JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :framework:testDebugUnitTest
```

To verify the Maa_bbb app, run from `../Maa-bbb-Android`:

```bash
JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug
```

## Runtime And Device Notes

- Target environment: Android 11+, `arm64-v8a`.
- The app process needs executable `su`; `adb root` alone is not enough.
- Runtime files staged in `runtime/` are packaged by host apps, not by the framework library by itself.
- Physical device `382b528f` has previously been verified with app root authorization.
