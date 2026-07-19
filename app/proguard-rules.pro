# 排班日历 ProGuard 规则
# Jetpack Compose 不需要额外规则（AGP 已内置）

# ── Hilt ─────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepclassmembers,allowobfuscation class * {
    @javax.inject.Inject <init>(...);
}

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract *;
}

# ── Gson（DataStore 序列化模型）────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# 保留所有 domain model 数据类（用于 Gson 反序列化）
-keepclassmembers class com.schedulecalendar.app.domain.model.** { *; }
-keep class com.schedulecalendar.app.domain.model.** { *; }

# ── Glance Widget（Gson 序列化数据类 + Widget 组件）────────────────────────────────
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver
# 保留 widget 包下所有 Gson 序列化的数据类字段（CalendarWidgetInfo / CalendarWidgetDay / ClockInWidgetData）
-keepclassmembers class com.schedulecalendar.app.widget.** { *; }
-keep class com.schedulecalendar.app.widget.** { *; }

# ── 备份管理 Gson 数据类（AppDataBackup / ShiftExportData）───────────────────────
-keepclassmembers class com.schedulecalendar.app.ui.settings.** { *; }
-keep class com.schedulecalendar.app.ui.settings.** { *; }
-keepclassmembers class com.schedulecalendar.app.ui.shifts.** { *; }
-keep class com.schedulecalendar.app.ui.shifts.** { *; }

# ── data.repository Gson 数据类（AppliedStatusJson Room 序列化）────────────────
-keepclassmembers class com.schedulecalendar.app.data.repository.** { *; }
-keep class com.schedulecalendar.app.data.repository.** { *; }

# ── MainActivity 返回键状态字段与访问方法（防止 R8 内联优化导致状态同步失效）──
# Kotlin 属性编译为 getter/setter 方法，R8 全程序优化可能内联这些方法
# 使 Activity ↔ Composable 之间的状态同步失效
-keepclassmembers class com.schedulecalendar.app.MainActivity {
    boolean isOnTabPage;
    boolean calendarSubModeActive;
    boolean getIsOnTabPage();
    void setIsOnTabPage(boolean);
    boolean getCalendarSubModeActive();
    void setCalendarSubModeActive(boolean);
    androidx.activity.OnBackPressedCallback tabBackCallback;
}

# ── OnBackPressedDispatcher 回调（保证 handleOnBackPressed 不被 R8 裁剪）─────────
-keepclassmembers class com.schedulecalendar.app.MainActivity$** {
    void handleOnBackPressed();
}
-keep class com.schedulecalendar.app.MainActivity$** extends androidx.activity.OnBackPressedCallback { *; }

# ── Coroutines ────────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── Navigation Route objects（防止 R8 重命名导致 ::class.qualifiedName 与 route 不匹配）──
-keepnames class com.schedulecalendar.app.ui.navigation.Route*

# ── kotlinx-serialization ─────────────────────────────────────────────────────
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
