pluginManagement {
    repositories {
        // 国内镜像优先，大幅加速插件解析（plugins.gradle.org 海外直连很慢）
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }

        // 官方源兜底：镜像节点临时故障时 Gradle 会按顺序自动 fallback 到此处，避免构建失败
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像优先，加速依赖下载；官方源兜底（镜像故障时自动 fallback）
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        google()
        mavenCentral()
        // JitPack（如你的项目依赖了 WheelPickerCompose）
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "排班日历"
include(":app")
include(":lint")
