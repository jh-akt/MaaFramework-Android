# Claude Notes

This is the primary agent guidance file for this repository. Read this file before making changes.

## Project Context

`MaaFramework Android` is an Android Root host framework for MAA projects. It extracts reusable runtime preparation, Root Runtime startup, task/resource parsing, virtual display preview, and log diagnostics from `MaaEnd-Android`, and includes a sample host app wired to `Maa_bbb` assets.

The current repository is a reusable Android MAA Root Host framework prototype, not just a one-off project adapter and not yet a polished Maven-published SDK.

Key modules:

- `framework/`: Android Library module that exposes the reusable MaaFramework Android APIs and Root Runtime implementation.
- `app/`: sample host app demonstrating integration with a MAA project.
- `runtime/`: Android runtime binary staging area packaged into the sample app.
- `docs/SESSION_SUMMARY.md`: recent framework extraction, sample wiring, and debugging context.
- `IMPLEMENTATION_OVERVIEW.md`: detailed implementation notes for the current architecture.

## Before Editing

- Read `docs/SESSION_SUMMARY.md` before further changes. It records the latest extraction decisions, sample-app wiring, and Android debugging state.
- For architecture or runtime-flow changes, also read `IMPLEMENTATION_OVERVIEW.md`.
- Preserve existing repo patterns and Gradle/Kotlin conventions.
- Keep changes scoped to the requested behavior.
- Do not remove or overwrite user changes in the working tree.
- Treat unrelated dirty files as user work unless explicitly told otherwise.

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
10. `app/build.gradle.kts`

## Architecture Notes

### Public Host API

The intended high-level integration surface is small:

- `MaaFrameworkAndroid.initialize(context)`: installs framework context and initializes root support.
- `MaaFrameworkSession`: root diagnostics, root permission request, Root Runtime connection lifecycle.
- `MaaRuntimeClient`: host-facing runtime operations such as `prepareRuntime()`, `startRun(RunRequest)`, `stopRun()`, `setMonitorSurface(surface)`, `startWindowedGame(resourceId)`, `getState()`, `readLogChunk(...)`, `exportDiagnostics()`, and touch forwarding.

Prefer improving this API layer over making sample app code call lower-level AIDL or root internals directly.

### Runtime Flow

A normal run is expected to follow this shape:

1. Host calls `MaaFrameworkAndroid.initialize(context)`.
2. Host creates `MaaFrameworkSession`.
3. Session checks root diagnostics and requests root permission.
4. Session connects `RootRuntimeService` through `RootRuntimeConnector`.
5. Client calls `prepareRuntime()`, which extracts APK assets and bundled runtime files to `/data/local/tmp/<package>/maaframework-runtime/v1`.
6. Client calls `startRun(RunRequest)`.
7. Root runtime prepares the 16:9 virtual display, starts the target game package on that display, initializes MaaFramework/controller, then executes the task sequence.

The root runtime process is a separate root process, not the ordinary app process.

### Virtual Display

- Normal `startRun()` currently uses the virtual display path by default.
- The default display is fixed at `1280x720`, `DPI = 160` in `DefaultDisplayConfig.kt`.
- Controller config should use the virtual `display_id` when the virtual display exists.
- Touch coordinates are expected to be clamped to the `1280x720` coordinate space.

## Build Notes

The sample app has been built with JDK 21. A known working full verification command is:

```bash
JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :framework:testDebugUnitTest :app:assembleDebug
```

For a quicker sample build, `./gradlew :app:assembleDebug` is usually enough.

## Asset And Runtime Packaging

The sample `app/` currently demonstrates a local-packaged integration model:

- Project assets come from sibling repo `../Maa_bbb/assets`.
- Runtime files come from this repo's `runtime/`.
- `runtime/maafw/*.so` is copied into APK `jniLibs`.
- `MaaCommonAssets/OCR/ppocr_v5/zh_cn` is copied to `resource/base/model/ocr`.
- Some pipeline JSON files are patched at build time for the sample recognition scenario.

The `runtime/` directory is intentionally text-only in git. Large local runtime artifacts should be staged there before packaging:

- `runtime/agent/go-service`
- `runtime/maafw/`

## Manifest Notes

`maa_project_manifest.json` complements `interface.json` with Android-host-specific metadata.

Important fields:

- `project_id`
- `display_name`
- `default_resource_id`
- `default_task_id`
- `default_preset_id`
- `supported_controllers`
- `attach_resource_paths`
- `github_resource_repository`
- `resource_package_names`

`resource_package_names` is critical: it maps MAA resource names to Android target package names. The virtual-display launch path uses this mapping to decide which app package to start.

Current sample resource package mappings include:

- `官服`: `com.miHoYo.enterprise.NGHSoD`
- `B服`: `com.miHoYo.bh3.bilibili`
- `OPPO渠道服`: `com.miHoYo.bh3.nearme.gamecenter`
- `华为渠道服`: `com.miHoYo.bh3.huawei`
- `应用宝渠道服`: `com.tencent.tmgp.bh3`
- `VIVO服`: `com.miHoYo.bh3.vivo`
- `九游服`: `com.miHoYo.bh3.uc`
- `小米服`: `com.miHoYo.bh3.mi`

## Runtime And Debugging Notes

- Target environment: Android 11+, `arm64-v8a`.
- The app process needs executable `su`; `adb root` alone is not enough.
- The sample home screen exposes root diagnostics to distinguish missing `su` from inaccessible `su`.
- The previous root/runtime blocker was resolved on a rooted device with app `su` authorization.
- The remaining known runtime issue is that `崩坏三 启动！` can repeatedly hit `StartApp` during the "start and enter game" stage before reaching a stable main-menu recognition loop.

### Known Device State

- Physical device `382b528f` has been verified with `Sukisu Ultra` root authorization.
- On that device, the app can get root, connect Root Runtime, prepare runtime, create a virtual display, and launch `com.miHoYo.enterprise.NGHSoD`.
- Emulator `emulator-5554` can support `adb root`, but its `/system/xbin/su` is shell-only and cannot be executed by the app process.

Before long physical-device debugging sessions:

```bash
adb -s 382b528f shell svc power stayon usb
adb -s 382b528f shell settings put system screen_off_timeout 2147483647
adb -s 382b528f shell input keyevent 224
```

### Diagnostics

- Root runtime logs are written under runtime `logs/root-runtime.log`.
- Failure screenshots are written under runtime `diagnostics/`.
- `exportDiagnostics()` packages state, logs, and screenshots into a zip.

## Current Priorities

If continuing framework extraction, prioritize:

- Standardizing the `runtime/` packaging flow and reducing sample-specific build wiring.
- Adding a template and validation for `maa_project_manifest.json`.
- Stabilizing `MaaRuntimeClient` as the host API surface.
- Making virtual display, input control, and preview responsibilities more explicit.
- Building a more reusable multi-project sample template.
