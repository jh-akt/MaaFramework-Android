# Android Host Migration

## 分层

- `:framework`：Root、Runtime、`MaaFrameworkSession`、`MaaRuntimeClient` 和项目目录；这些底层 API 保持稳定。
- `:framework-host`：不依赖 Compose 的统一状态/动作契约、设置仓库、策略与纯逻辑。
- `:framework-ui`：`MaaHostApp` 四页宿主壳。
- `build-logic`：Runtime Gradle 约定插件。

## 应用适配

应用保留自己的 `Application`、`Activity`、主题、图标、applicationId、版本和签名配置。现有 ViewModel 可以先通过薄适配层映射到 `MaaHostState` 与 `MaaHostActions`，再逐步把项目无关逻辑下沉；不需要一次改写 Root 或虚拟显示生命周期。

```kotlin
MaaHostApp(
    state = appState.toHostState(),
    actions = viewModel.hostActions(),
    config = MaaHostUiConfig(
        appName = appState.manifest.displayName,
        policy = MaaHostPolicies.Default,
    ),
)
```

选中卡片只改变 `selectedTaskId`，复选框只改变 `checkedTaskIds`，两者不能合并。执行时按可见任务顺序读取勾选项；没有勾选项才回退到当前焦点。

## 设置兼容

`MaaHostSettingsRepository` 会先读取 `host_settings_v2`，再回退到三端已有的逐项 preference key。导出统一为 v2，并带 `projectId`；导入仍接受旧 v1 结构，包括 MaaEnd 的 `lastSelectedTaskId` / `lastSelectedPresetId` 字段。v2 跨项目导入会被拒绝。

## Runtime

在 `settings.gradle.kts` 的 `pluginManagement` 中包含 `MaaFramework-Android/build-logic`，App 应用 `com.maaframework.android.host-application`。插件按以下顺序解析：

1. `maafwRuntimeDir`
2. `maaRuntime.localDirectory`
3. Release URL 或坐标

下载的 zip 默认必须通过同名 `.sha256` 校验。项目专用 agent 可以配置自己的 Release；本地开发仍可用旧 `maafwRuntime*` 属性覆盖。

## 验收

每个消费者至少执行：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

框架执行模块单测、UI Kotlin 编译、build-logic 构建以及 Runtime Python helper 单测。真机 Root/触控/息屏验证保留为发布前的设备验收，不阻塞普通 CI。

## 通用宿主与 APK 内置项目

新应用可直接使用 `MaaHostViewModel` 和 `viewModel.hostActions()`，不必复制应用层
ViewModel。它提供任务独立勾选、任务选项、资源/全局默认选项、预设、配置导入导出和
Root Runtime 操作。需要可编辑共享选项或特殊执行策略的旧宿主仍可保留自己的适配层。

项目资源需要构建期转换或与原生依赖锁定版本时，在 manifest 设置
`"bundled_resource_path": "project"`，把完整 Android 项目资源放入该 assets 目录。
此模式与 `github_resource_repository` 互斥，资源随 APK 更新，UI 不提供 GitHub
同步/清除操作。运行时仍由 Gradle 插件独立打包。准备运行时会替换代码和资源，但不
删除 `config/` 用户数据；内置项目包不得包含会覆盖用户数据的 `config/`。

`virtual_display_dpi` 可覆盖项目虚拟屏 DPI（默认 160），宽高继续使用框架的
1280×720。项目包名映射支持 `包名/Activity` 形式。
