# Claude Notes

This is the primary agent guidance file for this repository. Read this file before making changes.

## Project Context

`MaaFramework Android` is the reusable Android Root host framework for MAA projects. It provides runtime preparation, Root Runtime startup, project manifest support, `interface.json` catalog/resource parsing, MaaFramework execution, virtual display preview, touch forwarding, diagnostics, and shared Android runtime packaging conventions.

Current sibling host apps:

```text
/Users/haojiang/Code/
  MaaFramework-Android/   # source of truth for reusable framework code
  Maa-bbb-Android/        # Maa_bbb host app, references this repo as submodule
  MaaEnd-Android/         # MaaEnd host app, references this repo as submodule
```

Both host apps mount the submodule's `framework/` directory as Gradle project `:framework`. Do not put Maa_bbb or MaaEnd app UI, settings, manifests, or project-specific behavior back into this repository unless the user explicitly asks to merge them.

Reusable fixes should land here first, then the host app submodule pointers should be updated.

## Current Modules

- `framework/`: Android Library module exposing MaaFramework Android APIs and Root Runtime implementation.
- `runtime/`: Android runtime staging area consumed by host apps during packaging or by release archives.
- `tools/`: runtime preparation scripts, Go runner, Android Python launcher, Python shims, and helper tooling.
- `docs/SESSION_SUMMARY.md`: historical extraction and debugging notes.
- `IMPLEMENTATION_OVERVIEW.md`: detailed implementation notes for the framework architecture.

## Before Editing

- Read `docs/SESSION_SUMMARY.md` before deeper runtime changes.
- For architecture or runtime-flow changes, also read `IMPLEMENTATION_OVERVIEW.md`.
- Preserve existing Gradle/Kotlin/Java patterns.
- Keep app-specific changes in `../Maa-bbb-Android` or `../MaaEnd-Android`.
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
10. `framework/src/main/java/com/maaframework/android/storage/PersistentProjectRepositoryManager.kt`

## Public Host API

The intended high-level integration surface is small:

- `MaaFrameworkAndroid.initialize(context)`: installs framework context and initializes root support.
- `MaaFrameworkSession`: root diagnostics, root permission request, and Root Runtime connection lifecycle.
- `MaaRuntimeClient`: host-facing runtime operations such as `prepareRuntime()`, `startRun(RunRequest)`, `stopRun()`, `setMonitorSurface(surface)`, `startWindowedGame(resourceId)`, `getState()`, `readLogChunk(...)`, `exportDiagnostics()`, and touch forwarding.
- `MaaProjectManifest`: app-provided project identity, supported controllers, resource repository, package names, attach paths, default task/resource/preset, and copy/submodule mapping.

Prefer improving this API layer over making app code call lower-level AIDL or root internals directly.

## Catalog Compatibility

`InterfaceCatalogLoader` is shared by both host apps. Keep it tolerant of upstream MAA project dialects:

- Locale keys may be plain, `@key`, or `$key`.
- Task options may use `input` or `inputs`.
- Input validation messages may use `pattern_message` or `pattern_msg`.
- Option case labels may come from explicit `label`, `option.<id>.cases.<case>.label`, or `option.<id>.<case>.label`.
- If a non-Yes/No case has no label, fall back to its `name` directly instead of exposing `OptionId:caseName`.
- Controller matching should accept configured aliases such as `adb`, `ADB`, and `安卓端`.

When a host app shows raw option keys, inspect the upstream resource JSON and locale file first, then fix the framework parser if the shape is generic.

## Build Notes

Known working framework verification command:

```bash
JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :framework:testDebugUnitTest
```

To verify Maa-bbb after framework changes, run from `../Maa-bbb-Android`:

```bash
JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug
```

To verify MaaEnd after framework changes, run from `../MaaEnd-Android`:

```bash
JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
sh ./gradlew :app:assembleDebug
```

## Runtime And Device Notes

- Target environment: Android 11+, `arm64-v8a`.
- The app process needs executable `su`; `adb root` alone is not enough.
- Runtime files staged in `runtime/` are packaged by host apps or release archives, not by the framework library by itself.
- Host apps may resolve runtime from local overrides, this submodule's `runtime/`, or a GitHub Release archive depending on their Gradle configuration.
- Physical device `382b528f` has previously been verified with app root authorization and runtime success.
- `emulator-5554` has previously had shell-root limitations; do not treat it as equivalent to a physical rooted phone.
