# MaaFramework Android

`MaaFramework Android` 是一个把 MAA 项目封装成 Android Root 宿主应用的通用框架仓库。它提供可复用的运行时准备、Root Runtime 启动、任务目录解析、虚拟显示预览、触控转发和日志诊断能力。

Maa_bbb 的实际 Android 应用已经拆到同级项目：

```text
/Users/haojiang/Code/
  MaaFramework-Android/
  Maa-bbb-Android/
```

`Maa-bbb-Android` 通过 Gradle 子项目引用本仓库的 `framework/` 模块。

## 当前内容

- `framework/`
  - Android Library 模块
  - 负责 MaaFramework runtime 准备、Root Runtime 启动、任务与资源目录加载、预览窗口和诊断导出
- `runtime/`
  - Android 运行时二进制暂存目录
  - App 项目构建时可从这里复制 runtime assets / JNI so
- `tools/`
  - Android runtime 准备脚本、Go runner 和 Python shim 辅助工具
- `docs/`
  - 框架抽取和调试记录

## 框架构建

```bash
JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :framework:testDebugUnitTest
```

## App 项目引用方式

在同级 Android App 项目的 `settings.gradle.kts` 中挂载框架模块：

```kotlin
include(":framework")
project(":framework").projectDir = file("../MaaFramework-Android/framework")
```

App 模块继续使用：

```kotlin
dependencies {
    implementation(project(":framework"))
}
```

如果 App 还需要打包本仓库里的 runtime，可在 App 构建脚本里指向：

```kotlin
val runtimeDir = rootProject.layout.projectDirectory.dir("../MaaFramework-Android/runtime")
```

## Runtime 自动化

框架仓库不把大型 runtime 二进制直接提交进 git。推荐流程是：

1. 用 `tools/prepare_android_runtime.py` 把本地 Android runtime staged 到 `runtime/`。
2. 用 `tools/package_android_runtime.py` 打包成 GitHub Release asset。
3. 宿主 App 在 Gradle 构建时自动下载并解压该 release asset。

默认打包命令：

```bash
python3 tools/package_android_runtime.py
```

默认输出：

- `dist/maaframework-android-runtime-arm64-v8a.zip`
- `dist/maaframework-android-runtime-arm64-v8a.zip.sha256`

## 适用前提

- Android 11+
- `arm64-v8a`
- 设备需要向应用进程提供可执行 `su`
- 推荐使用 Magisk、KernelSU 或其他可向 App 授权 Root 的方案

注意：

- 单纯 `adb root` 不等于应用可获得 Root
- 没有 `su` 的真机，或者只有 `shell` 进程能用 `su` 的模拟器，宿主应用都会显示 Root 不可用

## 项目资产约定

框架默认从 APK 资产中读取以下内容：

- `interface.json`
- `resource/`
- `tasks/`
- `locales/`
- `maa_project_manifest.json`
- `bundled_runtime/`

`maa_project_manifest.json` 用来描述 Android 侧接入信息，例如默认资源、支持的 controller、资源与游戏包名映射。

## 接入方式

应用启动时初始化框架：

```kotlin
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MaaFrameworkAndroid.initialize(this)
    }
}
```

通过 `MaaFrameworkSession` 获取 Root 诊断并连接 Runtime：

```kotlin
val session = MaaFrameworkSession(context)
val rootReport = session.rootDiagnostics()
if (!rootReport.available) {
    error(rootReport.summary)
}

val granted = session.requestRootPermission()
check(granted)

val client = session.connectClient().getOrThrow()
client.prepareRuntime()
client.startRun(
    RunRequest(
        taskId = "StartTask",
        sequenceTaskIds = listOf("StartTask"),
        resourceName = "default",
    ),
)
```

`MaaRuntimeClient` 对外提供：

- `prepareRuntime()`
- `startRun(RunRequest)`
- `stopRun()`
- `getState()`
- `readLogChunk(...)`
- `exportDiagnostics()`
- `startWindowedGame(resourceId)`
- `setMonitorSurface(surface)`
- `setDisplayPower(on)`

## 开源协议

本仓库源码采用 `GNU Affero General Public License v3.0` (`AGPL-3.0`) 发布，详见 [LICENSE](LICENSE)。
