plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace  = "com.schedulecalendar.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.schedulecalendar.app"
        minSdk        = 26
        targetSdk     = 36
        versionCode   = 2026080301
        versionName   = "2026080301"
    }

    signingConfigs {
        create("release") {
                storeFile = null
                storePassword = ""
                keyAlias = ""
                keyPassword = ""
            }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            signingConfig     = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    // Kotlin 2.0+ 使用独立 Compose Compiler 插件，不再需要 composeOptions.kotlinCompilerExtensionVersion

    // Room schema 导出目录（便于追踪迁移历史，Google 正式推荐）
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
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

dependencies {
    implementation(libs.core.ktx)
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
    implementation(libs.glance.material3)

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
}
