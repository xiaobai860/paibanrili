# 排班日历 ProGuard 规则
#
# ── 总则 ────────────────────────────────────────────────────────────────────────
# 凡是**字段名会被写进用户数据**的类（备份文件 / 数据库里的 JSON 列 / SharedPreferences），
# 字段名就属于「数据格式」的一部分，**绝不能被混淆**：
#   ① 对应类加精确 keep；② 关键字段再加 @SerializedName 钉死名字（双保险，混淆也改不了 key）。
#   守护：ProguardBackupRulesTest 会自动发现载荷类/JSON 载体类并断言它们在 keep 名单里。
# ⚠️ 混淆类问题在 debug 包上**测不出来**（debug 不混淆）→ 验收看 R8 映射表：
#    app/build/outputs/mapping/release/mapping.txt 里保留类应写作 `X -> X:`（原名 + 尾冒号），
#    且该类块内**不应出现任何成员行**（成员没被改名就不会进映射表）；
#    一旦出现 `getShifts() -> b` 这类行，就说明字段名被混淆了（备份 JSON 会失效）。
#
# Jetpack Compose 不需要额外规则（AGP 已内置）

# ── 注解与签名 ──────────────────────────────────────────────────────────────────
# Gson 反射需要泛型签名；kotlinx-serialization / Hilt 需要注解可见。
-keepattributes *Annotation*
-keepattributes Signature

# ── Hilt / Room / Gson / Coroutines / WorkManager ───────────────────────────────
# 以上各库的规则**已由其 consumer 规则自带**（AGP 自动合并 AAR/JAR 内的 proguard 文件），
# 本文件不再重复声明：
#   · Hilt/Dagger        → @javax.inject.Inject 构造器与字段
#   · Room               → RoomDatabase 子类、@Entity 类、DAO 抽象方法
#   · Gson               → TypeToken / @JsonAdapter / @SerializedName / 无参构造器
#   · kotlinx-coroutines → MainDispatcherFactory / CoroutineExceptionHandler
#   · WorkManager        → Worker / InputMerger / WorkerParameters
# 重复写既无收益，又会让本文件失焦（IDE 也会判「规则过宽 / 未使用」）。
# ⚠️ 删这些规则后**必须真机验证**（Hilt 注入 / Room 打开 / 小组件加载 / WorkManager 运行）。

# ── Gson ────────────────────────────────────────────────────────────────────────
# Gson 2.11.0 **自带 consumer 规则**（META-INF/proguard/gson.pro，AGP 自动合并），
# 已覆盖 TypeToken / @JsonAdapter / 各 TypeAdapter 的无参构造器 / @SerializedName 字段
# → 不要手写 `-keep class com.google.gson.**`（重复覆盖 + 触发 IDE「规则过宽」提示）。
# 我们自己的模型必须 keep：domain model 是备份与 DataStore 的载荷。
-keep class com.schedulecalendar.app.domain.model.** { *; }

# ── Glance Widget ───────────────────────────────────────────────────────────────
# 小组件数据以 JSON 存在 SharedPreferences → widget 包下的数据类字段名不能被混淆。
# 下面两条 `extends` 规则相对下面那条整包 keep 是冗余的，保留作**纵深防御**：
# 万一将来有小组件类落在 widget 包之外，也能被保住。
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver
-keep class com.schedulecalendar.app.widget.** { *; }

# ── Glance 运行时 ───────────────────────────────────────────────────────────────
# ⚠️ 必须全量 keep，勿收窄：glance-appwidget 自带的 consumer 规则**只覆盖 protobuf 与
#    ActionCallback**，不覆盖 Glance 运行时（Composition / Provider / Action 内部类），
#    收窄后 release 包小组件会「载入出错」。改动必须真机验证小组件加载与后台刷新。
-keep class androidx.glance.** { *; }

# ── WorkManager ────────────────────────────────────────────────────────────────
# work-runtime 自带 consumer 规则（AAR 内 proguard.txt），已覆盖 worker/InputMerger 的
# 类名与构造器、WorkerParameters → 只需精确 keep 我们自己的 worker（按类名反射实例化）。
-keepnames class com.schedulecalendar.app.widget.WidgetRefreshWorker

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
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
