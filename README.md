# MaaFramework Android

`MaaFramework Android` 是一个把 MAA 项目封装成 Android Root 宿主应用的通用框架仓库。它从 `MaaEnd-Android` 中抽离出可复用的运行时准备、Root Runtime 启动、任务目录解析、虚拟显示预览与日志诊断能力，并附带一个基于 `Maa_bbb` 资产的样例应用。

## 当前内容

- `framework/`
  - Android Library 模块
  - 负责 MaaFramework runtime 准备、Root Runtime 启动、任务与资源目录加载、预览窗口和诊断导出
- `app/`
  - 样例宿主应用
  - 默认从同级 `../Maa_bbb/assets` 同步项目资产
  - 用来演示如何把任意 MAA 项目接入到 Android
- `runtime/`
  - Android 运行时二进制暂存目录
  - 打包时会被样例应用复制进 APK 资产或 JNI 目录

## 适用前提

- Android 11+
- `arm64-v8a`
- 设备需要向应用进程提供可执行 `su`
- 推荐使用 Magisk、KernelSU 或其他可向 App 授权 Root 的方案

注意：

- 单纯 `adb root` 不等于应用可获得 Root
- 没有 `su` 的真机，或者只有 `shell` 进程能用 `su` 的模拟器，样例中都会显示 `Root environment not detected`
- 样例首页会展示 root 诊断信息，帮助确认是“没有 `su`”还是“有 `su` 但 App 无法执行”

## 项目资产约定

框架默认从 APK 资产中读取以下内容：

- `interface.json`
- `resource/`
- `tasks/`
- `locales/`
- `maa_project_manifest.json`
- `bundled_runtime/`

`maa_project_manifest.json` 用来描述 Android 侧接入信息，例如默认资源、支持的 controller、资源与游戏包名映射：

```json
{
  "project_id": "maa-bbb",
  "display_name": "Maa_bbb Android Sample",
  "default_resource_id": "官服",
  "default_task_id": "崩坏三 启动！",
  "supported_controllers": ["adb", "安卓端"],
  "attach_resource_paths": [],
  "resource_package_names": {
    "官服": "com.miHoYo.enterprise.NGHSoD",
    "B服": "com.miHoYo.bh3.bilibili"
  }
}
```

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
        taskId = "崩坏三 启动！",
        sequenceTaskIds = listOf("崩坏三 启动！"),
        resourceName = "官服",
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
- `setDisplayPower(on)`

这样接入方不需要直接操作底层 AIDL。

## 样例应用

样例位于 `app/`，默认复用同级 `../Maa_bbb/assets` 与本仓库 `runtime/`：

```bash
JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug
```

调试安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

如果用真机长时间调试，建议保持亮屏：

```bash
adb shell svc power stayon usb
adb shell settings put system screen_off_timeout 2147483647
```

## 已验证状态

- `framework` 库模块与 `app` 样例模块可成功编译
- `Maa_bbb` 的资源、任务与资源渠道列表可在样例中正确加载
- 样例已安装到真机和模拟器进行验证
- 当前调试环境都不满足“应用可获得 root”前提，因此尚未完成真实 root runtime 执行闭环

## 仓库结构

- `framework/src/main/java/com/maaframework/android/`
  - 框架公开 API 与 Root Runtime 实现
- `app/src/main/java/com/maaframework/android/sample/bbb/`
  - `Maa_bbb` 样例应用
- `docs/SESSION_SUMMARY.md`
  - 本轮抽框架、接样例、排查 root 环境的总结

## 开源协议

本仓库源码采用 `GNU Affero General Public License v3.0` (`AGPL-3.0`) 发布，详见 [LICENSE](LICENSE)。
