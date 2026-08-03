pluginManagement {
    repositories {
        // 官方优先：插件门户和官方仓库，确保插件解析不会被镜像短暂故障阻断
        gradlePluginPortal()
        mavenCentral()
        google()
        // 阿里云镜像（备用，速度快但可能临时不可用）
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // 华为云镜像（备用，覆盖阿里云未同步的 artifact）
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像（最快）
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 华为云镜像（备用）
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        // JitPack（WheelPickerCompose 等第三方库）
        maven { url = uri("https://jitpack.io") }
        // 官方源（兜底）
        google()
        mavenCentral()
    }
}

rootProject.name = "排班日历"
include(":app")
