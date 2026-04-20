# MaaFramework Android Session Summary

Date: 2026-04-21  
Workspace: `/Users/haojiang/Code/MaaFramework-Android`  
Reference repos:

- source extraction baseline: sibling `../MaaEnd-Android`
- sample/debug assets: sibling `../Maa_bbb`

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

真机 `382b528f` 的 `adb shell` 中没有 `su`：

- `which su` 无输出
- `su -c id` 返回 `su: inaccessible or not found`

因此样例显示 `Root environment not detected` 是正确行为，不是框架误判。

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

- 普通 adb 调试真机
- 只有 `adb root` 的模拟器

都不够。

## 5. 下次继续时最重要的事

如果目标是继续验证真正的 runtime 执行链路，优先准备一台可向 App 授权 root 的设备，然后按这个顺序做：

1. 安装样例 APK
2. 打开样例首页，确认 root 诊断变为 `Executable su binary found` 或直接 `granted`
3. 点击 `Connect Root`
4. 点击 `Prepare Runtime`
5. 观察 `Runtime Logs`
6. 再尝试执行 `崩坏三 启动！`

如果目标是继续框架化，而不是设备联调，优先做：

1. 提炼更多公开 API
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
