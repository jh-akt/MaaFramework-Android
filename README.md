# MaaFramework Android

`MaaFramework Android` 是一个把 MAA 项目封装成 Android Root 宿主应用的通用框架仓库。它提供可复用的运行时准备、Root Runtime 启动、任务目录解析、虚拟显示预览、触控转发和日志诊断能力。

当前消费者通过 Git submodule 引用本仓库，应用仓与框架仓仍可独立发布。

## 当前内容

- `framework/`
  - 稳定的底层 Android Library；负责 Root Runtime、Session/Client、目录解析、虚拟显示和诊断
- `framework-host/`
  - 不依赖 Compose 的宿主契约、设置 v2/旧配置迁移、任务策略、日志过滤与运行请求构建器
- `framework-ui/`
  - 一套可复用的主页、任务、设置、日志四页 Compose 宿主壳
- `build-logic/`
  - `com.maaframework.android.host-application` Gradle 插件；统一 Runtime 解析、校验和打包
- `runtime/`
  - Android 运行时二进制暂存目录
  - App 项目构建时可从这里复制 runtime assets / JNI so
- `tools/`
  - Android runtime 准备脚本、Go runner 和 Python shim 辅助工具
- `docs/`
  - 框架抽取和调试记录

## 框架构建

```bash
./gradlew :framework:testDebugUnitTest :framework-host:testDebugUnitTest :framework-ui:compileDebugKotlin
./gradlew -p build-logic build
python3 -m unittest discover -s tools -p "test_*.py"
```

需要 JDK 21、Android SDK 36；最低运行版本仍为 Android 11（API 30）。

## App 项目引用方式

在 Android App 项目的 `settings.gradle.kts` 中挂载框架模块和构建插件：

```kotlin
pluginManagement {
    includeBuild("MaaFramework-Android/build-logic")
}

include(":framework", ":framework-host", ":framework-ui")
project(":framework").projectDir = file("MaaFramework-Android/framework")
project(":framework-host").projectDir = file("MaaFramework-Android/framework-host")
project(":framework-ui").projectDir = file("MaaFramework-Android/framework-ui")
```

App 模块应用约定插件并依赖三个分层模块：

```kotlin
plugins {
    id("com.maaframework.android.host-application")
}

dependencies {
    implementation(project(":framework"))
    implementation(project(":framework-host"))
    implementation(project(":framework-ui"))
}
```

插件保持兼容 `maafwRuntimeDir`、`maafwRuntimeUrl`、`maafwRuntimeRepo`、`maafwRuntimeTag`、`maafwRuntimeAsset` 和 `maafwRuntimeRefresh`。默认解析顺序是显式覆盖、本地暂存目录、GitHub Release；Release 压缩包会用同名 `.sha256` 文件校验。

项目需要专用 Runtime 时只配置坐标：

```kotlin
maaRuntime {
    localDirectory.set("runtime")
    releaseRepo.set("owner/project-android")
    releaseTag.set("android-runtime-v1")
    releaseAsset.set("project-android-runtime-arm64-v8a.zip")
}
```

## Runtime 自动化

框架仓库不把大型 runtime 二进制直接提交进 git。推荐流程是：

1. 用 `tools/prepare_android_runtime.py --android-api 30` 把 Android runtime staged 到 `runtime/`；脚本支持 Windows、Linux、macOS 的 NDK host toolchain。
2. 用 `tools/package_android_runtime.py` 打包成 GitHub Release asset。
3. 宿主 App 在 Gradle 构建时自动下载并解压该 release asset。

默认打包命令：

```bash
python3 tools/package_android_runtime.py
```

默认输出：

- `dist/maaframework-android-runtime-arm64-v8a.zip`
- `dist/maaframework-android-runtime-arm64-v8a.zip.sha256`

生成的 manifest 与 zip metadata 只记录相对路径，不写入开发机绝对路径。

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
