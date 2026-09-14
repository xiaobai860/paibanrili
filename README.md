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
- 上下班打卡提醒（精确闹钟，预调度「过去 3 天 ~ 未来 5 天」共 9 天窗口）
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
| 桌面组件 | Glance 1.2.0 + WorkManager 2.11.2 |
| 序列化 | Gson（配置/组件数据 JSON）+ kotlinx-serialization（Navigation 路由） |
| 农历/节气/黄历 | tyme4j 1.5.1（`cn.6tail:tyme4j`，依据紫金山天文台《农历的编算和颁行》） |
| 单元测试 | JUnit 4（守护法定节假日表 + 历法数据对账） |

## 构建环境与版本信息

### 构建参数（`app/build.gradle.kts`）
- `compileSdk = 37`，`minSdk = 34`（Android 14+），`targetSdk = 36`
- Java 17 编译目标；release 开启 R8 混淆 + 资源收缩
- Room schema 导出目录：`app/schemas/`（当前已导出版本 2/3/4/5）

### 版本号规则
- `versionName`：展示用版本号 = `年月日 + 两位当天迭代号`，如 `2026091001`（设置页展示的就是它）
- `versionCode`：系统用的递增整数，**每次发版 +1**，与 `versionName` 无关
  - ⚠️ 注意：设备上曾装过**日期型**版本（`2026083103`），其值远大于小整数计数器，因此覆盖安装会报 `INSTALL_FAILED_VERSION_DOWNGRADE`。装包需用 `adb install -r -d`（`-d` 允许降级，**保留数据**），或先卸载旧版。
  - 发版前核对设备真值：`adb shell dumpsys package com.schedulecalendar.app | findstr /C:versionCode`（不要只比对自家历史记录）
- 当前值：`versionCode = 181`、`versionName = "2026091404"`
- lint 已屏蔽：`HighAppVersionCode`、`IconLauncherShape`、`IconDuplicates`、`UnusedAttribute`、`NewerVersionAvailable`、`ReportShortcutUsage`

### 构建命令
```bash
# 调试版
./gradlew assembleDebug

# 发布版（改过资源/混淆敏感代码后务必加参数关闭缓存）
./gradlew assembleRelease --rerun-tasks --no-build-cache

# 单元测试（节假日数据守护 + 历法数据对账；改过 HolidayData / LunarCalendar 后必跑）
./gradlew testDebugUnitTest
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
│   └── calendar/            #   已按职责拆分：CalendarScreen / CalendarDialogs / CalendarCells
└── widget/                  # Glance 小组件 + 数据同步 + 周期刷新 Worker

app/src/test/java/com/schedulecalendar/app/   # 单元测试（HolidayDataTest + TymeAlmanacTest）
```

### 数据层（`data/`）
- **Room**（`AppDatabase`，版本 5，库名 `schedule_calendar.db`，WAL 模式）：
  - Entity：`ShiftEntity`、`ScheduleRecordEntity`、`ExtraItemEntity`、`ShiftBreakEntity`、`ShiftStatusEntity`
  - DAO：`ShiftDao`、`ScheduleRecordDao`、`ExtraItemDao`、`ShiftBreakDao`、`ShiftStatusDao`
  - 升级注意：`DatabaseModule` 使用 `.addMigrations()`（**fail-fast**）——改了 `@Database(version = )` 却没补对应 Migration 会**直接抛异常**，不会再静默清库
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

`app/src/test/java/com/schedulecalendar/app/domain/model/` 下有两个测试类：

**`HolidayDataTest.kt`**（8 用例，守护自研节假日表）：

- **覆盖年份必须 ≥ 当前年 + 1**（会随时间自然失败，起「该更新数据了」的提醒作用）
- 各覆盖年份假期数下限、放假与补班不重叠、补班日必落在周末（已核验年份）
- 超出覆盖范围时退化为「仅按周末」，绝不误判为节假日
- 2024 / 2025 / 2026 与国务院公告逐条回归

**`TymeAlmanacTest.kt`**（17 用例，守护历法数据 & 「确实用上了库」）：

- **法定节假日与 tyme4j 内置国务院公告逐日双向对账**（2024-01-01 ~ 2026-12-31）
- 节气：已知日期断言 + **2050 年仍须有完整 24 节气**（证明已脱离硬编码表）
- 节日：库覆盖项 + 本地补充项（小年、情人节 / 愚人节 / 圣诞节）都不丢
- 黄历字段取值合法性；12 时辰顺序与时间；冲煞逐日反推校验
- 七十二候 / 数九 / 三伏 / 梅雨取值范围；**三伏与数九严格互斥**（跨 21 年扫描）
- 星期与 JDK 公历逐日交叉验证

```bash
./gradlew testDebugUnitTest
```

> `assembleRelease` **不会**执行单元测试，改完节假日数据请手动跑一次。

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

`cn.6tail:tyme4j` 1.5.1 的包级能力与可用性评估：

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

## 维护须知（踩坑记录）

1. **改 res 资源后必须 `--rerun-tasks --no-build-cache`**，否则构建缓存复用旧产物，改动不进 APK
2. **涉及 Glance / WorkManager 的改动必须构建 release 验证**（debug 不混淆，掩盖 R8 导致的运行时崩溃）
3. **禁止在 ViewModel `init` 的默认 Main 协程里做 CPU 密集计算或同步 IO/Binder**——重活用 `withContext(Dispatchers.Default/IO)` 包住（历史卡顿根因）
4. **调用组件刷新前先用脏数据指纹判断**，数据未变跳过（Glance `update()` 内部会切主线程做 Binder）
5. **数据库升级必须新增 Migration**：`DatabaseModule` 已移除破坏性迁移，缺 Migration 会 fail-fast 抛异常（而非清库）
6. 高频纯计算（农历/工时换算/月排班明细）已有 LRU 缓存，新增同类函数应照做
7. 日历页查询事件必须用区间过滤，只有事项页允许全量 + TTL 缓存

## 许可证

本项目仅供学习交流使用。
