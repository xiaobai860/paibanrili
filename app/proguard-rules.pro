# 排班日历 ProGuard 规则
#
# ── 总则 ────────────────────────────────────────────────────────────────────────
# 凡是**字段名会被写进用户数据**的类（备份文件 / 数据库里的 JSON 列 / SharedPreferences），
# 字段名就属于「数据格式」的一部分，**绝不能被混淆**：
#   ① 对应类加精确 keep；② 关键字段再加 @SerializedName 钉死名字（双保险，混淆也改不了 key）。
#   守护：ProguardBackupRulesTest 会自动发现载荷类/JSON 载体类并断言它们在 keep 名单里。
# ⚠️ 混淆类问题在 debug 包上**测不出来**（debug 不混淆）→ 验收看 R8 映射表：
#    app/build/outputs/mapping/release/mapping.txt 里字段应是 `getXxx -> getXxx`（而非 `-> b`）。
#
# Jetpack Compose 不需要额外规则（AGP 已内置）

# ── Hilt ────────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepclassmembers,allowobfuscation class * {
    @javax.inject.Inject <init>(...);
}

# ── Room ────────────────────────────────────────────────────────────────────────
# 保留 RoomDatabase 子类的 public 抽象方法（Room 生成的 DAO 方法）。
# 用官方 `<methods>` 伪代码，别写 `public abstract *;`（IDE 检查会判成语法错）。
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public abstract <methods>;
}

# ── Gson ────────────────────────────────────────────────────────────────────────
-keepattributes Signature
-dontwarn sun.misc.**
# Gson 2.11.0 **自带 consumer 规则**（META-INF/proguard/gson.pro，AGP 自动合并），
# 已覆盖 TypeToken / @JsonAdapter / 各 TypeAdapter 的无参构造器 / @SerializedName 字段
# → 不要手写 `-keep class com.google.gson.**`（重复覆盖 + 触发 IDE「规则过宽」提示）。
# 我们自己的模型必须 keep：domain model 是备份与 DataStore 的载荷。
-keepclassmembers class com.schedulecalendar.app.domain.model.** { *; }
-keep class com.schedulecalendar.app.domain.model.** { *; }

# ── Glance Widget ───────────────────────────────────────────────────────────────
# 小组件数据以 JSON 存在 SharedPreferences → widget 包下的数据类字段名不能被混淆。
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver
-keepclassmembers class com.schedulecalendar.app.widget.** { *; }
-keep class com.schedulecalendar.app.widget.** { *; }

# ── Glance 运行时 ───────────────────────────────────────────────────────────────
# ⚠️ 必须全量 keep，勿收窄：glance-appwidget 自带的 consumer 规则**只覆盖 protobuf 与
#    ActionCallback**，不覆盖 Glance 运行时（Composition / Provider / Action 内部类），
#    收窄后 release 包小组件会「载入出错」。改动必须真机验证小组件加载与后台刷新。
-keep class androidx.glance.** { *; }
-keepclassmembers class androidx.glance.** { *; }

# ── WorkManager ────────────────────────────────────────────────────────────────
# work-runtime 自带 consumer 规则（AAR 内 proguard.txt），已覆盖 worker/InputMerger 的
# 类名与构造器、WorkerParameters → 只需精确 keep 我们自己的 worker（按类名反射实例化）。
-keepnames class com.schedulecalendar.app.widget.WidgetRefreshWorker
-dontwarn androidx.work.**

# ── 备份载荷类（应用数据 / 班次配置）──────────────────────────────────────────
# 备份文件的字段名是数据格式的一部分：混淆后导出的备份无法恢复（曾因此事故）。
# 精确 keep + 字段 @SerializedName 双保险；不要改回整包 keep（挪包会失效且掩盖问题）。
-keep class com.schedulecalendar.app.data.compat.AppDataBackup { *; }
-keep class com.schedulecalendar.app.data.compat.ShiftConfigBackup { *; }

# ── 数据库 JSON 列的载体类 ─────────────────────────────────────────────────────
# AppliedStatusJson 的字段名被直接写进 Room 的 appliedStatusesJson 列 → 必须 keep。
-keep class com.schedulecalendar.app.data.repository.AppliedStatusJson { *; }

# ── Coroutines ─────────────────────────────────────────────────────────────────
# 这两个类由协程库按名字反射查找 → 只保类名即可（-keepnames 不带成员列表）。
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler

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
