---
kind: configuration_system
name: 基于 DataStore + Hilt 的用户配置系统
category: configuration_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/data/prefs/AppPreferences.kt
    - app/src/main/java/com/schedulecalendar/app/di/PreferencesModule.kt
    - app/build.gradle.kts
    - gradle/libs.versions.toml
---

本仓库采用 AndroidX DataStore Preferences 作为用户运行时配置的唯一持久化方案，并通过 Hilt 以单例形式注入。未发现 application.properties、.env、BuildConfig 字段或外部 YAML/TOML 等构建期配置机制，所有可配置项均以键值对形式存储在本地 DataStore 中。

### 1. 核心架构与组件
- 数据层：app/src/main/java/com/schedulecalendar/app/data/prefs/AppPreferences.kt 是配置系统的唯一入口，封装了全部偏好键（KEY_*）及对应的 Flow/读写 API。
- 依赖注入：app/src/main/java/com/schedulecalendar/app/di/PreferencesModule.kt 通过 @Provides @Singleton 暴露 AppPreferences，由 Hilt 在应用级生命周期内共享。
- 序列化策略：复杂对象（SalaryConfig、AttendConfig、DisplayScheme、ScheduleRule、Map<String,String> 等）统一使用 Gson 序列化为 JSON 字符串存储；简单类型直接存为 string/int。
- 响应式读取：大部分配置提供 Flow<T> 属性（如 salaryConfigFlow、reminderEnabledFlow），UI 侧通过 collectAsStateWithLifecycle 订阅变化。

### 2. 配置分类与命名约定
所有键集中在 AppPreferences.companion object 中以 KEY_ 前缀声明，按功能域分组：
- 薪资/考勤/排班规则：KEY_SALARY_CONFIG、KEY_ATTEND_CONFIG、KEY_SCHEDULE_RULE
- UI 显示方案与排序：KEY_DISPLAY_SCHEMES、KEY_SHIFT_ORDER、KEY_STATUS_ORDER、KEY_EXTRA_ORDER、KEY_BREAK_ORDER
- 小组件数据：KEY_WIDGET_DATA
- 提醒设置：KEY_REMINDER_ENABLED、KEY_REMINDER_METHOD、KEY_REMINDER_CLOCK_IN(_MINUTES) 等
- 日历账户管理：KEY_DISABLED_ACCOUNT_IDS、KEY_ACCOUNT_CATEGORIES、KEY_ACCOUNTS_INITIALIZED
- 快捷方式与权限引导：KEY_SHORTCUT_ENABLED、KEY_INITIAL_PERMISSIONS_DONE
- 备份相关：KEY_APP_DATA_KEEP_COUNT、KEY_SHIFT_CONFIG_KEEP_COUNT、KEY_BACKUP_CUSTOM_PATH

### 3. 设计决策与约束
- 无多环境/多构建变体配置：build.gradle.kts 未定义 buildTypes 的 buildConfigField / resValue / manifestPlaceholders，也未使用 flavorDimensions，因此不存在 debug/release 差异化配置。
- 无环境变量或远程配置中心：未发现任何网络拉取配置、Feature Flag 服务或 .env 文件。
- DataStore 文件名固定：通过 preferencesDataStore("app_config") 指定单一文件，避免分散。
- 默认值处理：每个 getter 都提供合理的默认值（如 reminder_method 默认为 "alarm"、颜色索引默认为 0、保留份数默认为 5/10），保证首次启动可用。
- 清理能力：提供 clearAll() 方法一键清空所有键，用于恢复出厂设置。

### 4. 开发者规范
- 新增配置时，先在 AppPreferences companion object 中声明 KEY_*，再补充对应的 get*/save* 与可选的 Flow。
- 复杂对象优先使用 Gson 序列化，注意在 InstanceCreator 中注册 data class 默认构造器以避免反序列化丢失默认值。
- UI 层应优先消费 Flow 而非直接调用 suspend 读取，以保证状态自动刷新。
- 如需区分不同来源的临时偏好（如打卡时间戳），可在其他 ViewModel 中使用独立的 SharedPreferences（见 CalendarViewModel.kt 中对 clockPrefs 的使用），但业务配置一律走 AppPreferences。