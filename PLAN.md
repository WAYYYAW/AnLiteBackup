# AnLiteBackup P0 阶段实施规划：代码减负与后台保活体系

本文档基于对当前工程架构的全面排查及需求对齐，制定 P0 阶段的实施方案与落地细则。

---

## 一、 核心目标与对齐决议

1. **彻底清理遗留代码与技术债务**：
   - 全面删除 Neo-Backup / OAndBackupX 时期遗留的 `manager/`、`viewmodels/`、`ui/pages/`、`ui/navigation/` 目录；
   - 清理 `AndroidManifest.xml` 中冗余的短信/彩信广播接收器和服务（`SmsReceiver`, `HeadlessSmsSendService`, `ComposeSms` 等）；
   - 清除废弃的三方依赖（如 `pgpainless`, `kaml` 等），精简 `build.gradle.kts`；
   - 净化 `AnLiteActivity.kt` 与 `AnLiteApp.kt`，全面转向基于 `MainScreen` 的现代单页面 Compose 架构。

2. **前台服务 (ForegroundService) 与 WakeLock 保活**：
   - 引入专用 `BackupForegroundService`（类型为 `foregroundServiceType="dataSync"`）；
   - 在串行队列执行期间持有 `PowerManager.PARTIAL_WAKE_LOCK`，杜绝因系统 Doze 模式或 LMK 查杀导致任务中断；
   - 任务空闲或结束后自动释放锁并停止前台服务。

3. **Android Live 实时通知与优雅回退**：
   - 针对支持的新版本系统（Android 16 / Rich Ongoing Notifications 或厂商灵动岛/状态栏胶囊），优先接入实时 Live 通知；
   - 在不支持的系统版本上，平滑回退至带进度条的常驻通知（`NotificationCompat.CATEGORY_PROGRESS`）；
   - 通知栏不设置操作按钮，点击通知直接跳转回主页面（`AnLiteActivity`）。

4. **通知运行时权限前置强校验**：
   - 针对 Android 13+ (API 33+) 的 `POST_NOTIFICATIONS` 权限实行**强校验拦截**；
   - 用户发起备份前检测权限，若未授予则弹出权限申请/引导对话框，授予后方可启动备份，确保通知百分之百触达。

5. **安全的队列“清空待办”中断控制**：
   - 在应用内顶部 `QueueProgressBanner` 提供「清空待办」按钮；
   - 点击后仅清空后续尚未开始的任务列表，允许当前正在执行的单个应用/目录完整收尾（保证 `restorecon`、`umount` 和 Restic snapshot 完整性），随后安全退出。

---

## 二、 详细实施步骤

### 阶段 1：遗留模块大扫除与项目瘦身

#### 1. 物理删除废弃模块目录
- [ ] 删除 `src/main/java/com/anlite/backup/manager/`（共 38 个旧版 Task/Action/Handler 文件）；
- [ ] 删除 `src/main/java/com/anlite/backup/viewmodels/`（共 10 个旧版 ViewModel 文件）；
- [ ] 删除 `src/main/java/com/anlite/backup/ui/pages/`（旧版 Compose 多页面）；
- [ ] 删除 `src/main/java/com/anlite/backup/ui/navigation/`（旧版导航路由）；
- [ ] 删除与旧版短信相关的资源文件（如 `res/drawable/ic_sms.xml`、`res/drawable/ic_call_logs.xml` 等无用图标）。

#### 2. 清理 `AndroidManifest.xml`
- [ ] 移除短信/彩信权限：`READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, `READ_CALL_LOG`, `WRITE_CALL_LOG` 等；
- [ ] 移除旧组件声明：
  - `MmsReceiver`
  - `SmsReceiver`
  - `HeadlessSmsSendService`
  - `ComposeSms` Activity
  - `CommandReceiver`, `BootReceiver`, `ScheduleReceiver` 等废弃旧接收器；
- [ ] 保留并规范核心前台服务权限：
  - `POST_NOTIFICATIONS`
  - `WAKE_LOCK`
  - `FOREGROUND_SERVICE`
  - `FOREGROUND_SERVICE_DATA_SYNC`。

#### 3. 重构并净化核心入口
- [ ] **重构 `AnLiteActivity.kt`**：
  - 移除未初始化的 `navStack`、`openDialog`、`dialogKey`；
  - 移除基于 WorkManager 的旧方法（`startBatchAction`、`startBatchRestoreAction` 等）；
  - 保留纯净的 `setContent { AppTheme { MainScreen(...) } }` 入口；
- [ ] **净化 `AnLiteApp.kt`**：
  - 移除 Koin 中的 `handlersModule` 与 `viewModelsModule`，仅保留 `coreModule`、`databaseModule`、`prefsModule`；
  - 清理无用的旧日志计数与 `busyCountDown` 轮询定时器。

#### 4. 精简 Gradle 依赖 (`build.gradle.kts`)
- [ ] 移除 `pgpainless` 依赖项（Restic 自带全流程加密，无需外部 PGP）；
- [ ] 移除 `kaml` 依赖项（废弃旧 yaml 序列化）；
- [ ] 移除无用的 Navigation3 依赖，保持依赖树最小化。

---

### 阶段 2：Android 13+ 通知权限拦截机制

- [ ] **权限检查工具扩展**：
  - 在 `PermissionUtils.kt` 中封装 `checkNotificationPermission(context): Boolean`；
- [ ] **UI 交互拦截层**：
  - 在 `MainScreen.kt` 中集成权限请求契约（`rememberLauncherForActivityResult`）；
  - 当用户在「应用管理」点击“立即备份”、在「目录备份」点击“备份”、或在快照页点击“批量还原”时：
    1. 判断系统版本（SDK >= 33）是否已获得 `POST_NOTIFICATIONS`；
    2. 若未获得，弹出 Material 3 权限说明引导弹窗（告知需要常驻通知监控备份进度与保活）；
    3. 引导用户授权；若被永久拒绝，提供直达系统设置页面的引导；
    4. 只有在权限获得后，才将任务派发至 `SequentialBackupQueue`。

---

### 阶段 3：`BackupForegroundService` 与 WakeLock 保活引擎

#### 1. 服务类架构设计
- [ ] 创建 `com.anlite.backup.core.service.BackupForegroundService`：
  - 继承自 `android.app.Service`；
  - 内部持有 `PowerManager.WakeLock`（Tag: `AnLiteBackup:WakeLock`）；
  - 注入 `SequentialBackupQueue`；
- [ ] 在 `AndroidManifest.xml` 中注册：
  ```xml
  <service
      android:name=".core.service.BackupForegroundService"
      android:exported="false"
      android:foregroundServiceType="dataSync" />
  ```

#### 2. Android Live 实时通知适配与回退
- [ ] 创建专用通知渠道（`CHANNEL_ID = "anlite_backup_progress"`，重要度为 `IMPORTANCE_LOW`，禁止重复震动提示）；
- [ ] **动态探测构建通知**：
  - 点击通知动作（`PendingIntent`）：使用 `Intent(this, AnLiteActivity::class.java)`，设置 `FLAG_ACTIVITY_SINGLE_TOP`；
  - **Live 通知探测**：在 Android 16 (API 36) 或适配系统上，尝试应用 Live Update / Ongoing Progress 规范；
  - **标准回退通知**：使用 `NotificationCompat.Builder`：
    - `setOngoing(true)`
    - `setCategory(NotificationCompat.CATEGORY_PROGRESS)`
    - `setProgress(100, (percent * 100).toInt(), false)`
    - `setContentTitle(if (isRestore) "AnLite 正在还原" else "AnLite 正在备份")`
    - `setContentText("[$currentIndex/$totalCount] $taskLabel - $stepMessage")`
    - 不放置额外 Action 按钮。

#### 3. 联动生命周期
- [ ] 当 `SequentialBackupQueue` 启动任务批处理时，调用 `startForegroundService`；
- [ ] `BackupForegroundService` 收集 `queueProgress` Flow 动态刷新通知；
- [ ] 当队列 `isRunning == false` 且任务完成时，释放 WakeLock，调用 `stopForeground(STOP_FOREGROUND_REMOVE)` 并 `stopSelf()`。

---

### 阶段 4：安全清空待办（取消控制）

#### 1. 队列控制层 (`SequentialBackupQueue.kt`)
- [ ] 增加 `clearPendingTasks()` 方法：
  - 清空通道内积压的任务列表；
  - 设置 `isCancelRequested = true` 标志；
  - 当前正在运行的任务**不强行终止**，正常等待底层执行完恢复/校验/卸载并释放资源；
  - 在当前单项任务收尾后，立即退出循环并触发完成回调，防止产生损坏的中间态快照。

#### 2. ViewModel 与 UI 横幅联动
- [ ] `AppsViewModel` 暴露 `cancelPendingTasks()` 方法；
- [ ] 在 `MainScreen.kt` 的 `QueueProgressBanner` 悬浮横幅中，增加「取消后续」文本按钮：
  - 点击后触发 `viewModel.cancelPendingTasks()`；
  - 横幅提示切换为：“正在收尾当前任务，后续任务已取消...”。

---

## 三、 验证与验收标准

1. **编译构建验收**：
   - 运行 `./gradlew assembleDebug`，编译零错误，APK 体积显著下降（废弃代码与 PGP 库移除）。
2. **长任务熄屏保活验收**：
   - 勾选 20+ 个应用发起批量备份；
   - 立即锁屏并静置 5 分钟；
   - 唤醒手机观察通知栏进度与手机日志，确认后台保持稳定执行，无 LMK 杀进程与中断现象。
3. **通知交互验收**：
   - 点击状态栏通知，能够直接拉起 `AnLiteActivity` 界面；
   - 在 Android 13+ 首次触发备份时，能正确触发强校验拦截并弹出授权指引。
4. **清空待办安全验收**：
   - 在批量备份中途点击「取消后续」；
   - 验证当前正在备份的应用顺利写完数据库与卸载挂载点，后续排队任务立即停止，无孤儿挂载残留与仓库锁死。
