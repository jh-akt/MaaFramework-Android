# MaaFramework Android 实现说明

这份文档聚焦当前 `/Users/haojiang/Code/MaaFramework-Android` 的实现结构，目标不是重复 README，而是把“框架怎么工作”整理成一份可维护的实现笔记，方便后续继续抽象、接新项目或排查运行问题。

## 1. 目标和当前抽象边界

这个仓库的目标，是把一个标准 MAA 项目包装成 Android 侧可运行的 Root 宿主框架。当前抽象已经把 `MaaEnd-Android` 里与单一项目强耦合的部分拆开，保留了 Android 侧接入所需的几块核心能力：

- 项目资产打包与运行时目录准备
- Root 环境探测与 Root Runtime 启动
- `interface.json` / `locales` / `tasks` / `resource` 的解析
- MaaFramework + Agent + Android Native Controller 的初始化
- 16:9 虚拟屏创建、应用拉起、预览和触控注入
- 宿主应用可直接调用的高层 Session / Client API

当前仓库不是一个 Maven 发布好的 SDK，而是一套已经能落地运行的 Android 框架工程。Maa_bbb 示例宿主已经拆到同级 `../Maa-bbb-Android`，并通过 Gradle 子项目引用这里的 `framework/` 模块。

## 2. 模块划分

### `framework/`

Android Library 模块，承载主要实现。对外可关注这几层：

- 入口初始化：`com.maaframework.android.MaaFrameworkAndroid`
- 高层会话 API：`session/MaaFrameworkSession.kt`
- 高层运行 API：`session/MaaRuntimeClient.kt`
- Root 与 Runtime：`root/`
- MAA 桥接：`maa/MaaFrameworkBridge.java`
- 预览与虚拟屏：`preview/`
- 目录解析与项目约定：`catalog/`、`project/`
- JNI 桥：`bridge/`

### `../Maa-bbb-Android/app/`

示例宿主应用已经从本仓库移到同级项目，当前用 `Maa_bbb` 作为接入样例。它的作用不是增加新框架能力，而是演示：

- 宿主如何初始化框架
- 如何做 root 诊断、连接 runtime、准备 runtime、启动任务
- 如何把现有 MAA 项目资产和 runtime 打进 APK

### `runtime/`

供样例应用打包使用的运行时二进制目录。实际运行时会被解包到 `/data/local/tmp/<package>/maaframework-runtime/v1`。

## 3. 对外接入面

当前最上层的接入 API 很薄，宿主只需要理解 3 个对象。

### `MaaFrameworkAndroid`

文件：`framework/src/main/java/com/maaframework/android/MaaFrameworkAndroid.kt`

职责：

- 安装 `DriverClass` 上下文
- 初始化 `RootManager`

它本身不负责连接 runtime，只是做一次全局初始化。

### `MaaFrameworkSession`

文件：`framework/src/main/java/com/maaframework/android/session/MaaFrameworkSession.kt`

职责：

- root 是否可用 / 是否已授权
- root 诊断信息读取
- 请求 root 权限
- 连接 / 断开 Root Runtime

它相当于宿主和 Root Runtime 之间的“会话门面”。

### `MaaRuntimeClient`

文件：`framework/src/main/java/com/maaframework/android/session/MaaRuntimeClient.kt`

职责：

- `prepareRuntime()`
- `startRun(RunRequest)`
- `stopRun()`
- `setMonitorSurface(surface)`
- `startWindowedGame(resourceId)`
- 触控转发：`touchDown/touchMove/touchUp`
- 状态与日志：`getState()`、`readLogChunk()`
- 导出诊断：`exportDiagnostics()`
- 控制屏幕电源：`setDisplayPower(on)`

它把底层 AIDL 接口收敛成了宿主能直接消费的一组同步 API。

## 4. 运行主链路

一次标准运行，当前实现会经过下面这条链路。

### 4.1 应用初始化

宿主应用启动时调用 `MaaFrameworkAndroid.initialize(context)`，完成：

- `DriverClass.installContext(context)`
- `RootManager.initialize(context)`

这样 JNI 输入控制和 `libsu` root 能力就有了基础上下文。

### 4.2 Root 诊断和授权

`RootManager` 负责 root 检测与授权请求：

- 用一组常见 `su` 路径做探测
- 同时检查 PATH 中可见的 `su`
- 区分“文件存在但 App 不可执行”和“App 已拿到 root”

这里的一个重要设计是：框架把“设备是否真的能给 App root”单独建模成 `RootEnvironmentReport`，避免把“adb 能 root”误判成“应用可 root”。

### 4.3 启动 Root Runtime

`MaaFrameworkSession.connect()` 最终会走到 `RootRuntimeConnector`。

`RootRuntimeConnector` 的职责：

- 为本次连接生成 token
- 清理同包名遗留的旧 root runtime 进程
- 通过 `librootlauncher.so` 启动独立 root 进程
- 等待 `RootServiceBootstrapRegistry` 回传 Binder
- 将 Binder 封装成 `IRootRuntimeService`

这里的实现思路是：真正执行 MAA 的进程不是普通 App 进程，而是通过 root 拉起的独立 runtime 进程。

### 4.4 准备运行时目录

`MaaRuntimeClient.prepareRuntime()` 对应 `RootRuntimeService.prepareRuntime()`，核心逻辑在 `runtime/RuntimeBootstrapper.kt`。

当前 prepare 的行为包括：

- 创建 runtime 根目录：
  - `/data/local/tmp/<package>/maaframework-runtime/v1`
- 建立 `logs/`、`diagnostics/`
- 清理旧的 `interface.json`、`tasks`、`resource`、`agent`、`maafw` 等残留内容
- 停掉旧的 `agent/go-service`
- 从 APK assets 提取基础运行资产
- 如果存在 `bundled_runtime/`，继续覆盖到 runtime 根目录
- 如果存在 `private_pipeline/`，覆盖到 `resource/pipeline/Common/__Private` 或 `resource_adb/pipeline/Common/__Private`
- 给 `agent/go-service` 补可执行权限
- 输出 `RuntimeCapabilities`

被视为基础资产的目录目前包括：

- `interface.json`
- `locales`
- `tasks`
- `resource`
- `resource_adb`

另外，`RuntimeBootstrapper` 还会解析 `interface.json`，把其中资源路径和 controller 的 `attach_resource_path` 一并补进提取列表，所以框架不是死板只拷贝固定目录。

### 4.5 解析任务、资源和预设

`InterfaceCatalogLoader` 负责读取 `interface.json` 和 `locales/interface/zh_cn.json`，生成运行时可消费的 `CatalogSnapshot`。

它当前支持：

- 解析 `import`
- 解析 task / preset / resource / global option
- 按 `supported_controllers` 过滤不适用的 controller 条目
- 合并 locale 文本
- 把 option 展开成 Android 侧可直接展示和提交的数据模型

这层的意义是把 MAA 项目原始描述文件转换成宿主 UI 和 runtime 都能用的统一结构。

### 4.6 启动任务前先准备虚拟屏

当前实现里，`RootRuntimeService.startRun()` 在真正执行 task 之前，会先：

1. 解析本次 `RunRequest`
2. 校验 runtime 已准备完成
3. 解析 task 列表和 resource
4. 调用 `prepareVirtualDisplayForRun(resource)`

这里是最近比较关键的一次收敛：普通 `startRun` 链路现在也会默认走虚拟屏，而不是只在 `startWindowedGame()` 时才走虚拟屏。

`prepareVirtualDisplayForRun()` 内部会：

- 调 `VirtualDisplayManager.start(context)` 创建虚拟屏
- 按 `maa_project_manifest.json` 中的资源包名映射找到目标包
- 用 `ActivityUtils.startApp(...)` 把游戏拉起到这个 display 上

也就是说，当前默认行为已经是：

- 先起 16:9 虚拟屏
- 再把目标 App 启到该 display
- 最后再初始化 Maa controller

这一步是为了避免直接抓取物理屏比例，确保 controller 和截图逻辑落在固定的 1280x720 画布上。

## 5. 16:9 虚拟屏、预览和触控

### 5.1 虚拟屏配置

`preview/DefaultDisplayConfig.kt` 当前固定定义：

- `WIDTH = 1280`
- `HEIGHT = 720`
- `DPI = 160`

这意味着当前框架把 Android 运行目标统一成 16:9 虚拟显示，而不是依赖手机物理分辨率。

### 5.2 虚拟屏创建

`VirtualDisplayManager` 负责：

- 通过 `NativeBridgeLib.setupNativeCapturer(width, height)` 创建 native capturer surface
- 通过隐藏 `DisplayManager` 能力创建 `VirtualDisplay`
- 记录 displayId
- 可选把预览 surface 透传给 native bridge

它会为新 display 打开一组与展示、触控和焦点相关的 flag，尽可能让这个虚拟屏更接近真实可交互显示。

### 5.3 应用拉起

`ActivityUtils.startApp(...)` 负责把目标包启动到指定 display：

- `displayId == 0` 时优先走 shell `am start`
- 虚拟屏场景下通过 `ActivityOptions.launchDisplayId` 配合隐藏 ActivityManager 拉起
- 可选先 `forceStopPackage`
- 虚拟屏场景会加 `FLAG_ACTIVITY_MULTIPLE_TASK`

### 5.4 预览和触控

Root Runtime 通过下面两条链路对宿主暴露虚拟屏交互能力：

- 预览：`setMonitorSurface(surface)`
- 触控：`touchDown/touchMove/touchUp`

触控最终会通过 `DriverClass` 注入到当前虚拟 display。`RootRuntimeService` 在处理触控时，会把坐标限制在 `1280 x 720` 范围内，避免越界。

## 6. MaaFrameworkBridge 的职责

文件：`framework/src/main/java/com/maaframework/android/maa/MaaFrameworkBridge.java`

这是整个框架里最核心的一层，它负责把 Android 侧准备好的环境接到 MaaFramework。

### 6.1 初始化阶段

`init(...)` 的主要步骤：

1. 确认 `NativeBridgeLib` 已加载成功
2. 记录 runtimeRoot、resource、attachResourcePaths、logLevel
3. `ensureLoaded(context, runtimeRoot)` 预加载依赖 so
4. 创建 `MaaResource`
5. 把当前资源路径以及 `resource_adb` bundle 进去
6. 创建 Android Native Controller
7. 创建 `MaaTasker`
8. 绑定 resource 和 controller
9. 挂接 controller / tasker / context sink

### 6.2 动态库加载策略

`ensureLoaded(...)` 当前会：

- 设置 `jna.tmpdir` / `java.io.tmpdir` / `TMPDIR`
- 从 APK 自带 native lib 目录加载：
  - `libMaaFramework.so`
  - `libMaaToolkit.so`
  - `libMaaAndroidNativeControlUnit.so`
- 从 runtime 目录加载：
  - `libonnxruntime.so`
  - `libfastdeploy_ppocr.so`
  - `libopencv_world4.so`
  - `libMaaUtils.so`
  - `libMaaAgentClient.so`
- 如存在 `maafw/plugins`，会先重命名到 `plugins.disabled`

这个策略说明当前框架默认不依赖系统环境装库，而是尽量从 APK 和 bundled runtime 自带一套闭环。

### 6.3 Controller 配置

`buildControllerConfig(...)` 会根据当前 display 状态生成 controller 配置 JSON。

重点是：

- 如果虚拟屏已存在，就把 `display_id` 设成虚拟 displayId
- `screen_resolution` 使用 `1280 x 720`
- `force_stop` 在虚拟屏场景下设为 `true`
- 如果还没起虚拟屏，才回退到查询系统分辨率和 `display 0`

这也是当前“普通运行默认走虚拟屏”的关键落点。

### 6.4 任务执行

`runTask(entry, overrideJson)` 的主要流程：

- 如有需要先接上 agent
- 调 `MaaTaskerPostTask`
- 等待 `MaaTaskerWait`
- 非成功状态下补充 task 描述和最近事件摘要
- 返回 `RunResult`

因此 RootRuntimeService 不直接理解 MAA task 内部语义，它更像调度层；真正的任务执行语义仍然在 MaaFramework 一侧。

## 7. JNI 和 Native Bridge

文件：`framework/src/main/java/com/maaframework/android/bridge/NativeBridgeLib.java`

当前 `NativeBridgeLib` 负责：

- `System.loadLibrary("bridge")`
- 调用 `bootstrap(DriverClass.class)` 完成 JNI 绑定初始化
- 暴露 native capturer / preview surface / frame capture 能力

它相当于 Android Java/Kotlin 层和 `libbridge.so` 的统一入口。

当前上层直接用到的 native 能力主要有：

- 创建截图 surface：`setupNativeCapturer`
- 释放 capturer：`releaseNativeCapturer`
- 预览 surface 同步：`setPreviewSurface`
- 失败时抓取当前预览帧：`capturePreviewFrame`

## 8. Root Runtime Service 的职责边界

文件：`framework/src/main/java/com/maaframework/android/root/RootRuntimeService.kt`

它是 root 进程内的总调度器，负责把“运行一个任务”拆成几段稳定流程：

- 初始化输入控制和显示电源恢复
- 准备 runtime
- 管理任务状态快照 `RuntimeStateSnapshot`
- 顺序执行 task 序列
- 管理虚拟屏生命周期
- 采集运行日志
- 失败时截图
- 导出诊断 zip

它不是纯粹的 Binder 壳，而是把 Android 运行态里最容易脏乱的流程都集中在一个单线程执行器里做串行化，减少竞态。

## 9. 项目接入约定：`maa_project_manifest.json`

文件：`framework/src/main/java/com/maaframework/android/project/MaaProjectManifest.kt`

当前 manifest 用来描述 Android 宿主侧额外需要的信息，和 `interface.json` 互补。

重点字段：

- `project_id`
- `display_name`
- `default_resource_id`
- `default_task_id`
- `default_preset_id`
- `supported_controllers`
- `attach_resource_paths`
- `resource_package_names`

其中最关键的是 `resource_package_names`，它定义“MAA 资源名”到“Android 目标包名”的映射。框架在拉起游戏到虚拟屏时，就是通过这层映射决定要启动哪个包。

## 10. 示例应用如何接入 `Maa_bbb`

Maa_bbb 示例应用现在位于同级 `../Maa-bbb-Android`，通过 Gradle 子项目引用本仓库的框架模块：

```kotlin
include(":framework")
project(":framework").projectDir = file("../MaaFramework-Android/framework")
```

示例应用的 `app/build.gradle.kts` 继续负责：

- 依赖 `implementation(project(":framework"))`
- 从 `../MaaFramework-Android/runtime/` 同步 bundled runtime
- 把 `../MaaFramework-Android/runtime/maafw/*.so` 同步到 APK `jniLibs`

这说明当前框架的推荐接入姿势仍然是：

- App 项目负责项目资产和 runtime 的打包策略
- 框架模块负责 Root Runtime、目录解析、虚拟屏和 MAA 调度
- Root Runtime 再从 APK 解包到 `/data/local/tmp`

这种分工可以让框架保持通用，同时让不同 MAA 项目的 Android 宿主各自维护自己的 UI 和配置。

## 11. 日志、失败截图和诊断导出

当前调试链路已经比较完整。

### 日志

`RuntimeLogger` 会把 root 运行日志写到：

- `logs/root-runtime.log`

同时内存里保留一小段 live tail，供宿主 UI 轮询展示。

### 失败截图

任务失败时，`RootRuntimeService` 会优先尝试：

- `NativeBridgeLib.capturePreviewFrame()`

如果拿不到预览帧，再退回：

- `/system/bin/screencap`

截图保存在 runtime 下的 `diagnostics/`。

### 诊断导出

`exportDiagnostics()` 会把下面几项打成 zip：

- `state.json`
- `logs/root-runtime.log`
- 失败截图

这让“用户报现象”能尽量转成“直接拿诊断包看状态和截图”。

## 12. 当前实现上的几个重要结论

### 普通任务运行现在默认走虚拟屏

这是当前实现最值得记住的一点。不是只有窗口预览模式才使用虚拟屏，正常 `startRun()` 也会先准备虚拟屏，再初始化 controller。

### 画布尺寸当前固定为 1280x720

这让控制、截图、预览和触控可以统一按 16:9 坐标系处理，不再直接受物理屏比例影响。

### Root 前提是“App 进程可执行 su”

单纯 `adb root` 并不等于框架可运行。当前实现已经把这个约束显式反映在 root diagnostics 中。

### Runtime 采取“APK 打包 + 本地解包”的策略

这让运行环境更可控，也降低了 Android 端调试时“设备上到底装了哪版 so / agent / pipeline”的不确定性。

## 13. 建议优先阅读的文件

如果后面要继续扩展这个框架，建议按这个顺序读代码：

1. `framework/src/main/java/com/maaframework/android/session/MaaFrameworkSession.kt`
2. `framework/src/main/java/com/maaframework/android/session/MaaRuntimeClient.kt`
3. `framework/src/main/java/com/maaframework/android/root/RootRuntimeConnector.kt`
4. `framework/src/main/java/com/maaframework/android/root/RootRuntimeService.kt`
5. `framework/src/main/java/com/maaframework/android/runtime/RuntimeBootstrapper.kt`
6. `framework/src/main/java/com/maaframework/android/maa/MaaFrameworkBridge.java`
7. `framework/src/main/java/com/maaframework/android/preview/VirtualDisplayManager.kt`
8. `framework/src/main/java/com/maaframework/android/catalog/InterfaceCatalogLoader.kt`
9. `framework/src/main/java/com/maaframework/android/project/MaaProjectManifest.kt`
10. `../Maa-bbb-Android/app/build.gradle.kts`

## 14. 后续继续抽象时的方向

从当前实现看，后续最有价值的继续抽象方向主要有这些：

- 把 `runtime/` 打包流程再标准化，减少示例应用里的定制脚本
- 为 `maa_project_manifest.json` 增加校验与模板生成
- 把 `MaaRuntimeClient` 做成更稳定的宿主 API，而不是只服务当前样例
- 把虚拟屏、输入控制和预览抽成更明确的 display/session 模型
- 为多项目接入补一层更标准的 sample 模板

总体上，这个仓库当前已经不是“某个项目的 Android 适配工程”，而是一个可复用的 Android MAA Root Host 框架雏形。
