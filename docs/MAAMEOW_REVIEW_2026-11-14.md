# 框架优化审查：对照 MaaMeow

审查日期：2026-11-14（Asia/Singapore）。

- 本地框架基线：`82d90f2`，包含上一轮生命周期门闩、资源目录事务和 MaaFgo 宿主接入。
- 参考仓库：同级 `MAA-Meow/`，来源 https://github.com/Aliothmoon/MAA-Meow 。
- 参考提交：`9eab1041bf1640f623b3a2bbefb019a72d15b348`，提交时间 2026-11-11 15:42:57 +08:00。本轮 `ls-remote` 确认与远程 main 一致。
- 初始审查范围：静态源码与现有产物规模。后续实现结果见下节；未做 Android 设备复现、耗时或功耗测量。
- MaaMeow 使用 MaaAssistantArknights 的 MaaCore；适合借鉴 Android 提权、Binder、资源协调及生命周期设计，具体任务执行 API 仍须按 MaaFramework 设计。

## 本轮实现结果

- Binder：连接互斥、死亡监听、按 Binder 身份隔离旧回调；普通 RPC 5 秒、诊断 30 秒上限。超时记录线程栈，尝试恢复亮屏，只终止匹配 PID，等待死亡确认才允许重连；绝不自动重放任务。启动日志不再丢弃。
- 生命周期：准备、运行、小窗启动/关闭和退出清理统一排队；停止令牌贯穿文件复制和资源锁等待；准备/运行的返回值表示请求被接受，结果从状态快照读取。宿主死亡后最多等待 30 秒清理，避免遗留卡死进程。
- 资源：运行时复制持有跨进程文件租约，使用 staging/current/previous 事务发布；取消或失败保留可用版本。config/logs/debug/diagnostics 保存在版本目录之外，清理版本时不跟随符号链接。
- 预览：有界单线程输入队列，合并连续 Move，溢出发送 ACTION_CANCEL；Surface 更新保留，调用不阻塞 UI，关闭先取消手势再释放调用线程。
- 准备缓存：用 APK 路径/大小/修改时间与资源版本生成标识，校验关键组件后复用；缺失组件会重建。任务目录每次准备后只解析一次。引擎/Agent 仍逐任务隔离：自定义 Agent 的全局状态与资源修改尚无通用重置契约，本轮不启用跨任务实例复用。
- 后端：`PrivilegedRuntimeBackend` 分离权限、后端身份、连接与故障处理，`MaaFrameworkSession` 可注入实现。默认仍是 Root；**尚未实现 Shizuku 启动器，不能据此宣称无 Root 设备已受支持**。
- 宿主：选项展开提取为 `MaaOptionOverrides`，状态修改采用原子 update；BBB/GF2 迁移到共享 ViewModel，保留原有 preferences 名称；MaaEnd 复用选项组件并保留产品任务策略。
- ADB 排序默认：目录解析与公共 UI 都按兼容性稳定排序；兼容任务在前，非兼容任务在后，各组内部应用置顶策略。桌面专属任务保留标识和详情，禁止勾选，宿主与 Runtime 双重拒绝执行。未声明 controller 按接口规范视为通用任务；支持声明不等于已通过实机验证。
- 息屏恢复：`MaaDisplayPowerRecovery` 统一监听亮屏事件、异步恢复、接收器释放与会话隔离，`MaaDisplayPowerPolicy` 控制亮屏事件策略；四个应用共用。停止、失败、正常断连由 Root runtime 恢复，卡死时尽力恢复，异常退出保留标记供下一次连接恢复。

验证：框架 53 项、公共宿主 7 项、Fgo 2 项、MaaEnd 1 项单元测试通过；四应用编译通过，Fgo 完整 APK 打包与 payload 检查通过。Android 17 / 4 KB 页手机已验证安装启动、Root 授权、Runtime 连接、首次准备、重复准备缓存和息屏恢复。二次准备后 interface 与 identity 文件的 inode/mtime/大小均未改变，日志不再出现解包。

设备验收额外发现 16 KB 构建问题：自编译 bridge/launcher 增加 LOAD 与 RELRO 链接对齐；公共 UI 显式依赖 graphics-path 1.1.0，替换 1.0.1 中跨 RELRO 尾页的可写数据布局。公共打包插件对 fastdeploy/OpenCV 的预编译 ELF 禁用二次 strip，保留其额外 LOAD 段的 offset/vaddr 对齐；只检查 p_align 不足以发现这一问题。依据 [Android 页大小检查说明](https://developer.android.com/guide/practices/page-sizes) 与 [Graphics 发布记录](https://developer.android.com/jetpack/androidx/releases/graphics)，用实际 ELF 验证，不通过隐藏系统提示规避。

尚未验证：真正的 16 KB 设备、实机战斗、运行中停止、触控压力与卡死进程故障注入。下文行号对应初始审查基线，实施后已经变化。

## 优先级与建议顺序

| 顺序 | 优先级 | 工作 | 直接收益 |
| --- | --- | --- | --- |
| 1 | P1 | 远端会话管理、Binder 死亡/卡死处理 | 不因远端挂死永久卡在忙碌；拒绝过期回调 |
| 2 | P1 | prepare/start/stop/destroy 统一执行所有权 | 准备可取消，退出不截断正在准备的文件 |
| 3 | P1 | 资源读取租约、运行时 staging 发布 | 避免新旧资源混用、失败留下半成品 |
| 4 | P1 | 预览 Surface/触控退出同步 UI 调用路径 | 降低远端慢调用造成卡顿或 ANR 的风险 |
| 5 | P2 | 按版本准备、按运行会话复用引擎和 Agent | 减少解包、模型加载和 Python 启动成本 |
| 6 | P2 | Root/Shizuku 后端抽象 | 扩大可用设备范围，统一权限与连接状态 |
| 7 | P2 | 宿主状态与执行计划拆分、迁移旧应用 | 修复跨线程状态覆盖，降低多应用维护成本 |

## 1. Binder 会话需要独立管理

本地依据：

- `framework/src/main/java/com/maaframework/android/root/RootRuntimeConnector.kt:18`：每次连接先清理既有 Root 进程；没有 Binder 死亡监听或可复用会话。
- 同文件 `:29` 的 15 秒超时仅包住等待 bootstrap Binder，不涵盖前面的同步 shell 执行，也不涵盖之后的业务调用。
- `framework-host/src/main/java/com/maaframework/android/host/MaaHostViewModel.kt:471`：每秒串行 ping、读状态、读日志；任一个同步 Binder 调用卡死，轮询无法进入异常分支。
- 同文件 `:1099`：重操作已切到 IO，但 IO 线程不等于超时、取消或故障恢复。

MaaMeow 依据：

- `app/src/main/java/com/aliothmoon/maameow/manager/RemoteServiceManager.kt:38`：`awaitOrAbandon` 明确处理同步 Binder 无法被普通协程取消的问题。
- 同文件 `:132`、`:208`：`linkToDeath`，且用 Binder 身份过滤迟到的死亡通知。
- 同文件 `:346`：卡死时先尝试取线程栈，再对匹配的会话解绑处理。

建议：引入框架级 RuntimeSessionManager，保存连接代次、Binder 身份、连接状态；进程启动日志保留到诊断目录。把卡死与已经死亡分开，恢复前记录诊断；禁止无条件重放可能已执行的游戏动作。不能只给同步调用套一层 `withTimeout` 就声称底层已停止。

验收：旧 Binder 的延迟死亡不能断开新会话；调用不返回时 UI 仍能操作并看到明确终态；诊断接口本身卡住也要有上限。

## 2. 准备和退出尚未完全纳入同一执行生命周期

本地依据：

- `RootRuntimeService.kt:83` 的 `prepareRuntime()` 直接在 Binder 调用线程执行准备。
- `RootRuntimeService.kt:137` 首次 start 也会在提交 executor 前同步准备。
- `RootRuntimeService.kt:320` 的 destroy 将清理和 `exitProcess(0)` 排进 executor；正在 Binder 线程进行的准备不在该队列里，因此退出仍可能中断解包。
- `RuntimeBootstrapper.kt:42` 及递归复制没有接收停止标记；停止请求要等准备返回后才收敛。
- `startWindowedGame` / `stopWindowedPreview` 也没有纳入任务的显示资源所有权。

建议：所有耗时生命周期工作由一个明确的 worker/session 持有；Binder 入口只提交请求、返回 operationId，进度与结果独立通知。文件边界和下载块加入协作取消检查。destroy 等当前操作安全退出后再清理；原生调用不可取消时由会话超时机制兜底。

MaaMeow 的 `MaaCompositionService` 区分 STARTING、RUNNING、STOPPING，启动、资源准备及空闲文件写入共享协调入口，可借鉴状态边界。其实现也不是所有同步原生调用都天然可取消。

验收：准备中停止、准备中关闭 App/销毁服务、停止后立即启动、运行中重复打开/关闭预览；每次只有一个拥有者，取消不发布半成品。

## 3. 目录原子切换还需要读者协调

本地依据：

- `PersistentProjectRepositoryManager.kt:156` 的更新和清理使用跨进程文件锁，这是现有正确基础。
- `RuntimeBootstrapper.kt:69` 从 `current/` 枚举和复制资源没有持有同一读取租约；UI 的同步/清除仅互斥于其他资源操作，没有与准备串行。
- 可能交错：读取旧 interface → writer 将 current 改名为 previous 并发布新版 current → reader 按原 current 路径读取新版 resource/agent。原子改名保证发布完整，不保证多文件读者的一致快照。
- `RuntimeBootstrapper.kt:56` 先删除旧 runtime payload，再解包并覆盖；空间不足、进程退出或复制错误会留下不完整运行目录。

MaaMeow 依据：`domain/service/CoreFilesGate.kt` 使用任务空闲条件与启动互斥锁；`data/resource/HotUpdateSync.kt` 可先联网获取，实际落盘走 `whenIdle`，提交后递增 revision 供读者重载。

建议：下载与执行可并行，发布/清理与运行时准备统一协调。采用不可变 revision 目录和读者租约，或把读取完整快照纳入共享锁。运行目录使用 staging → 校验 → 发布；用户 config、库存和缓存独立存放。上轮仓库目录事务可以复用，但运行时的用户数据与原生句柄需要额外设计。

验收：复制到一半更新或清理、低空间、发布前后进程中断；本次会话始终读取同一个 revision，失败后仍能使用上一份完整资源。

## 4. 触控与 Surface 仍可能阻塞 UI

本地依据：`MaaHostViewModel.kt:278–292` 直接调用同步 `setMonitorSurface/touchDown/touchMove/touchUp`；`framework-ui/.../MaaFrameworkUi.kt:1322` 在 Compose pointerInput 中同步触发这些方法。现有退出预览逻辑会尝试补发 Up，但补发本身同样可能阻塞。

MaaMeow 依据：`app/src/main/aidl/com/aliothmoon/maameow/RemoteService.aidl` 的 Surface 与触控为 `oneway`，提供整体 `touchCancel`；同一 Binder 的 oneway 保证发送顺序。

建议：使用有界、串行的输入队列或经过状态反馈设计的 oneway API；移动事件可合并，Down/Up/Cancel 不丢失。不要为每个 Move 启动无序 IO 协程。保留明确的远端失败反馈，不能把“成功入队”当成“注入成功”。

验收：多指 Down/Move/Up 顺序、远端延迟、队列饱和、预览退出和 Binder 死亡时释放触点；UI 主线程不等待远端。

## 5. 可测量的性能优化候选

### 按资源身份跳过重复准备

当前每次显式 prepare 或新 Root 会话首次运行都会重新准备 payload。MaaFgo 当前生成文件树实测：

- project：13,745 个文件，311,530,876 bytes。
- runtime：528 个文件，166,637,471 bytes。
- 合计约 456 MiB、14,273 个文件；这是逻辑文件规模，不是测得的写放大或设备耗时。

MaaMeow 的 `CoreDataPusher.kt:33` 用版本码与 APK mtime 形成 stamp；`remote/internal/CoreDataStore.kt:36` 在 stamp 和关键文件满足条件时跳过重解，成功后才记录 stamp。

建议使用 APK/运行时包指纹、项目 revision、ABI、框架资源布局版本作为缓存键，保留强制重建入口。MaaMeow 的 ensureResources 本身仍采用删除后解包，适合借鉴身份判断，不应把它描述为已有完整事务回滚。

### 同一任务序列复用引擎与 Agent

`RootRuntimeService.kt:338` 每执行一个 task 都销毁旧 Bridge，再 init 新 Bridge。初始化会重新创建 Resource/Controller/Tasker；首次 run 又拉起 Agent。MaaMeow 提供 CreateInstance → AppendTask → Start 的会话式接口，有可借鉴的生命周期边界。

建议先复用同一区服、同一资源版本、同一控制器的单次运行会话，出现失败或切换配置就销毁。必须测试 pipeline override 不会从前一任务泄漏到后一任务、Agent 缓存是否需重置。先测登录→日常等序列的模型加载/Agent 启动耗时，再决定复用粒度；不预估未经测量的提升比例。

## 6. Root/Shizuku 统一后端

MaaMeow 的 `RemoteServiceConnectorBackend`、`RemoteAccessState` 和 Root/Shizuku 两个 connector 将权限、可用性、连接和后端身份分开。当前框架 API 与实现仍以 Root 为中心。

建议在 MaaFrameworkSession 后增加 PrivilegedRuntimeBackend，App 仅选择后端与读取能力。保留旧 Root API 兼容适配。先完成前述会话与数据目录设计，再扩展 Shizuku；shell/root 的文件访问、SELinux、虚拟屏和息屏能力必须逐项验证，不能只替换启动命令。

## 7. 通用 ViewModel 继续拆分，并迁移旧应用

当前通用 `MaaHostViewModel` 为 1,202 行；MaaEnd 为 1,357 行，BBB 与 GF2 各 1,194 行。上一轮抽取让 MaaFgo 不再复制整份宿主逻辑，但旧应用尚未统一迁移。

此外，资源进度回调在 IO 线程用 `_uiState.value.copy(...)` 更新，UI 和轮询也以同样方式读改写；StateFlow 单次赋值线程安全不等于整个读改写原子，交错时可能覆盖刚改的选项或运行状态。

建议拆为：

1. CatalogRepository：目录、资源身份和更新进度。
2. RunPlanBuilder：任务排序、共享/任务选项、输入替换、override 合成，纯 Kotlin 可测。
3. RuntimeSessionController：权限、连接、运行状态和日志。
4. Host ViewModel：聚合状态和 UI 意图，更新采用原子 update 或单一 reducer。

迁移旧应用前覆盖其特有执行策略和配置兼容；保留已选择任务与已勾选任务的不同语义。

## 推荐第一批验收

先完成会话故障处理、生命周期统一和资源快照，再做触控异步化。回归重点：

- 准备中停止/退出不发布残缺资源。
- 远端死亡或调用卡死有明确终态、保留诊断，不重放游戏动作。
- 准备与更新/清理并发时资源版本一致。
- 迟到回调不污染新会话，触控事件顺序和释放正确。
- 所有宿主既有配置兼容；真实设备分别验证 Root 与未来的 Shizuku。
