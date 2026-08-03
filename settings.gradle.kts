pluginManagement {
    repositories {
        // 插件解析优先使用稳定的官方源，避免镜像短暂不可用导致 UnknownPluginException
        gradlePluginPortal()
        mavenCentral()
        google()

        // 以下为镜像/备用源，保留以加速下载或作为兜底
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 依赖解析也优先使用官方仓库，镜像作为可选加速或兜底
        google()
        mavenCentral()
        // 加速镜像/备用
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        // JitPack（如你的项目依赖了 WheelPickerCompose）
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "排班日历"
include(":app")
