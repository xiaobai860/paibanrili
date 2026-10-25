import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        load(FileInputStream(file))
    }
}

android {
    namespace  = "com.schedulecalendar.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.schedulecalendar.app"
        minSdk        = 34
        targetSdk     = 36
        // versionName：展示用版本号 = 年月日 + 两位迭代版号（当天第一版 01，第二版 02……）
        // versionCode：系统用的递增整数，**每次发版 +1**，与 versionName 无关。
        //   ⚠️ 坑（2026-09-14 实测）：设备上曾装过日期型版本 2026083103，远大于小整数计数器，
        //   一旦 versionCode 低于设备已装版本，覆盖安装会报 INSTALL_FAILED_VERSION_DOWNGRADE；
        //   而 `-d` 只对 debuggable 包生效、release 包无效 → 只能「完整卸载（不带 -k）→ 安装」。
        //   （不要为此把 versionCode 改成日期型。）
        // 注意：Android 仅比较 versionCode 决定是否允许覆盖安装。
        versionCode   = 226
        versionName   = "2026102601"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile     = file(localProperties.getProperty("RELEASE_STORE_FILE"))
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias      = localProperties.getProperty("RELEASE_KEY_ALIAS")
            keyPassword   = localProperties.getProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            // 与 release 共存：仪器测试（Room 迁移测试）必须装 debug 变体，
            // 若共用 applicationId 会因签名不同而要求卸载 release（丢用户数据）。
            applicationIdSuffix = ".debug"
            // 诊断日志开关（AppLog 的唯一依据）：debug 包恒开；
            // 之前写 "false" 并靠 `BuildConfig.DEBUG || …` 兜底，会让 IDE 判定该布尔表达式"可简化"
            // （它按当前变体常量折叠），照改会破坏 release 的自动关闭语义，故直接收敛为单一常量。
            buildConfigField("boolean", "DIAGNOSTIC_LOGS", "true")
            // 输出目标：debug 包额外写一份日志文件到 App 私有目录，便于抓取完整日志。
            buildConfigField("boolean", "LOG_TO_FILE", "true")
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            signingConfig     = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 诊断日志默认关闭；`-PdiagLogs=true` 出"诊断版 release"（release 签名 + 诊断日志全局开）供现场调试
            buildConfigField("boolean", "DIAGNOSTIC_LOGS", (project.findProperty("diagLogs") == "true").toString())
            // 输出目标：release **也写日志文件**（只收 w/e —— d/i 被 DIAGNOSTIC_LOGS=false
            // 挡在门外），供「设置 → 关于 → 导出日志文件」做现场诊断；
            // `-PlogToFile=false` 可显式关掉 → FileLog 类随之被 R8 裁掉。
            buildConfigField("boolean", "LOG_TO_FILE", (project.findProperty("logToFile") != "false").toString())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // buildConfig=true：生成 BuildConfig，供 AppLog 的两个**编译期常量门控**使用
    // （DIAGNOSTIC_LOGS / LOG_TO_FILE）。
    // ⚠️ 不要另起「用 BuildConfig.DEBUG 门控的日志」：那会形成第二套开关，release 下
    //   难以保证统一关闭；且 IDE 会把 `BuildConfig.DEBUG || X` 判为可简化常量表达式，
    //   一旦被人照改（硬编码 true），release 就会全量打印调试日志。
    //   release 零调试输出**只认** DIAGNOSTIC_LOGS。
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Kotlin 2.0+ 使用独立 Compose Compiler 插件，不再需要 composeOptions.kotlinCompilerExtensionVersion

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // 清理运行时无用元数据（约省 50KB+）：
            // - DebugProbesKt.bin：kotlinx-coroutines 协程调试探针，仅 kotlinx.coroutines.debug 开启时使用
            // - *.version / INDEX.LIST / NOTICE.md：库版本指纹与许可索引，纯信息
            // - app-metadata.properties / version-control-info.textproto：AGP 构建元数据 / VCS 信息
            excludes += "DebugProbesKt.bin"
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/**/LICENSE.txt"
            excludes += "/META-INF/com/android/build/gradle/app-metadata.properties"
            excludes += "/META-INF/version-control-info.textproto"
        }
        // useLegacyPackaging = true — 控制 .so 打包方式，对 strip 警告无抑制效果
        jniLibs { useLegacyPackaging = true }
    }

    // 使用日期格式版本号（如 2026071501），忽略 HighAppVersionCode 警告
    // 抑制资源相关警告：图标形状(默认模板图标)、图标重复(方形/圆形相同)、新版API属性、新版依赖提示
    // checkReleaseBuilds = false：Android Studio 的「Generate Signed App Bundle/APK」菜单在 AS 运行期间会锁住 lint-cache 文件，
    // 导致 lintVitalAnalyzeRelease 因 FileSystemException 失败并产生警告。跳过 release 的 lint 即可消除 Build 菜单中的 5 个警告。
    lint {
        abortOnError       = false
        checkReleaseBuilds = false
        disable += "HighAppVersionCode"
        disable += "IconLauncherShape"
        disable += "IconDuplicates"
        disable += "UnusedAttribute"
        disable += "NewerVersionAvailable"
        disable += "ReportShortcutUsage"
    }
}

// Room schema 导出目录（便于追踪迁移历史，Google 正式推荐）
// ksp 配置块必须位于 android {} 之外（顶层 Project 作用域），否则触发 DSL "Suspicious receiver type" 警告
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.core.ktx)
    // 仪器测试（Room 迁移测试用）
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)

    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt — 全部使用 KSP（不再需要 kapt）
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.prefs)

    // Lifecycle runtime-compose（collectAsStateWithLifecycle）
    implementation(libs.lifecycle.runtime.compose)

    // Glance（Compose-first 桌面小组件）
    implementation(libs.glance.appwidget)

    // WorkManager（小组件周期刷新兜底：事件驱动为主 + 15min 周期兜底）
    implementation(libs.workmanager.runtime)

    // Coroutines
    implementation(libs.coroutines.android)

    // Gson
    implementation(libs.gson)

    // kotlinx-serialization（Navigation 2.8 类型安全路由依赖）
    implementation(libs.kotlinx.serialization.json)

    // WheelPickerCompose（滚轮选择器组件）
    implementation(libs.wheel.picker.compose)

    // 农历计算库（tyme4j 是 lunar 的升级版）
    implementation(libs.tyme4j)

    // DocumentFile（SAF 目录文件操作）
    implementation(libs.documentfile)

    // 单元测试：守护手工维护的法定节假日/调休数据（覆盖年份 + 一致性）
    testImplementation(libs.junit)
    // 自定义 Lint 规则（实时守护日志规范：裸 Log / 参数化 TAG），配合 LoggingConventionTest 守护测试。
    // lintChecks 让规则进入 app 的 lint 分析 + Android Studio 实时检查。
    lintChecks(project(":lint"))
}
