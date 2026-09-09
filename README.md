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
- 上下班打卡提醒（精确闹钟，未来 7 天预调度）
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
| 数据库 | Room（当前版本 5，导出 schema） |
| 偏好存储 | DataStore（`app_config`）+ SharedPreferences（仅组件数据缓存） |
| 导航 | Navigation Compose（类型安全路由） |
| 异步 | Kotlin Coroutines + Flow |
| 桌面组件 | Glance 1.1.1 + WorkManager |
| 序列化 | Gson（配置/组件数据 JSON）+ kotlinx-serialization（Navigation 路由） |
| 农历 | tyme4j |

## 构建环境与版本信息

### 构建参数（`app/build.gradle.kts`）
- `compileSdk = 37`，`minSdk = 34`（Android 14+），`targetSdk = 36`
- Java 17 编译目标；release 开启 R8 混淆 + 资源收缩
- Room schema 导出目录：`app/schemas/`（当前已导出版本 2/3/4/5）

### 版本号规则
- `versionName`：展示用版本号 = `年月日 + 两位当天迭代号`，如 `2026091001`（设置页展示的就是它）
- `versionCode`：系统用独立递增整数，与 `versionName` 无关，**发版时必须保证不小于用户已装版本**，否则覆盖安装失败
- 当前值：`versionCode = 162`、`versionName = "2026091001"`
- lint 已屏蔽：`HighAppVersionCode`、`IconLauncherShape`、`IconDuplicates`、`UnusedAttribute`、`NewerVersionAvailable`、`ReportShortcutUsage`

### 构建命令
```bash
# 调试版
./gradlew assembleDebug

# 发布版（改过资源/混淆敏感代码后务必加参数关闭缓存）
./gradlew assembleRelease --rerun-tasks --no-build-cache
```

发布版签名配置存储在 `local.properties` 中（不纳入版本控制）：

```properties
RELEASE_STORE_FILE=/path/to/keystore
RELEASE_STORE_PASSWORD=your_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_password
```

### ⚠️ R8/ProGuard 关键注意事项
`app/proguard-rules.pro` 中以下 keep 规则**必须永久保留**，删除会导致小组件"载入出错"（真机已复现）：
- `androidx.glance.**` / `androidx.glance.appwidget.**` 全保留
- `androidx.work.**` 全保留 + `InputMerger`/`Worker`/`ListenableWorker` 子类保留
- 原因：R8 裁剪掉 `OverwritingInputMerger` 无参构造 → Glance 后台 worker 实例化失败

其他 keep：Gson 反序列化数据类（`domain.model.**` 等）、Navigation 路由 `-keepnames Route*`、MainActivity 状态字段防内联。

## 项目结构

```
app/src/main/java/com/schedulecalendar/app/
├── MainActivity.kt          # 唯一 Activity：权限引导 + AppNavHost + 组件跳转日期
├── ScheduleApp.kt           # @HiltAndroidApp：数据变更→刷新组件；WorkManager 兜底
├── data/                    # 数据层
│   ├── db/                  #   AppDatabase(v5) + 5 Entity + 5 DAO
│   ├── calendar/            #   系统日历集成（认证器 + CalendarProvider 仓库）
│   ├── repository/          #   仓库层（Entity↔Domain 映射 + 变更信号 Flow）
│   └── prefs/               #   AppPreferences（DataStore 封装）
├── domain/                  # 领域层（纯 Kotlin，无 Android 依赖）
│   ├── model/               #   Models / CalcUtils / HolidayData / LunarCalendar
├── di/                      # Hilt Module（DatabaseModule / PreferencesModule）
├── reminder/                # 提醒：调度器 + 4 个 BroadcastReceiver
├── ui/                      # Compose 界面层（Screen + ViewModel + 导航 + 主题）
└── widget/                  # Glance 小组件 + 数据同步 + 周期刷新 Worker
```

### 数据层（`data/`）
- **Room**（`AppDatabase`，版本 5，库名 `schedule_calendar.db`，WAL 模式）：
  - Entity：`ShiftEntity`、`ScheduleRecordEntity`、`ExtraItemEntity`、`ShiftBreakEntity`、`ShiftStatusEntity`
  - DAO：`ShiftDao`、`ScheduleRecordDao`、`ExtraItemDao`、`ShiftBreakDao`、`ShiftStatusDao`
  - 升级注意：`DatabaseModule` 配置了 `fallbackToDestructiveMigration`（未写 Migration 会**清库重建**）
- **仓库层**：各 Repository 在 Entity↔Domain 映射基础上暴露 `refreshSignal` / `changeSignal`（SharedFlow），供全局监听数据变化
- **DataStore**（`AppPreferences`）：全部应用配置（薪资/考勤/排班规则/显示方案/组件配置/备份设置），复杂对象 Gson 序列化
- **SharedPreferences**：仅组件数据缓存（`calendar_widget_data_prefs`）与组件外观（`widget_config_prefs`）
- **系统日历**：`CalendarEventRepository` 读写 CalendarProvider；`CalendarAuthenticator` 提供可见的自定义账户

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
- `ReminderScheduler`：`AlarmManager.setAlarmClock` 预调度未来 7 天上下班提醒
- `AlarmReceiver` / `AnniversaryReminderReceiver`：发通知（`goAsync` 防超时）
- `BootReceiver`：开机后重新调度
- `TimeChangeReceiver`：时间/时区变更后全量重调度

### 依赖注入（`di/`）
- `DatabaseModule`：`AppDatabase` + 5 个 DAO 单例
- `PreferencesModule`：`AppPreferences` 单例
- BroadcastReceiver / GlanceWidget 无法构造注入，统一通过 **Hilt EntryPoint**（`EntryPointAccessors.fromApplication`）获取依赖

## 维护须知（踩坑记录）

1. **改 res 资源后必须 `--rerun-tasks --no-build-cache`**，否则构建缓存复用旧产物，改动不进 APK
2. **涉及 Glance / WorkManager 的改动必须构建 release 验证**（debug 不混淆，掩盖 R8 导致的运行时崩溃）
3. **禁止在 ViewModel `init` 的默认 Main 协程里做 CPU 密集计算或同步 IO/Binder**——重活用 `withContext(Dispatchers.Default/IO)` 包住（历史卡顿根因）
4. **调用组件刷新前先用脏数据指纹判断**，数据未变跳过（Glance `update()` 内部会切主线程做 Binder）
5. **数据库升级必须新增 Migration**，否则 `fallbackToDestructiveMigration` 清库
6. 高频纯计算（农历/工时换算/月排班明细）已有 LRU 缓存，新增同类函数应照做
7. 日历页查询事件必须用区间过滤，只有事项页允许全量 + TTL 缓存

## 许可证

本项目仅供学习交流使用。
