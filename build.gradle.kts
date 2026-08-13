// 顶层 build.gradle.kts — 仅声明插件，不应用到 root
plugins {
    alias(libs.plugins.android.application)  apply false
    alias(libs.plugins.kotlin.compose)       apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt)                 apply false
    alias(libs.plugins.ksp)                  apply false
}
