// app/src/main/java/com/schedulecalendar/app/domain/util/ReminderChannels.kt
package com.schedulecalendar.app.domain.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.provider.Settings

/**
 * 提醒类通知的**渠道创建统一出口**（新建时带闹钟铃声 + 振动）。
 *
 * ## 为什么必须统一
 * 此前项目里有**两处创建同一个渠道、配置却不一致**：
 * - `ReminderScheduler.init` —— App 一启动就跑，建的是**没有声音**的渠道（先到者胜）；
 * - `AlarmReceiver` —— 带闹钟铃声的配置写在 `if (channel == null)` 里，
 *   而渠道早已被上一处创建 → **那段配置从未执行过**。
 *
 * 结果：提醒**只弹通知、不响铃**（真机反馈「到点只弹通知」）。
 *
 * ## 本类的策略（极简，刻意不做过度设计）
 * - 渠道**不存在** → 用带闹钟铃声 + 振动的配置创建；
 * - 渠道**已存在** → 原样保留，不动。
 *
 * ### 为什么不"检测到无声就删掉重建"
 * 因为**渠道的 sound / importance / vibration 一经创建不可修改**（Android 8.0+ 限制），
 * 想换铃声唯一的办法是删掉重建。而：
 * - 删渠道只有 `NotificationManager.deleteNotificationChannel`（应用自己调），
 *   **adb 没有对应命令**（`cmd notification` 不提供），用户也只能在系统设置里手动删；
 * - 为改一次铃声引入"迁移标记 + 历史渠道清单 + 删除重建"整套机制，
 *   复杂度与收益不成正比（旧渠道留在系统设置里不影响功能，它已不再被使用）。
 *
 * ⇒ **需要换铃声时，直接改 `CHANNEL_ID`**（如 `work_reminder` → `work_reminder_v2`），
 * 新 ID 就是全新渠道 → 带正确配置创建；旧渠道自然淘汰。
 *
 * ## 铃声为什么用 alarm_alert（系统「闹钟铃声」）
 * - `RingtoneManager.getDefaultUri(TYPE_ALARM)` 在部分国产 ROM 上读不到闹钟铃声
 *   （OPPO 实测返回的也是 `notification_sound` → 变成"叮"的通知音、走通知音量）；
 * - 系统 settings URI `content://settings/system/alarm_alert` 始终有效（官方推荐写法），
 *   它指向用户在「设置 → 声音 → 闹钟铃声」里选的那一首 → 才是真正的"响铃"。
 *   （部分 ROM 会把渠道的 `USAGE_ALARM` 规范化成 `USAGE_NOTIFICATION`，
 *     这是系统行为，应用层无法绕过。）
 */
object ReminderChannels {

    /**
     * 确保提醒渠道存在且**带闹钟铃声 + 振动**。
     *
     * 幂等：渠道已存在时**什么都不做**（渠道的 sound 创建后不可改，改铃声请换 CHANNEL_ID）。
     *
     * @param channelId   渠道 ID（同一 ID 的渠道只会有一份配置）
     * @param channelName 渠道显示名（系统设置里可见）
     * @param description 渠道说明
     */
    fun ensureAlarmChannel(
        context: Context,
        channelId: String,
        channelName: String,
        description: String
    ) {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 已存在 → 不动（尊重用户在系统设置里的自定义；也避免重复创建的开销）
        if (nm.getNotificationChannel(channelId) != null) return

        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            this.description = description
            setSound(
                // 官方常量：始终指向用户当前选择的「闹钟铃声」
                // （AOSP 注释：a Uri that will point to the current default alarm alert
                //  at any given time → 稳定 URI，不会因 App 升级失效）
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            // ⚠️ 不调用 setBypassDnd(true)：绕过勿扰仅对系统应用真正生效，
            // 普通应用调用没有收益（个别 ROM 上还会抛异常）。
        }
        nm.createNotificationChannel(channel)
    }
}
