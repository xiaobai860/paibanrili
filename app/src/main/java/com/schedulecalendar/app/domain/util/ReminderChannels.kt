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
 * ## 本类的策略（官方标准写法）
 * - 渠道**不存在** → 用带闹钟铃声 + 振动的配置创建（字段在此刻一次设定）；
 * - 渠道**已存在** → 原样保留，**不删不重建、不改 ID**。
 *
 * ### 为什么不"检测到声音不对就删掉重建 / 换 ID"
 * - 渠道的 sound / importance / vibration **一经创建不可修改**（AOSP：
 *   "Only modifiable before the channel is submitted to createNotificationChannel"），
 *   这是**官方标准做法**——渠道 ID 是语义化常量，字段在创建时定好，之后不动；
 * - 若渠道被 ROM（如 MIUI 的通知管控）或用户在系统设置里锁定/接管，那是**系统行为**，
 *   应用侧不通过换 ID 绕过：换 ID 只会不断堆积"残留渠道"（用户设置里一堆同名条目），
 *   而被锁定的字段在**任何**新渠道 ID 下都可能被 ROM 再次接管；
 * - 需要调整铃声/重要程度时，**由用户在「通知管理」里改**（用户的设置优先，App 不覆盖）。
 *
 * ## 铃声为什么用系统「通知铃声」而不是「闹钟铃声」
 *
 * 官方有两个不同场景，别混用：
 * - 《Build an alarm clock》（做**系统闹钟 App**）→ `DEFAULT_ALARM_ALERT_URI` + `USAGE_ALARM`；
 * - 《Notification channels》（做**通知**）→ `DEFAULT_NOTIFICATION_URI` + `USAGE_NOTIFICATION`。
 *
 * 本应用是**通知式打卡提醒**（到点由 AlarmManager 精确触发，提醒本身是一条通知）
 * → 属于后者。更关键的是**兼容性**（2026-10-29 在 ColorOS / MIUI 双 ROM 实测）：
 * - 国产 ROM 会把渠道的 `USAGE_ALARM` **规范化成 `USAGE_NOTIFICATION`**，
 *   并把声音改写成通知铃声（OPPO 实测：`mSound` 由 alarm_alert 变成 notification_sound、
 *   `mAudioAttributes` 由 USAGE_ALARM 变成 USAGE_NOTIFICATION）
 *   → 写「闹钟铃声」的期望在国产 ROM 上**落空**，实际拿到的仍是通知声；
 * - 写「通知铃声」是所有 ROM 都照单执行的标准路径 → **行为可预测**；
 * - 且通知音量是用户天天在用、知道怎么调的音量（闹钟音量很多人从没碰过）。
 *
 * 「抢注意力」不依赖音量流：锁屏顶屏 60 秒由 `CATEGORY_ALARM` + `setFullScreenIntent(true)`
 * 提供（见 `AlarmReceiver`），与声音走哪个流无关。
 *
 * ⚠️ 若 ROM 或用户在系统设置里锁定了渠道的声音 / 重要程度 → 那是**系统行为**，
 *   由用户在「通知管理」里调整（用户的选择优先，本类不覆盖、不换 ID 绕过）。
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
                // 官方《Notification channels》标准示例的写法：系统**通知铃声** + USAGE_NOTIFICATION
                // （AOSP 注释：a Uri that will point to the current default notification sound
                //  at any given time → 稳定 URI，不会因 App 升级失效）
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
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
