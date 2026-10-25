// app/src/main/java/com/schedulecalendar/app/domain/util/AppLog.kt
// 文件级抑制 "inline 收益不显著" 提示：本文件 inline 的目的**不是运行期提速**，
// 而是让 release 构建把 `if (编译期常量 false)` 的诊断分支连同**参数求值**（字符串拼接）一起剔除，
// 从而生产包零日志开销。去掉 inline 会让 release 真实求值参数 → 开销回归。故此提示必须抑制。
@file:Suppress("NOTHING_TO_INLINE")
package com.schedulecalendar.app.domain.util

import android.util.Log
import com.schedulecalendar.app.BuildConfig

/**
 * 全项目唯一日志出口（统一标准化）。
 *
 * 目的：让调试/诊断日志可以**安全地整体开/关**，避免各处裸 `Log` 造成漏关漏开。
 *
 * ## 三个维度（都在这里集中决定，调用点只管"打一条什么级别的日志"）
 *
 * ### 1. 开关（编译期，BuildConfig）
 * - `BuildConfig.DIAGNOSTIC_LOGS`：诊断级（d/i）是否输出。
 *   debug 变体恒为 true；release 默认 false → `if (false)`，R8 会把整个分支
 *   **连同消息字符串拼接一起剔除**，生产包零开销（不写 logcat，省性能与电量）。
 *   需要 release 现场调试时：`gradlew assembleRelease -PdiagLogs=true` 出"诊断版 release"
 *   （仍是 release 签名 + R8，但诊断日志全局打开），用完即弃，日常 release 不受影响。
 * - `BuildConfig.LOG_TO_FILE`：是否**额外**写一份到 App 私有目录的文件（见 `FileLog`）。
 *   debug 与 release 默认 true —— release 下**只有 w/e 会落盘**（d/i 被 DIAGNOSTIC_LOGS 挡在门外），
 *   量极低；`-PlogToFile=false` 可整体关掉 → `FileLog` 相关代码被 R8 整个剔除，
 *   不产生任何线程与磁盘 IO。
 *
 * ⚠️ 只用这两个字段，不要写成 `BuildConfig.DEBUG || BuildConfig.DIAGNOSTIC_LOGS`：
 *   那样 IDE 会按当前变体把该布尔表达式判为"可简化"的常量表达式，
 *   一旦被人照着简化（硬编码 true），release 就会全量打印诊断日志、开销回归。
 *
 * ### 2. 级别（普通日志 vs 调试信息）
 * - `d` / `i` = **调试/诊断信息**（流程追踪、缓存命中、跳过原因…）：
 *   仅 debug 生效，release 自动关闭。**热路径上不要用 w/e 打这类内容。**
 * - `w` / `e` = **真实错误/告警/异常**：**总是输出**（release 也保留，是异常/崩溃诊断的
 *   生命线，量低）。**铁律：禁止为"省开销"去门控关闭它们。**
 *
 * ### 3. 输出目标
 * - logcat：始终（系统默认，adb logcat 可抓）。
 * - 文件：可选，由 `BuildConfig.LOG_TO_FILE` 控制（`FileLog`）。
 *
 * 签名与 `android.util.Log` 完全一致，便于把历史 `Log.x(...)` 直接改成 `AppLog.x(...)`。
 *
 * ## 约定（新增日志必须遵守）
 * - TAG 必须来自 `LogTags`（`AppLog.x(LogTags.XXX, ...)`），**不得硬编码字符串**；
 * - 诊断/调试信息一律 `AppLog.d` / `AppLog.i`；仅真实错误用 `AppLog.w` / `AppLog.e`；
 * - 消息格式：诊断级写 `动作: 细节`（冒号后一个空格）；错误级写 `动作 failed: 原因`；
 * - 禁止裸用 `android.util.Log.d/v/i`，禁止 `printStackTrace()`（改用 `AppLog.e(tag, msg, tr)`）。
 *
 * ⚠️ 下面所有方法都带 `inline`，**切勿去掉**：
 * 目的不是运行期提速，而是让 release 构建把 `if (编译期常量 false)` 的诊断分支
 * 连同**参数求值**（字符串拼接）整体剔除 → 生产包零开销。去掉 inline 后，
 * release 仍会真实求值参数并走运行期判断，日志开销回归。
 * 编译器那句 "Expected performance impact from inlining is insignificant" 指的是**运行期速度**，
 * 不是此处关心的**构建期消除**，已用文件级 `@file:Suppress("NOTHING_TO_INLINE")` 抑制，勿删。
 */
object AppLog {
    inline fun d(tag: String, msg: String) {
        if (BuildConfig.DIAGNOSTIC_LOGS) {
            Log.d(tag, msg)
            if (BuildConfig.LOG_TO_FILE) FileLog.append('D', tag, msg, null)
        }
    }

    inline fun i(tag: String, msg: String) {
        if (BuildConfig.DIAGNOSTIC_LOGS) {
            Log.i(tag, msg)
            if (BuildConfig.LOG_TO_FILE) FileLog.append('I', tag, msg, null)
        }
    }

    inline fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        if (BuildConfig.LOG_TO_FILE) FileLog.append('W', tag, msg, null)
    }

    inline fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        if (BuildConfig.LOG_TO_FILE) FileLog.append('E', tag, msg, null)
    }

    inline fun w(tag: String, msg: String, tr: Throwable?) {
        Log.w(tag, msg, tr)
        if (BuildConfig.LOG_TO_FILE) FileLog.append('W', tag, msg, tr)
    }

    inline fun e(tag: String, msg: String, tr: Throwable?) {
        Log.e(tag, msg, tr)
        if (BuildConfig.LOG_TO_FILE) FileLog.append('E', tag, msg, tr)
    }
}
