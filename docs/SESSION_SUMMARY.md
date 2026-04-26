# MaaFramework Android Session Summary

Date: 2026-04-24
Workspace: `/Users/haojiang/Code/MaaFramework-Android`  
Reference repos:

- source extraction baseline: sibling `../MaaEnd-Android`
- sample/debug assets: sibling `../Maa_bbb`

Update 2026-04-26: Maa_bbb 的 Android 宿主应用已经拆到同级 `../Maa-bbb-Android`，本仓库保留 `framework/`、`runtime/`、`tools/` 等框架内容。新的 App 项目通过 Gradle 子项目引用 `../MaaFramework-Android/framework`。

## 1. 本轮工作的目标

从 `MaaEnd-Android` 中抽出一套更通用的 Android 框架，使任意 MAA 项目都能通过：

- 打包项目资产
- 声明 `maa_project_manifest.json`
- 初始化宿主应用
- 请求 Root 并连接 Root Runtime

的方式接入 Android。

## 2. 当前已经完成的内容

### 仓库结构

- 原先的 App 逻辑被拆成两个模块：
  - `framework/`：Android Library
  - `app/`：样例宿主应用
- 根工程名已调整为 `MaaFrameworkAndroid`

### framework 抽象

- 包名从 `com.maaend.android` 迁移到 `com.maaframework.android`
- 移除了 MaaEnd 专属 UI、持久化和资源仓库更新逻辑
- 引入 `maa_project_manifest.json` 作为 Android 接入描述文件
- `InterfaceCatalogLoader`、`RuntimeBootstrapper`、`RootRuntimeService` 已改为面向通用 MAA 项目
- `MaaFrameworkBridge` 不再强制依赖 `./resource_adb`
- 增加了高层 `MaaRuntimeClient`，接入方不需要直接面对 AIDL
- 增加 `RootEnvironmentReport`，用于解释 root 环境是否真正对应用可用

### sample app

- 新的 `app/` 模块是 `Maa_bbb` 的 Android 示例宿主
- 构建时会同步：
  - `../Maa_bbb/assets`
  - `runtime/`
  - `runtime/maafw/*.so`
- Compose 样例页支持：
- 资源选择
- 预设选择
- 任务选择与执行
- 参考 `MaaEnd-Android` 的 Home / Tasks / Logs 页面结构与主题
- 虚拟显示预览挂载
- 运行状态轮询
- 日志展示
- root 诊断展示

## 3. 已验证结果

构建命令：

```bash
JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :framework:testDebugUnitTest :app:assembleDebug
```

结论：

- 构建成功
- APK 已安装到真机 `382b528f`
- APK 已安装到模拟器 `emulator-5554`
- `Maa_bbb` 资源列表在样例 UI 中加载正确：
  - 官服
  - B服
  - OPPO 渠道服
  - 华为渠道服
  - 应用宝渠道服
  - VIVO 服
  - 九游服
  - 小米服

## 4. Root 调试的关键结论

这是本轮最重要的现实约束。

### 真机

真机 `382b528f` 已切换到 `Sukisu Ultra` 管理 root，并完成了对样例 App 的授权联调。当前已经确认：

- 样例页显示 `Root · Granted`
- `Connect Root` 后 `Connected · Yes`
- `Prepare Runtime` 可成功
- `Run Task` 后可创建虚拟显示并拉起 `com.miHoYo.enterprise.NGHSoD`
- Runtime 日志中可见 `agent client connected`

因此当前真机链路的关键前提已经满足，root/runtime 不再是主阻塞。

### 模拟器

模拟器支持 `adb root`，并且 `shell` 进程里能看到：

- `/system/xbin/su`

但是这个 `su` 仅对 `shell` 组可执行，不对普通应用进程开放。结果是：

- `adb root` 可以
- App 进程仍然不能执行 `su`
- 框架里的 root 诊断会显示 “su exists ... but the app process cannot execute it”

### 结论

想要跑通 Root Runtime，设备必须满足以下至少一项：

- 真正的已 Root 真机，并且可以向该 App 授权 root
- 提供对普通 App 进程开放的 `su`

仅靠：

- 没有向 App 授权 `su` 的普通 adb 调试真机
- 只有 `adb root` 的模拟器

都不够。

## 5. 当前剩余主阻塞

`崩坏三 启动！` 任务已经能够真正启动，但还没有完整跑通。当前卡点收敛在“启动并进入游戏”这段：

1. 游戏被成功拉起到虚拟显示
2. 日志进入 `启动并进入游戏`
3. `成功进入游戏主菜单` 与 `点击任意位置进入游戏` 识别失败
4. 随后再次命中 `模拟器包名游戏启动` 并重复 `StartApp`

结论：

- root 权限不是问题
- runtime 准备不是问题
- 虚拟显示与拉起游戏不是问题
- 当前需要继续修的是启动阶段识别/流程控制

如果目标是继续框架化，优先做：

1. 继续同步 `MaaEnd-Android` 中明确属于 framework 的 UI / 输入 / 诊断能力
2. 增加发布脚本或 Maven 发布配置
3. 为 `maa_project_manifest.json` 提供模板和校验

## 6. 这次改动后最值得先看的文件

- `framework/src/main/java/com/maaframework/android/MaaFrameworkAndroid.kt`
- `framework/src/main/java/com/maaframework/android/session/MaaFrameworkSession.kt`
- `framework/src/main/java/com/maaframework/android/session/MaaRuntimeClient.kt`
- `framework/src/main/java/com/maaframework/android/root/RootManager.kt`
- `framework/src/main/java/com/maaframework/android/root/RootRuntimeService.kt`
- `framework/src/main/java/com/maaframework/android/project/MaaProjectManifest.kt`
- `app/src/main/java/com/maaframework/android/sample/bbb/MainViewModel.kt`
- `app/src/main/java/com/maaframework/android/sample/bbb/SampleScreen.kt`

## 7. 设备调试备忘

真机长时间联调前先保持亮屏：

```bash
adb -s 382b528f shell svc power stayon usb
adb -s 382b528f shell settings put system screen_off_timeout 2147483647
adb -s 382b528f shell input keyevent 224
```
