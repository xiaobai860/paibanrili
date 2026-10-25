# 排班日历 ProGuard / R8 规则
#
# ── 总则 ────────────────────────────────────────────────────────────────────────
# 本文件只放**「删了就出事」**的规则，分两类：
#   ① **字段名会被写进用户数据**的类（备份 JSON / 数据库 JSON 列 / SharedPreferences）
#      → 字段名属于「数据格式」的一部分，绝不能混淆；
#      做法：对应类加精确 keep；关键字段再加 @SerializedName 钉死名字（双保险）。
#   ② **类名会被反射找回**的类 → 类名是运行期契约。
# 其余（Hilt / Room / Gson / coroutines / WorkManager / Glance 的通用规则）
# 一律由各库自带的 consumer 规则提供（AGP 自动合并 AAR/JAR 内的 proguard 文件），不重复声明。
# 守护：ProguardBackupRulesTest 会自动发现载荷类 / JSON 载体类 / Glance 回调类
#       并断言它们在 keep 名单里。
# ⚠️ 混淆类问题在 debug 包上**测不出来**（debug 不混淆）→ 验收看 R8 映射表：
#    app/build/outputs/mapping/release/mapping.txt 里保留类应写作 `X -> X:`（原名 + 尾冒号），
#    且该类块内**不应出现任何成员行**（成员没被改名就不会进映射表）；
#    一旦出现 `getShifts() -> b` 这类行，就说明字段名被混淆了（备份 JSON 会失效）。
#
# Jetpack Compose 不需要额外规则（AGP 已内置）

# ── 注解与签名 ──────────────────────────────────────────────────────────────────
# *Annotation*：kotlinx-serialization（Navigation 类型安全路由）需要运行时可见的
#   @Serializable 注解；Gson 的 @SerializedName 也靠它。
#   → **不要**再单独写 `-keepattributes RuntimeVisibleAnnotations,AnnotationDefault`，
#     已被本行完全覆盖（重复声明会让本文件失焦）。
-keepattributes *Annotation*
# Signature：Gson 反序列化集合字段时要读泛型签名（List<Shift> → Shift）。
# Gson 2.14.0 的 consumer 规则也带这条，这里显式保留，避免依赖库的隐式行为。
-keepattributes Signature

# ── Hilt / Room / Gson / Coroutines / WorkManager / Glance ─────────────────────
# 以上各库的规则**已由其 consumer 规则自带**（AGP 自动合并 AAR/JAR 内的 proguard 文件），
# 本文件不再重复声明：
#   · Hilt/Dagger        → @javax.inject.Inject 构造器与字段
#   · Room               → RoomDatabase 子类、@Entity 类、DAO 抽象方法
#   · Gson               → Signature / TypeToken / @JsonAdapter / @SerializedName / 无参构造器
#   · kotlinx-coroutines → MainDispatcherFactory / CoroutineExceptionHandler
#   · WorkManager        → ListenableWorker 类名与构造器 / InputMerger / WorkerParameters
#   · Glance             → protobuf 字段 / ActionCallback 子类；AAR manifest 里的组件由 AGP 自动 keep
# 重复写既无收益，又会让本文件失焦（IDE 也会判「规则过宽 / 未使用」）。
# ⚠️ 删这些规则后**必须真机验证**（Hilt 注入 / Room 打开 / 小组件加载 / WorkManager 运行）。

# ── 领域模型（备份 JSON / DataStore / Room JSON 列的载荷）──────────────────────
# 全部字段名都会进用户数据 → 类名与字段名都不能被改。
-keep class com.schedulecalendar.app.domain.model.** { *; }

# ── 小组件包 ───────────────────────────────────────────────────────────────────
# ① 组件数据以 JSON 存在 SharedPreferences → 数据类字段名不能被混淆；
# ② **Glance 通过「类名反射」实例化我们的 ActionCallback**（actionRunCallback 把
#    callback.javaClass.name 写进 Intent，点击时再 Class.forName 找回来）
#    → 所有 `: ActionCallback` 子类的**类名**必须保留；
# ③ WidgetRefreshWorker 由 WorkManager 按类名反射实例化 → 类名必须保留。
# ⚠️ 本规则是「宽而对」的刻意选择：收窄成精确类名时，新增回调类极易漏登记，
#    而漏登记的后果是「点组件按钮静默无反应」这类极难排查的问题。
#    守护：ProguardBackupRulesTest.glanceActionCallbacksAreInKeepList
-keep class com.schedulecalendar.app.widget.** { *; }

# ── Glance ────────────────────────────────────────────────────────────────────
# 2026-10-26 审计结论：**不再** keep androidx.glance.** 全包。
#   · glance-appwidget 的 consumer 规则已保 protobuf 字段与 ActionCallback 子类；
#     glance / glance-appwidget-external-protobuf 自带「Intentionally empty … safe to shrink」；
#   · Glance 依赖的反射入口全部安全：
#       ActionTrampolineActivity / ActionCallbackBroadcastReceiver / InvisibleActionTrampolineActivity
#         → 声明在 Glance 的 AAR manifest 里 → AGP 自动 keep（类名完整）；
#       GlanceRemoteViewsService / MyPackageReplacedReceiver → 同上；
#       AsyncRequestWorker / SessionWorker → WorkManager consumer 规则 keepnames；
#       我们自己的 ActionCallback → 上面的 widget 包 keep 保证类名不变。
#   · 实测：去掉本规则后 APK 由 7,242,117 B 降到 5,898,356 B（**-18.6%**），
#     R8 零告警，真机启动无崩溃，小组件在新包下重新组装成功（RemoteViews 正常下发）。
# ⚠️ 升级 Glance 版本后必须重跑这套验证：构建无 R8 告警 + 三个组件都能新增/刷新
#    + 点组件上的按钮（打卡 / 刷新 / 打开 App）能正常触发。

# ── 日志 ────────────────────────────────────────────────────────────────────────
# ⚠️ **故意不加** `-assumenosideeffects class android.util.Log { d; v; i; }`。
# 理由（2026-10-26 实测）：该规则让 R8 认为 `Log.d/i` 无副作用 → 返回值未使用 →
#   **把调用连同消息字符串一起剥掉**。普通 release 里无所谓（分支本来就该关），
#   但「诊断版 release」（`-PdiagLogs=true`）会因此**一条诊断日志都打不出来** ——
#   实测确认：诊断串 "syncAllWidgets start" 在诊断包里已从 dex 中消失。
#   等于悄悄废掉现场诊断能力，且极难察觉（包能装、能跑，只是没日志）。
#
# release 零诊断输出靠的是**结构机制**，不靠 R8 兜底：
#   ① `AppLog` 用编译期常量 `BuildConfig.DIAGNOSTIC_LOGS` 门控（release=false）
#      → 整个分支连同参数求值被消除，零开销、零可能漏关；
#   ② `LoggingConventionTest` 断言全项目零裸用 `android.util.Log`
#      → 有人绕过 AppLog 直接写 Log.d，**单测立刻失败**（比 R8 兜底更强：编译期即拦）。
#
# w / e 始终保留：错误级日志是生产故障的唯一诊断生命线，任何情况下都不得关闭。

# ── 备份载荷类（应用数据 / 班次配置）──────────────────────────────────────────
# 备份文件的字段名是数据格式的一部分：混淆后导出的备份无法恢复（曾因此事故）。
# 精确 keep + 字段 @SerializedName 双保险；不要改回整包 keep（挪包会失效且掩盖问题）。
-keep class com.schedulecalendar.app.data.compat.AppDataBackup { *; }
-keep class com.schedulecalendar.app.data.compat.ShiftConfigBackup { *; }

# ── 数据库 JSON 列的载体类 ─────────────────────────────────────────────────────
# AppliedStatusJson 的字段名被直接写进 Room 的 appliedStatusesJson 列 → 必须 keep。
-keep class com.schedulecalendar.app.data.repository.AppliedStatusJson { *; }


# ── Navigation Route objects ────────────────────────────────────────────────────
# 防止 R8 重命名导致 ::class.qualifiedName 与 route 不匹配。
-keepnames class com.schedulecalendar.app.ui.navigation.Route*

# ── kotlinx-serialization ─────────────────────────────────────────────────────
# kotlinx-serialization-core 1.12.0 的 consumer 规则已保 INSTANCE + serializer(...)，
# 这里**只补它没有的 Companion**：Navigation 的 serializer<T>() 是靠 companion 上的
# serializer() 反射解析的，Companion 必须活着。
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
