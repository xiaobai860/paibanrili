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

# ── Glance Widget ──────────────────────────────────────────────────────────────
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver

# ── Coroutines ────────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── kotlinx-serialization ─────────────────────────────────────────────────────
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
