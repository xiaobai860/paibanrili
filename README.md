# 排班日历

一款基于 Jetpack Compose 开发的 Android 排班与薪资管理应用，帮助倒班/轮班工作者记录班次、打卡考勤、统计工时与薪资。

---

# 第一部分：软件功能与特点

## 功能特性

### 日历排班
- 月视图展示每日班次，支持滑动切换月份
- 自定义班次管理（名称、颜色、时间段）
- 批量排班、复制排班、清除排班
- 排班规则自动应用（循环排班）
- 附加状态标记（请假、调休、休息）
- 系统日历同步（日程/纪念日）
- 农历、黄历展示（含独立黄历页）

### 上下班打卡
- 桌面小组件快捷打卡
- 漏打卡检测与补录
- 早到/晚退加班自动识别与确认
- 跨午夜班次自动处理

### 工时统计
- 正常/加班/周末/节假日工时分类统计
- 附加状态可勾选「计为加班」（**仅当天生效**），加班工时按当天「计薪方式」归类（工作日→加班、周末→周末、节假日→节假日）
- 迟到/早退次数统计与超限提醒
- 每日工时柱状图 & 月度趋势图
- 备注与补贴/扣款记录

### 薪资计算
- 基础底薪 + 绩效自动核算
- 正常/加班/周末/节假日工资分项计算
- 补贴与扣款管理
- 社保/公积金/个税扣除
- 薪资构成饼图 & 月薪趋势折线图
- 当月预计薪资实时显示

### 待办中心
- 漏打卡提醒
- 加班待确认事项
- 日程与纪念日管理
- 法定节假日展示（含调休）

### 桌面小组件
- 3x3 / 3x4 日历组件（当月日期 + 班次 + 附加状态）
- 2x1 排班/打卡组件（上下班打卡按钮 + 明日班次 + 节假日倒计时）
- 组件外观配置页（文字/背景颜色、独立透明度）
- 事件驱动刷新 + 15 分钟周期兜底刷新

### 提醒
- 上下班打卡提醒（精确闹钟，预调度「过去 3 天 ~ 未来 7 天」共 11 天窗口）
- 纪念日提醒

### 其他
- 数据自动备份（轻量指纹判断，数据未变不重复写盘）
- Material You 动态主题

## 技术特点
- 全 Jetpack Compose UI（含 Glance 组件），MVVM + Hilt 依赖注入
- 类型安全 Navigation 路由
- Room + WAL 模式，高频计算带 LRU 缓存
- 高频重活全部移出主线程（后台协程 + 去抖 + 脏数据指纹判断）

---

# 第二部分：开发指南

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3 |
| 架构 | MVVM + Hilt 依赖注入 |
| 数据库 | Room（当前版本 7，导出 schema） |
| 偏好存储 | DataStore（`app_config`）+ SharedPreferences（仅组件数据缓存） |
| 导航 | Navigation Compose（类型安全路由） |
| 异步 | Kotlin Coroutines + Flow |
| 桌面组件 | Glance 1.2.0 + WorkManager 2.12.0 |
| 序列化 | Gson（配置/组件数据 JSON）+ kotlinx-serialization（Navigation 路由） |
| 农历/节气/黄历 | tyme4j 1.5.2（`cn.6tail:tyme4j`，依据紫金山天文台《农历的编算和颁行》） |
| 单元测试 | JUnit 4（13 个测试类 / 117 例：法定节假日表 + 历法数据对账 + 加班判定与三档计薪金额映射 + 计算口径档案区间选择 + 备份反序列化默认值 + 备份往返 + 数据格式栅栏 + ProGuard 规则） |
| 仪器测试 | AndroidX Test Runner（5 个测试类，需连真机：档案删除残留 / 孤儿数据清理 / 薪资考勤一致性） |

## 构建环境与版本信息

### 构建参数（`app/build.gradle.kts`）
- `compileSdk = 37`，`minSdk = 34`（Android 14+），`targetSdk = 36`
- Java 17 编译目标；release 开启 R8 混淆 + 资源收缩
- Room schema 导出目录：`app/schemas/`（只保留当前版本 7；栅栏已抬到 v7，不再需要历史 schema）

### 版本号规则
- `versionName`：展示用版本号 = `年月日 + 两位当天迭代号`，如 `2026091001`（设置页展示的就是它）
- `versionCode`：系统用的递增整数，**每次发版 +1**，与 `versionName` 无关
- 唯一真值是 `app/build.gradle.kts` —— **文档不再抄录当前值**，省得每次发版都要同步两份
- ⚠️ 覆盖安装陷阱（2026-09-14 实测，三条都踩过）：
  - 设备上曾装过**日期型**版本（`2026083103`），远大于小整数计数器 → versionCode 一旦低于设备已装版本，
    覆盖安装直接报 `INSTALL_FAILED_VERSION_DOWNGRADE`
  - **`-d` 对 release 包无效**：`adb install -d` 的官方语义是 “allow version code downgrade
    (**debuggable packages only**)”，别指望它
  - **绝不能用 `pm uninstall -k`**：`-k` 会保留数据目录并把被卸载包的版本号记进系统，之后重装会报
    `Downgrade detected on app uninstalled with DELETE_KEEP_DATA`，等于**把降级路径锁死**，比不卸载更糟
  - ✅ 正确做法：**完整卸载（不带 `-k`）→ 安装**
    ```bash
    adb shell pm uninstall com.schedulecalendar.app
    adb install -r app/build/outputs/apk/release/app-release.apk
    ```
- 发版前核对设备真值：`adb shell dumpsys package com.schedulecalendar.app | findstr /C:versionCode`
  （**不要只比对自家历史记录** —— 曾因此误判成"不会回退"）
- lint 已屏蔽：`HighAppVersionCode`、`IconLauncherShape`、`IconDuplicates`、`UnusedAttribute`、`NewerVersionAvailable`、`ReportShortcutUsage`；`app/lint.xml` 只对 `mipmap-anydpi-v26/ic_launcher*.xml` 的 `ObsoleteSdkInt` 做了**定向豁免**（不要整目录屏蔽）

### 构建命令
```bash
# 调试版
./gradlew assembleDebug

# 发布版（改过资源/混淆敏感代码后务必加参数关闭缓存）
./gradlew assembleRelease --rerun-tasks --no-build-cache

# 单元测试（117 例：节假日/历法/计薪/档案/备份格式守护；改过 HolidayData / LunarCalendar / CalcUtils / 备份载荷后必跑）
./gradlew testDebugUnitTest

# 仪器测试（5 例，必须连真机；改过档案删除、孤儿数据清理、薪资考勤一致性后必跑）
./gradlew connectedDebugAndroidTest

# Lint
./gradlew lintDebug
```

> **Windows / PowerShell 下**：必须先给 Gradle 指定 JDK17（否则可能用错 JDK），且要用**单条 `cmd /c "…"`** 串联，
> 不要用 `;` 分隔多条命令（PowerShell 会解析报错）：
>
> ```cmd
> cmd /c "set JAVA_HOME=<JDK17 路径>&& cd /d <项目绝对路径>&& gradlew.bat assembleRelease --rerun-tasks --no-build-cache --console=plain"
> ```
>
> `adb` 路径不含空格时，PowerShell 里**直接写不带引号的全路径**即可（加引号会报 `UnexpectedToken`）。

发布版签名配置存储在 `local.properties` 中（不纳入版本控制）：

```properties
RELEASE_STORE_FILE=/path/to/keystore
RELEASE_STORE_PASSWORD=your_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_password
```

### ⚠️ R8/ProGuard 关键注意事项
`app/proguard-rules.pro` **只保留必要规则**（当前 9 条）：Hilt / Room / Gson / coroutines / WorkManager / Glance
的通用规则**已由各库自带的 consumer 规则覆盖**（AGP 自动合并 AAR/JAR 内的 proguard 文件），不再重复声明。

**必须永久保留**（删掉会出事）：

| 规则 | 为什么不能删 |
|---|---|
| `-keep class com.schedulecalendar.app.domain.model.**` | 领域模型的**字段名会进用户数据**（备份 JSON / DataStore），混淆即数据损坏 |
| `-keep class com.schedulecalendar.app.widget.**` | ① 组件数据以 JSON 存在 SharedPreferences；② **Glance 靠类名反射实例化我们的 `ActionCallback`**（类名被改 → 点按钮静默失效）；③ WorkManager 靠类名实例化 `WidgetRefreshWorker` |
| `-keep class ...data.compat.AppDataBackup` / `ShiftConfigBackup` | 备份载荷类的字段名就是**数据格式**（曾出事故：release 包导出的备份全部不可恢复） |
| `-keep class ...data.repository.AppliedStatusJson` | 其字段名被直接写进 Room 的 `appliedStatusesJson` 列 |
| `-keepnames class ...ui.navigation.Route*` | 类型安全路由的 serialName 取自类全名，改名会导致 route 串不匹配 |
| `-keepattributes *Annotation*` | kotlinx-serialization（Navigation 路由）需要运行时可见的 `@Serializable` 注解 |
| `-keepattributes Signature` | Gson 反序列化集合字段要读泛型签名（`List<Shift>` → `Shift`） |
| `-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; }` | Navigation 的 `serializer<T>()` 靠 companion 上的 `serializer()` 反射解析 |

> **Glance 不再整包 keep**（2026-10-26 审计）：`glance` / `glance-appwidget-external-protobuf` 自带
> 「safe to shrink」声明，`glance-appwidget` 的 consumer 规则已保 protobuf 字段与 ActionCallback 子类，
> 而 Glance 依赖的反射入口（`ActionTrampolineActivity` / `ActionCallbackBroadcastReceiver` 等）
> 都在其 AAR manifest 里声明 → 由 AGP 自动 keep。**去掉后 APK 从 7,242,117 B 降到 5,898,356 B（-18.6%）**，
> R8 零告警，真机小组件在新包下重新组装成功。
> ⚠️ 升级 Glance 版本后必须重跑：构建无 R8 告警 + 三个组件能新增/刷新 + 点组件按钮（打卡/刷新/打开 App）能触发。

**改动这些规则后必须真机验证**（Hilt 注入 / Room 打开 / 小组件加载 / WorkManager 运行），
并看 R8 映射表 `app/build/outputs/mapping/release/mapping.txt`：
- 保留类应写作 `com.x.Y -> com.x.Y:`（**原名 + 尾冒号**）
- **该类块内不应出现任何成员行** —— 成员没被改名就不会被写进映射表；
  一旦出现 `getShifts() -> b` 这类行，就说明字段名被混淆了（备份 JSON 会失效）

## 项目结构

```
app/src/main/java/com/schedulecalendar/app/
├── MainActivity.kt          # 唯一 Activity：权限引导 + AppNavHost + 组件跳转日期
├── ScheduleApp.kt           # @HiltAndroidApp：数据变更→刷新组件；WorkManager 兜底
├── data/                    # 数据层
│   ├── db/                  #   AppDatabase(v7) + 6 Entity + 6 DAO
│   ├── compat/              #   兼容治理层：DataFormat(版本真相源) / BackupFormat(格式探测+闸门)
│   │                        #   / BackupPayloads(当前格式载荷) / CompatGate(启动闸门) / DataIntegrityChecker(校验)
│   ├── calendar/            #   系统日历集成（认证器 + CalendarProvider 仓库）
│   ├── repository/          #   仓库层（Entity↔Domain 映射 + 变更信号 Flow）
│   └── prefs/               #   AppPreferences（DataStore 封装）
├── domain/                  # 领域层（纯 Kotlin，无 Android 依赖）
│   ├── model/               #   Models / CalcUtils / CalcInputs / CalcProfile
│   │                        #   / HolidayData / LunarCalendar
├── di/                      # Hilt Module（DatabaseModule / PreferencesModule）
├── reminder/                # 提醒：调度器 + 4 个 BroadcastReceiver
├── ui/                      # Compose 界面层（Screen + ViewModel + 导航 + 主题）
│   ├── calendar/            #   已按职责拆分：CalendarScreen / CalendarDialogs / CalendarCells
│   ├── compat/              #   CompatOverlay（数据格式闸门不通过时的阻断遮罩）
│   ├── detail/              #   排班详情页（ScheduleDetailScreen）
│   ├── hours/               #   工时明细页（HoursDetailScreen）
│   ├── salary/              #   薪资相关页
│   ├── statistics/          #   统计页（工时/薪资图表）
│   ├── shifts/              #   班次管理 + 编辑器 + 显示方案
│   ├── settings/            #   设置及子页（薪资/考勤/日历账户/提醒/组件/存储/数据管理）
│   ├── todo/                #   事项页（日程/纪念日）
│   └── navigation/          #   AppNavHost + 类型安全路由（Route*）
└── widget/                  # Glance 小组件 + 数据同步 + 周期刷新 Worker

app/src/test/java/com/schedulecalendar/app/          # 单元测试（13 个测试类，共 117 例）
│   ├── data/compat/            #   BackupRestoreRoundTrip / DataFormatFence / ProguardBackupRules
│   ├── data/repository/        #   ReferenceIdParser
│   ├── domain/model/           #   CalcProfile / CalcUtilsAttendance / CalcUtilsOvertime
│   │                           #   / CalcUtilsProfile / CalcUtilsSalaryTier
│   │                           #   / HolidayData / HolidayDataInvariants / TymeAlmanac
│   └── ui/settings/            #   BackupGson
app/src/androidTest/java/com/schedulecalendar/app/  # 仪器测试（5 个测试类，需连真机）
└── data/repository/            #   BreakProfileLifecycle / OrphanDataCleaner
                                 #   / ProfileDeleteAndCopy / ProfileDeletionResidue
                                 #   / SalaryAttendConsistency
```

### 数据层（`data/`）
- **Room**（`AppDatabase`，版本 7，库名 `schedule_calendar.db`，WAL 模式）：
  - Entity：`ShiftEntity`、`ScheduleRecordEntity`、`ExtraItemEntity`、`ShiftBreakEntity`、`ShiftStatusEntity`、`CalcProfileEntity`
  - DAO：`ShiftDao`、`ScheduleRecordDao`、`ExtraItemDao`、`ShiftBreakDao`、`ShiftStatusDao`、`CalcProfileDao`
  - 升级注意：**只接受数据格式 v7 及以后**（`DataFormat.MIN_SUPPORTED_DB_VERSION`）；更老的库在启动期被 `CompatGate` 判为 `Unsupported` 并阻断界面，不会静默清库、也不会被"尽力解析"
- **仓库层**：各 Repository 在 Entity↔Domain 映射基础上暴露 `refreshSignal` / `changeSignal`（SharedFlow），供全局监听数据变化
- **DataStore**（`AppPreferences`）：全部应用配置（薪资/考勤/排班规则/显示方案/组件配置/备份设置），复杂对象 Gson 序列化
- **SharedPreferences**：仅组件数据缓存（`calendar_widget_data_prefs`）与组件外观（`widget_config_prefs`）
- **系统日历**：`CalendarEventRepository` 读写 CalendarProvider；`CalendarAuthenticator` 提供可见的自定义账户

### 数据备份与还原（`ui/settings/`，入口：设置 → 数据管理）
两种备份均为 **Gson JSON 文件**（非 DB 快照），恢复按顶层 key 自动识别类型：

| 备份类型 | 载体 | 内容 | 保留策略 |
|---|---|---|---|
| 应用数据（全量） | `AppDataBackup`（当前 version=5） | DB 全部 6 张表（含计算口径档案）+ 薪资/考勤/排班规则/显示方案/排序/颜色索引/提醒设置/账户分类与禁用账户 | 按**天**保留（每天 1 份最新，默认 5 天），手动备份（`_manual`）不参与裁剪 |
| 班次配置（可分享） | `ShiftConfigBackup`（当前 version=7） | 班次/休息时段/附加状态/补贴项 + 排序 + 颜色索引（**不含**排班记录与薪资考勤口径） | 按**份**保留最近 N 份（默认 10），手动备份不参与裁剪 |

- **自动备份**：应用数据每次启动做一次（轻量指纹：表计数 + 档案计数 + 全部备份覆盖的偏好哈希，未变则跳过写盘）；班次配置在每次增删改后立即生成
- **不在备份范围**（均为设备相关或可重建数据）：组件数据缓存与外观配置、备份设置自身（保留份数/自定义路径/指纹）、一次性初始化标记、**系统日历中的纪念日/日程**（存 CalendarProvider，随系统日历账户存续）
- **还原语义**：字段存在即**整表替换**（与弹窗「当前数据会被覆盖」一致）；备份里缺该字段时该项**保持现状**，绝不清空用户数据；恢复前自动生成一份 `_manual` 快照可回退；DB 事务成功后才写偏好
- **版本闸门（不再兼容旧格式）**：只接受**当前格式**（应用数据 v5 / 班次配置 v7）；更低版本的备份**直接拒绝**，并提示装兼容版（224 / 2026102503）导出后再升级 —— 不做「尽力解析」（那会让历史工时/薪资静默算错）。可空字段策略仍区分「缺字段」与「真空列表」；`BackupGson` 注册 InstanceCreator，缺非空字段时落 Kotlin 默认值（否则撞 NOT NULL 约束）。详见 `docs/数据兼容与迁移策略.md`
- **存储位置**：默认应用私有目录；可设 SAF 自定义目录，**写入失败（介质摘除/权限失效）自动回退私有目录**，保证任何情况下当天都有可恢复的备份

### UI 层（`ui/`）
底部导航 5 个 Tab（定义在 `ui/navigation/AppNavHost.kt`）：

| Tab | 路由 | 主要 Screen |
|-----|------|-------------|
| 日历 | `RouteCalendar` | `CalendarScreen`、`HuangLiScreen`（黄历） |
| 事项 | `RouteTodo` | `TodoScreen`、日程/纪念日增改页 |
| 统计 | `RouteStatistics` | `StatisticsScreen`（工时/薪资图表） |
| 班次 | `RouteShifts` | `ShiftsScreen`、`ShiftEditorScreen` |
| 设置 | `RouteSettings` | `SettingsScreen` + 薪资/考勤/日历账户/提醒/组件/存储等子页 |

详情与二级页：`ScheduleDetailScreen`、`HoursDetailScreen`、`ExtraItemsScreen`、`DisplaySchemesScreen`（显示方案）。

### 小组件（`widget/`）
| 组件 | 尺寸 | 说明 |
|------|------|------|
| `CalendarGlanceWidget` | 3x3（可拉至 240dp 宽） | 当月网格 + 班次 + 附加状态 |
| `Calendar3x4GlanceWidget` | 3x4 | 同上，更高 |
| `ScheduleGlanceWidget` | 2x1（不可拉伸） | 打卡按钮 + 明日班次 + 倒计时 |

- `WidgetSync.syncAllWidgets()`：组件数据刷新统一入口（回源重算，Mutex 串行化）
- `WidgetRefreshWorker`：15 分钟周期兜底刷新（跨天/打卡窗口/倒计时）
- `WidgetConfigActivity`：组件外观配置页
- 数据一致性：打卡记录与组件数据分离存储，`updateWidgetData` 负责清理失效关联

### 提醒（`reminder/`）
- `ReminderScheduler`：`AlarmManager.setAlarmClock` 预调度「过去 3 天 ~ 未来 7 天」窗口内的上下班提醒
- `AlarmReceiver` / `AnniversaryReminderReceiver`：发通知（`goAsync` 防超时）
- `BootReceiver`：开机后重新调度
- `TimeChangeReceiver`：时间/时区变更后全量重调度

### 依赖注入（`di/`）
- `DatabaseModule`：`AppDatabase` + 6 个 DAO 单例
- `PreferencesModule`：`AppPreferences` 单例
- BroadcastReceiver / GlanceWidget 无法构造注入，统一通过 **Hilt EntryPoint**（`EntryPointAccessors.fromApplication`）获取依赖

## 法定节假日与黄历数据（数据来源与维护）

### 数据来源分工（2026-09-13 历法迁移后）

**只有「法定节假日 / 调休补班」是自研硬编码表**；节气、节日、黄历各字段**全部由 tyme4j 计算**。

| 数据 | 位置 | 来源 |
|------|------|------|
| 法定节假日（不上班） | `HolidayData.holidays` | 🔧 自研表（纯查表） |
| 调休补班日（周末上班） | `HolidayData.transferWorkdays` | 🔧 自研表（纯查表） |
| 节假日名称 | `HolidayData.holidayNames` | 🔧 自研表（日期 → 名称） |
| 覆盖年份 | `MIN_COVERED_YEAR` / `MAX_COVERED_YEAR` | 当前 2024–2030 |
| 二十四节气 | `HolidayData.getSolarTerm` | ✅ tyme4j `SolarDay.getTermDay()`（任意年份） |
| 传统节日 / 公历节日 | `getTraditionalFestival` / `getInternationalFestival` | ✅ tyme4j `getFestival()` + 少量本地补充 |
| 黄历全部字段 | `LunarCalendar.getFullHuangLi` | ✅ tyme4j |

> 原 2024–2030 的 `solarTermDates` 节气硬编码表（168 条）已**删除**。

**超范围兜底行为**：年份超出覆盖范围时，`isLegalHoliday` / `isMakeupDay` 恒为 false，
`CalcUtils.autoSalaryMode` 因此**退化为「仅按周末判断」**——不会把工作日误判成节假日（偏保守），
但识别不出调休补班（周末上班会按周末计薪）。可用 `HolidayData.isWithinCoverage(date)` 判断是否处于该退化路径。

> 该数据会直接决定「工作日 / 周末 / 节假日」归类，进而影响**工时统计与薪资计算**，务必保持准确。

**维护节奏**：每年国务院办公厅公告（通常前一年 11 月发布）后，更新 `holidays` /
`transferWorkdays` / `holidayNames` 三张表并上调 `MAX_COVERED_YEAR`。

### 单元测试守护

`app/src/test/java/com/schedulecalendar/app/` 下有 **13 个测试类、共 117 个用例**：

**历法与节假日（`domain/model/`）**

**`HolidayDataTest.kt`**（8 用例，守护自研节假日表）：

- **覆盖年份必须 ≥ 当前年 + 1**（会随时间自然失败，起「该更新数据了」的提醒作用）
- 各覆盖年份假期数下限、放假与补班不重叠、补班日必落在周末（已核验年份）
- 超出覆盖范围时退化为「仅按周末」，绝不误判为节假日
- 2024 / 2025 / 2026 与国务院公告逐条回归

**`HolidayDataInvariantsTest.kt`**（5 用例，守护节假日表的跨年不变式：放假日与补班日不得重叠、补班日必在周末、节假日名唯一等）

**`TymeAlmanacTest.kt`**（17 用例，守护历法数据 & 「确实用上了库」）：

- **法定节假日与 tyme4j 内置国务院公告逐日双向对账**（2024-01-01 ~ 2026-12-31）
- 节气：已知日期断言 + **2050 年仍须有完整 24 节气**（证明已脱离硬编码表）
- 节日：库覆盖项 + 本地补充项（小年、情人节 / 愚人节 / 圣诞节）都不丢
- 黄历字段取值合法性；12 时辰顺序与时间；冲煞逐日反推校验
- 七十二候 / 数九 / 三伏 / 梅雨取值范围；**三伏与数九严格互斥**（跨 21 年扫描）
- 星期与 JDK 公历逐日交叉验证

**计薪与考勤（`domain/model/`）**

**`CalcUtilsOvertimeTest.kt`**（7 用例，守护「计为加班」判定）：

- 勾选框 + 计薪方式 → 三档归类（工作日→加班工时、周末→周末工时、节假日→节假日工时）
- 未勾选不算加班；加班状态的时间段**不**从正常工时里扣除
- 内置「加班」附加状态**已下线**：不在 `BUILTIN_STATUSES` 里，且其固定 ID **不再**参与加班判定
- JSON 向后兼容：无 `isOvertime` 字段的旧数据解析为非加班

**`CalcUtilsSalaryTierTest.kt`**（12 用例，守护三档计薪的**金额**映射）：

- 三档各自用**自己**的时薪（工作日→加班时薪、周末→周末时薪、节假日→节假日时薪），任何串档都会立即暴露
- 自动档覆盖：普通工作日 / 周六周日 / 法定节假日（含**压在周末的节假日**）/ 调休补班日 / 超范围退化 /
  非法日期不抛异常
- **同源回归**：日历格子「加班收入」与「每日总收入」之和必须等于月汇总对应项
  （历史 bug 曾导致同一天金额在日历页与薪资页对不上，这条专门锁死它）

**`CalcUtilsAttendanceTest.kt`**（7 用例，守护迟到/早退次数与容忍时长口径）

**计算口径档案（`domain/model/`）**

**`CalcProfileTest.kt`**（11 用例）与 **`CalcUtilsProfileTest.kt`**（9 用例，守护「档案 + 生效日接力」）：

- 区间左闭右闭、相邻档案不重叠、按日期取当天生效档案
- 改档案不改历史工时/薪资（历史排班仍按当时生效的档案计算）
- 无档案时回退 DataStore 现值；跨月边界取档案正确

**数据兼容与备份（`data/compat/`、`data/repository/`、`ui/settings/`）**

**`DataFormatFenceTest.kt`**（11 用例，锁定数据格式栅栏**只升不降**：DB 版本 / 应用数据备份版本 / 班次配置备份版本）
**`BackupRestoreRoundTripTest.kt`**（11 用例，备份→还原→再备份的往返一致性）
**`ProguardBackupRulesTest.kt`**（5 用例，守护 keep 规则不被误删）：备份载荷类 / 数据库 JSON 列载体类 / 领域模型与小组件包都在 keep 名单里，且**小组件的 `ActionCallback` 子类**（Glance 靠类名反射实例化，漏了会「点按钮静默失效」）必须被覆盖；并禁止整包 keep 掩盖载荷挪包
**`BackupGsonTest.kt`**（7 用例，守护备份反序列化的默认值与可空字段策略）
**`ReferenceIdParserTest.kt`**（7 用例，守护归档记录引用的班次/状态 id 解析）

```bash
# 单元测试（116 例）
./gradlew testDebugUnitTest

# 仪器测试（5 例，必须连真机）
./gradlew connectedDebugAndroidTest
```

> `assembleRelease` **不会**执行单元测试；改完节假日数据、历法、计薪/加班逻辑、备份格式或数据格式栅栏后请手动跑一次。

### tyme4j 使用清单（2026-09-13 已完成历法迁移）

引用 `com.tyme.*` 的文件：`domain/model/LunarCalendar.kt`、`domain/model/HolidayData.kt`。

**生产逻辑已全部由库计算**：

| 数据 | 所用 API |
|------|----------|
| 农历日期、闰月、干支纪年/月/日、生肖 | `SolarDay.getLunarDay()` → `getSixtyCycleDay()` / `getEarthBranch().getZodiac()` |
| 农历 → 公历 | `LunarDay.fromYmd().getSolarDay()` |
| 黄历「宜 / 忌」 | `LunarDay.getRecommends()` / `getAvoids()` |
| **二十四节气** | `SolarDay.getTermDay()`（`getDayIndex()==0` 即节气当天），已取代原 2024–2030 硬编码表 |
| **传统节日** | `LunarDay.getFestival()`（13 个，依据 GB/T 33661-2017） |
| **公历现代节日** | `SolarDay.getFestival()`（10 个） |
| **彭祖百忌** | `SixtyCycle.getPengZu().getName()`（八字双句，如「庚不经络织机虚张 寅不祭祀神鬼不尝」） |
| **逐日胎神** | `LunarDay.getFetusDay().getName()` |
| **二十八宿** | `LunarDay.getTwentyEightStar()`（宿名+七曜+动物+吉凶，如「角木蛟（吉）」） |
| **建除十二值神** | `LunarDay.getDuty().getName()` |
| **值神（黄道黑道十二神）** | `LunarDay.getTwelveStar()`（`getEcliptic()` 区分黄/黑道） |
| **吉神宜趋 / 凶神宜忌** | `LunarDay.getGods()` 按 `getLuck()` 拆分 |
| **纳音五行** | `SixtyCycle.getSound().getName()` |
| **12 时辰吉凶** | `SolarDay.getSixtyCycleDay().getHours()`（固定子→亥顺序）+ `SixtyCycleHour.getTwelveStar()` |
| 七十二候 / 三候 / 数九 / 三伏 / 梅雨 | `SolarDay.getPhenologyDay()` / `getNineDay()` / `getDogDay()` / `getPlumRainDay()` |
| **冲的生肖 / 煞方** | `EarthBranch.getOpposite()`（六冲）/ `getOminous()`（三合局煞方） |
| **日吉凶（黄道 / 黑道）** | `LunarDay.getTwelveStar().getEcliptic()`（黄道吉日 / 黑道日，替代了原先按宜忌条数自造的"大吉/平"） |
| 星期 | `SolarDay.getWeek()`（`LunarCalendar.getWeekDayText()`） |

**仍为本地实现（含原因）**：

| 数据 | 做法 | 原因 |
|------|------|------|
| 法定节假日 / 调休补班 | 自研硬编码表（2024–2030），**生产以此为主** | 库数据止于 2026-10-10，无法支撑 `MAX_COVERED_YEAR = 2030`；库数据仅用于单元测试对账（`TymeAlmanacTest.statutoryHolidays_matchTyme4jOfficialNotice`） |
| 北方小年 / 南方小年 | `getTraditionalFestival` 本地补充 | 库 `LunarFestival` 未收录腊月廿三/廿四 |
| 情人节 / 愚人节 / 圣诞节 | `getInternationalFestival` 本地补充 | 库 `SolarFestival` 仅 10 个，未收录这 3 个 |
| 八字 / 回历 / 藏历 / 九星 | 未使用 | 无对应需求 |

> ⚠️ 历史遗留说明：迁移前的胎神/二十八宿/建除/值神/吉神凶煞是本地查表，
> 其中吉神凶煞是占位算法（与真实历法无关），十二时辰的起止时间也整体错位一小时，
> 迁移后一并修正。

### 黄历页（`HuangLiScreen`）字段构成

**所有显示项均由 tyme4j 计算，无自研历法字段**：

| 区块 | 字段 |
|------|------|
| 顶部 Hero | 农历月日、干支（年 · 月 · 日）、星期、节气 / 节日标签、宜、忌 |
| 今日信息 | 胎神、冲的生肖与煞方、彭祖百忌、吉神宜趋、凶神宜忌、建除十二值神、值神、二十八宿、纳音五行、七十二候、数九 / 三伏、梅雨 |
| 吉时 | 12 时辰（干支 + 时间 + 黄道 / 黑道吉凶） |

**行布局约定**：靠「时间互斥性」决定两个字段能否共用一行，已由测试锁定：

| 组合 | 关系 | 布局 |
|------|------|------|
| 数九 ↔ 三伏 | **严格互斥**（三伏 7~8 月、数九 12~3 月） | 共用一格，标签随季节切换 |
| 三伏 ↔ 梅雨 | **不互斥**（2020~2040 年间有 5 年重叠） | 梅雨独立一行 |
| 梅雨 ↔ 数九 | 不互斥 | 梅雨独立一行 |

> 依据 `TymeAlmanacTest.dogDay_neverOverlapsNineDay_butRelationshipWithPlumRainIsEmpirical`：
> 扫描 2020~2040 年，三伏共 780 天，与数九重叠 **0** 天，与梅雨重叠 16 天。

### tyme4j 能力清单

`cn.6tail:tyme4j` 1.5.2 的包级能力与可用性评估：

| 包 / API | 能力 | 可用性 |
|----------|------|--------|
| `com.tyme.solar` / `com.tyme.lunar` | 公历↔农历互转、干支纪年/月/日/时 | ✅ **已在用**（`LunarCalendar`） |
| `SolarDay#getTerm` / `getTermDay` | **节气**（天文算法，任意年份，无需维护表） | ✅ **已在用**（`HolidayData.getSolarTerm`） |
| `com.tyme.festival.LunarFestival` | **农历节日**（春节/端午/中秋…） | ✅ **已在用**（`getTraditionalFestival`） |
| `com.tyme.festival.SolarFestival` | **公历节日** | ✅ **已在用**（`getInternationalFestival`） |
| `com.tyme.holiday.LegalHoliday` | **法定节假日 / 调休**，内置数据覆盖 **2001-12-29 ~ 2026-10-10**；提供 `isWork()` / `getName()` / `getTarget()` | ⚠️ 仅用于单元测试对账：数据止于 2026-10-10，**不含 2027+**，生产仍以自研表为主 |
| `com.tyme.culture.*`（pengzu / fetus / star.* / phenology / nine / dog / plumrain…） | 彭祖百忌、胎神、二十八宿、九星、七十二候、数九、三伏、梅雨等 | ✅ **已在用**（`LunarCalendar` 黄历各字段 + 七十二候/数九·三伏/梅雨） |
| `com.tyme.eightchar` | 八字排盘（四柱 / 大运 / 流年） | ⭕ 如有需求可用 |
| `com.tyme.sixtycycle` | 六十甲子 | ✅ 已间接使用 |
| `com.tyme.hijri` / `com.tyme.rabbyung` | 回历 / 藏历 | ⭕ 大概率用不上 |

> `LegalHoliday.DATA` 是一个 `public static` 压缩字符串，每条记录 13 字符
> （`yyyyMMdd` + 是否上班位 + 节日索引 + 正负偏移 + 天数）。理论上可**追加**自备的未来年份数据，
> 但修改库的静态字段属于非常规用法，采用前需评估。

## 计算口径档案（改配置不影响历史计算）

### 为什么需要

项目里有两类会影响统计计算的数据，机制完全不同：

| 类别 | 实体 | 改动的历史影响 | 处理方式 |
|---|---|---|---|
| **有引用锚点** | 班次 / 附加状态 / 补贴扣款 | 排班记录里存了 id（`record.shiftId` / `appliedStatus.statusId` / `extraItemIds`），改实体 = 新版本 | **归档旧记录 + 生成新 UUID 的新记录**；历史排班仍引用旧 id → 历史工时/薪资天然不变 |
| **无引用锚点** | 薪资配置 / 考勤配置 / 休息时段 | 不挂在任何排班上，改一次就追溯影响**所有**历史 | **配置档案 + 生效日接力**（见下） |

> 2026-10-23 全量审查发现：计算侧曾误用「仅有效班次集合」，导致历史排班解析不到班次 → 工时/薪资变 0，已修复。

### 档案模型（`domain/model/CalcProfile.kt`）

```kotlin
data class CalcProfile(
    val id: String,
    val name: String,                       // 展示名，如「2026-10 调薪」
    val salary: SalaryConfig,               // 薪资配置快照
    val attend: AttendConfig,               // 考勤配置快照
    val breaks: List<ShiftBreak>,           // 全局休息时段集合快照
    val effectiveFrom: String,              // 生效日 yyyy-MM-dd（含）
    val effectiveTo: String? = null,        // 失效日 yyyy-MM-dd（含），null = 至今
    val note: String? = null,
    val createdAt: Long = 0L
)
```

- 存储在 `calc_profiles` 表（`CalcProfileEntity`），三块配置以 JSON 快照形式存一份
- 区间语义 **左闭右闭** `[effectiveFrom, effectiveTo]`；接力时旧档案的 `to` = 新档案生效日**前一天**，
  相邻档案首尾相接、**既不重叠也不留空洞**
- 档案**不可物理删除**（历史可能仍指向它），只能归档（= 把 `effectiveTo` 设为归档日 −1）
- 排班记录保持**唯一时间轴**，不按档案分区（否则切档案后历史排班会「消失」）
- **班次 / 附加状态 / 补贴扣款不档案化**：它们有引用锚点，「引用即版本」更精确

### 计算链路

按日取当天生效的档案，再逐日计算：

| API | 用途 |
|---|---|
| `CalcUtils.calcMonthHoursByDate` | 按日分段统计月工时 |
| `CalcUtils.calcMonthSalaryByDate` | 按日分段统计月薪资 |
| `CalcUtils.getMonthScheduleDetailsByDate` | 日历格子 / 月明细（同一套口径） |

- 没有档案时**回退 DataStore 现值**（保证任何时候都能算出来）
- 详情页与月汇总必须用**同一套**口径，否则会出现「同一天金额两处不同」

### 改动流程（不可跳过）

1. **复制当前档案** → 替换改动项 → 选生效日（今天 / 下月 1 日）→ 保存（生成新档案并接力旧档案）
2. **绝不原地覆盖** 已有档案
3. **休息时段编辑必须先建档案再落库**（建档失败则撤销），避免「表改了但计算没生效」
4. **清空数据要同时清档案**；**备份必须带上 `calcProfiles`**

> 曾经的坑：直接改表里的薪资/考勤配置 → 历史月份工时薪资一起变，用户完全无法察觉。

## 计薪规则与加班判定（2026-09-14 定稿）

### 三档计薪 + 正班（金额映射）

计薪**档位只有三档**，每档用**自己**的时薪；「正常时薪」只用于正班工时，**不是**计薪档位：

| 计薪档位 | 判定（自动档见下） | 工时桶 | 时薪 |
|---|---|---|---|
| 工作日 | 非法定节假日、非周末、非调休补班日 | 加班工时 | **加班时薪** |
| 周末 | 周六/周日，且非节假日、非补班日 | 周末工时 | **周末时薪** |
| 节假日 | 法定节假日（优先于周末判定） | 节假日工时 | **节假日时薪** |
| （正班） | 工作日档内不超过「正常班时长」的部分 | 正常工时 | 正常时薪 |

⚠️ **唯一实现是 `CalcUtils.calcDaySalaryParts(hours, cfg): DaySalaryParts`**
（`bonusTotal` = 三档合计、`total` = 当日工时薪资）。**所有**金额计算都必须经它：
`calcMonthSalary` / `calcMonthSalaryByDate`、`getMonthScheduleDetails` / `getMonthScheduleDetailsByDate`
（日历格子「加班收入」/ 每日总收入）、`DetailViewModels`（详情页薪资行）。

> 历史 bug：后三处曾各自实现，把周末/节假日工时**一律按加班时薪**计价，与月汇总不一致 →
> 同一天的金额在日历页和薪资页显示不同。新增同源回归测试锁死。

> ⚠️ **计算侧必须用「含归档」的集合**：`getAllIncludingArchivedWithBuiltin()`。
> 历史排班引用的是**已归档**的班次/状态，若用「仅有效集合」解析，历史记录会解析不到班次 → 工时/薪资变 0。
> **UI 选择侧**（下拉、排序、重名校验）才用仅有效集合 `observeAllWithBuiltin()` / `getAllWithBuiltin()`。
> 日历页 `CalendarViewModel` 区分 `rawShifts`（含归档，算明细）与 `activeShifts`（仅有效，展示）是正确范式 ——
> 2026-10-25 审计发现该页的**状态**侧曾误用仅有效集合（班次侧正确），导致历史自定义状态名解析不出、
> 已修复。

**「正常班小时」的口径**：`min(实际工时, 正常班时长阈值)`，阈值优先取班次自带的 `Shift.normalWorkHours`，
未配置则用 `AttendConfig.normalWorkHoursPerDay`。**只有工作日档才产出正班小时** ——
周末/节假日全天归该档，不拆分。

### 自动档判定顺序（`CalcUtils.autoSalaryMode`）

自上而下，先命中者胜出：

1. **节假日** —— 命中 `HolidayData` 法定节假日表（**优先于周末**，国庆落在周六/周日仍算节假日档）
2. **周末** —— 周六/周日，且非节假日、且**非调休补班日**
3. **工作日** —— 其余全部（含调休补班日，以及无法判定时的兜底）

非法日期（含 `2026-02-30` 这类不存在的日期）一律返回工作日**且不抛异常** ——
否则一条脏数据会让 `isWeekend` 里的 `LocalDate.of` 抛出 `DateTimeException`，打断整月统计。

### 加班判定：只认「计为加班」勾选框

```kotlin
val countsAsOvertime: Boolean get() = isOvertime
```

- 原**内置「加班」附加状态已下线**（已从 `BUILTIN_STATUSES` 移除，内置项现在只有请假、调休）：
  任何自定义状态想当加班用，在排班编辑页勾「计为加班」即可 —— `AppliedStatus.isOvertime` 是唯一来源
- 「计为加班」开关的显示条件（**三个同时满足**）：
  已选附加状态 **且** 该状态不是内置请假/调休 **且** **班次是无时段班次**（内置休息/调休/请假）
  —— 这类班次没有自己的上下班时间，工时完全由附加状态的时间段决定，加班只能靠这个开关体现
- 勾选后，该状态的时间段按当天「计薪方式」归入加班/周末/节假日工时

## 迟到 / 早退统计口径

| 环节 | 口径 |
|---|---|
| **统计页「迟到/早退次数」** | 只统计**实际**迟到/早退 —— 晚于计划上班（或早于计划下班）即计 1 次，**与容忍时长无关** |
| **详情列表** | 列出全部迟到/早退记录；**超过容忍时长**的那条用警示样式并标注「（超容许）」，容许时长内的正常展示并标注「（容许内不计次）」 |
| **迟到/早退扣款** | 按**第几次**分档（按日期升序，`CalcUtils.attendanceDeduction`）：① 未超允许次数 且 未超容许时长 → 不扣；② 未超允许次数**但**超容许时长 → 只扣**超出容许时长的部分**；③ **超出允许次数之后**的每条 → **全部时长都扣**，不再先减容许时长 |
| **统计页警示** | 「提醒阈值」= **允许的次数**，**超过**才用警示橙 + 角标（默认 0 → 出现 1 次即警示，0 次不警示）；不影响次数的显示与能否点开详情 |

> 容忍时长（`lateToleranceMin` / `earlyLeaveToleranceMin`）的作用被刻意限定在「详情标注 + 扣款免罚」，
> **不再吞掉次数统计** —— 否则会出现"明明迟到了，统计页却显示 0 次"。
> 计数实现见 `CalcUtils.calcMonthHours`，由 `CalcUtilsAttendanceTest` 守护。

## 导航转场与返回行为

### 转场约定（`ui/navigation/AppNavHost.kt`）

| 场景 | 进场 | 出场 |
|---|---|---|
| 打开二级页 | 新页从右整屏推入 + 淡入（260ms） | 旧页向左平移 1/5 宽（**不淡出**） |
| 返回上一级 | 上一级从左侧 1/5 视差滑回 + 淡入 | 当前页整屏滑出到右侧 |
| 切换底部 Tab | 淡入（180ms） | 淡出（180ms） |

- 缓动统一 `FastOutSlowInEasing`（`tween` 默认的线性曲线观感生硬）
- 旧页**不做淡出**：淡到全透明会露出窗口底色，快速切换时看起来像"闪一下"
- Tab 用淡入淡出而非横向滑动：底部导航切换语义上不是「进入下一级」，滑动会误导层级
- 常量集中在文件顶部：`secondaryEnter/Exit`、`secondaryPopEnter/Exit`、`tabEnter/tabExit`

### 已关闭系统「预测式返回」

Manifest 中 `<application android:enableOnBackInvokedCallback="false">`。

- **原因**：targetSdk 36 / Android 16 起该特性默认开启，系统会在返回时给窗口套一层**缩放**动画，
  与应用内转场叠加，返回二级页时像"页面被直接缩放"，且完全不受应用层控制
- **关闭后**：返回逻辑仍由 `BackHandler` / `OnBackPressedDispatcher` 处理
  （根 Tab 返回退出、日历子模式拦截、二级页 popBackStack 等行为都不变），
  转场则完全由上面那套 `AppNavHost` 转场决定

## 项目文档索引（`docs/`）

> ⚠️ `docs/` 目录按项目约定**不进版本库**（`.gitignore` 第 43 行），仅本地留存。

| 文档 | 性质 | 内容 |
|---|---|---|
| `docs/数据兼容与迁移策略.md` | **现行策略**（权威） | 数据格式栅栏（DB v7 / 应用数据备份 v5 / 班次配置备份 v7）、版本闸门、备份还原语义 |
| `docs/calc-profile-plan.md` | **设计与实施存档**（已落地） | 计算口径档案（配置档案 + 生效日接力）的设计决策、区间接力不变式、实施进度 |
| `docs/打卡按钮与附加状态显示规则需求.md` | **需求定稿**（仍是实现依据） | 2x1 小组件打卡按钮窗口、附加状态时间段计算（S1~S5 场景矩阵） |
| `docs/代码审查报告-20260822.md` | **历史存档** | 2026-08-22 一次性审查报告；行号与结论均已过时，仅作留档 |

> 文档与代码冲突时：**以代码为准**，并回头修正文档；
> 涉及数据格式 / 计算口径的文档改动，必须同步跑 `testDebugUnitTest`（含 `DataFormatFenceTest`）。

## 维护须知（踩坑记录）

1. **改 res 资源后必须 `--rerun-tasks --no-build-cache`**，否则构建缓存复用旧产物，改动不进 APK
2. **涉及 Glance / WorkManager 的改动必须构建 release 验证**（debug 不混淆，掩盖 R8 导致的运行时崩溃）
   —— 2026-10-26 起本项目**不再 keep `androidx.glance.**` 全包**（省 18.6% 体积）；
   **升级 Glance 版本后**必须重跑：构建无 R8 告警 + 三个组件能新增/刷新 + 点组件按钮（打卡/刷新/打开 App）能触发
3. **禁止在 ViewModel `init` 的默认 Main 协程里做 CPU 密集计算或同步 IO/Binder**——重活用 `withContext(Dispatchers.Default/IO)` 包住（历史卡顿根因）
4. **调用组件刷新前先用脏数据指纹判断**，数据未变跳过（Glance `update()` 内部会切主线程做 Binder）
5. **数据库升级只写一条前向迁移**：从当前版本（v7）到新版本；栅栏已抬到 v7，更老的数据格式一律**明确拒绝**（不转换、不兜底解析）
6. 高频纯计算（农历/工时换算/月排班明细）已有 LRU 缓存，新增同类函数应照做
7. 日历页查询事件必须用区间过滤，只有事项页允许全量 + TTL 缓存
8. **降级/回退安装只能「完整卸载（不带 `-k`）→ 安装」**：`adb install -d` 对 release 包无效，
   `pm uninstall -k` 反而会把降级路径锁死（详见「版本号规则」）
9. **manifest 改动有没有真进 APK**：查 `app/build/intermediates/packaged_manifests/release/
   processReleaseManifestForPackage/AndroidManifest.xml`；`merged_manifests/…` 只是中间产物，不是打包结果
10. **设备数据无法通过 adb 备份**：adb shell 无 root，`/data/data/com.schedulecalendar.app` 连 `ls -ld` 都被拒，
    应用 FileProvider 也是 `exported=false` → 需要卸载前先**让用户在 App 内「设置 → 数据管理」导出备份**
11. **不为旧数据做兼容/迁移**（已确认）：内置「加班」附加状态下线后**未保留**历史 ID 兜底 ——
    极少量引用它的旧记录会显示不出状态名、也不再按加班计（用户确认基本没用过该功能，可接受）
12. **计算侧用「含归档」集合，UI 选择侧用「仅有效」集合**（详见「计薪规则与加班判定」末尾的警示）：
    历史排班引用的是**已归档**的班次/状态，用仅有效集合会让历史工时/薪资算成 0 或状态名解析不出
13. **改薪资/考勤/休息时段 = 派生新档案**，绝不原地改表（详见「计算口径档案」）：
    这三类参数**没有引用锚点**，直接改一次就追溯影响所有历史月份
14. **文档与代码冲突以代码为准**：改完代码顺手修正 README / `docs/`，别让文档越拖越旧

## 许可证

本项目仅供学习交流使用。
