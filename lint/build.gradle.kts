import org.gradle.api.JavaVersion

// 自定义 Lint 规则模块（纯 Java，避免引入 kotlin-jvm 插件与项目 Kotlin 版本耦合）。
// 仅 compileOnly 依赖 lint-api（运行时不打包，lint 在宿主 AGP 进程加载）。
plugins {
    `java-library`
}

dependencies {
    compileOnly("com.android.tools.lint:lint-api:32.4.0")
    compileOnly("com.android.tools.lint:lint-checks:32.4.0")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
