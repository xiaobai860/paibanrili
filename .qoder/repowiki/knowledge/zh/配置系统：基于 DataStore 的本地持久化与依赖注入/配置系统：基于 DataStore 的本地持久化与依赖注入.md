---
kind: configuration_system
name: 配置系统：基于 DataStore 的本地持久化与依赖注入
category: configuration_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/data/prefs/AppPreferences.kt
    - app/src/main/java/com/schedulecalendar/app/di/PreferencesModule.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/SettingsViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/BackupManager.kt
    - gradle/libs.versions.toml
---

## 1. 核心架构与工具
该应用采用 **Jetpack DataStore (Preferences)** 作为主要的运行时配置存储方案，结合 **Hilt** 进行依赖注入管理。配置数据以键值对形式存储在本地文件中，并通过 `Flow` 提供响应式的数据流，确保 UI 层能实时感知配置变化。

- **存储引擎**: `androidx.datastore:datastore-preferences`。所有用户偏好设置（如薪资规则、考勤配置、提醒开关等）均序列化后存入名为 `app_config` 的 DataStore 实例。
- **序列化**: 使用 `Gson` 将复杂的 Kotlin Data Class（如 `SalaryConfig`, `AttendConfig`）序列化为 JSON 字符串存储。在反序列化时，通过 `InstanceCreator` 确保保留 Kotlin 数据类的默认参数值，防止因字段缺失导致的崩溃或空指针。
- **依赖注入**: 通过 Hilt 的 `@Singleton` 作用域管理 `AppPreferences` 单例，确保全局配置状态的一致性。

## 2. 关键文件与职责

| 文件路径 | 职责描述 |
| :--- | :--- |
| `app/src/main/java/.../data/prefs/AppPreferences.kt` | **配置核心类**。定义了所有的配置键（`PreferencesKey`），提供了读取（`Flow` 或 `suspend`）和保存配置的接口。处理了 JSON 序列化/反序列化逻辑。 |
| `app/src/main/java/.../di/PreferencesModule.kt` | **DI 模块**。负责向 Hilt 容器提供 `AppPreferences` 实例。 |
| `app/src/main/java/.../ui/settings/SettingsViewModel.kt` | **配置业务逻辑**。监听 `AppPreferences` 中的 Flow，将原始配置映射为 UI 状态（`UiState`），并处理配置的更新请求。 |
| `app/src/main/java/.../ui/settings/BackupManager.kt` | **配置备份与恢复**。负责将当前配置序列化为 JSON 文件进行备份，支持从外部存储或 SAF（Storage Access Framework）导入/导出配置。 |
| `gradle/libs.versions.toml` | **版本目录**。统一管理 DataStore、Hilt、Gson 等配置相关库的版本。 |

## 3. 配置分类与管理策略

### 3.1 业务配置
- **薪资与考勤**: `SalaryConfig`, `AttendConfig`。以 JSON 字符串形式存储，支持复杂的嵌套结构。
- **排班规则**: `ScheduleRule`。定义循环排班的逻辑。
- **显示方案**: `List<DisplayScheme>`。存储多个自定义的日历显示样式。

### 3.2 运行时状态与排序
- **排序索引**: 班次、状态、补贴项的显示顺序通过逗号分隔的 ID 字符串存储（如 `KEY_SHIFT_ORDER`）。
- **颜色索引**: 记录用户上次选择的预设颜色位置，提升操作连续性。
- **权限与初始化标记**: 如 `KEY_INITIAL_PERMISSIONS_DONE`，用于控制应用首次启动时的权限申请流程。

### 3.3 提醒与通知
- **提醒配置**: 包括上下班提醒的开关、方式（日历/闹钟）、提前时间等。这些配置直接驱动后台的 `ReminderScheduler` 和 `AlarmReceiver`。

## 4. 备份与迁移机制
- **自动备份**: `BackupManager` 根据用户设置的保留份数（`keepCount`），在特定操作（如修改班次配置）或每天自动触发备份。
- **多源存储**: 支持将配置备份到应用私有目录 (`filesDir/backups`) 或用户指定的外部 SAF 路径。
- **全量恢复**: 备份文件包含所有业务配置和运行时状态。恢复时，`BackupManager` 会解析 JSON 并批量调用 Repository 和 Preferences 接口重写数据。

## 5. 开发者规范
1. **新增配置项**: 
   - 在 `AppPreferences` 中定义 `PreferencesKey`。
   - 提供对应的 `Flow`（用于 UI 观察）和 `suspend fun save...`（用于写入）。
   - 若为复杂对象，需在 `gson` 初始化处注册 `InstanceCreator`。
2. **默认值处理**: 读取配置时应提供合理的默认值（如 `?: SalaryConfig()`），确保新安装应用或清除数据后功能正常。
3. **线程安全**: 所有 DataStore 的读写操作均为挂起函数或在 Flow 中执行，严禁在主线程直接访问底层存储。
4. **备份同步**: 若新增了需要持久化的配置字段，务必同步更新 `BackupManager` 中的 `buildAppDataJson` 和 `restoreAppData` 方法，以确保备份文件的兼容性。