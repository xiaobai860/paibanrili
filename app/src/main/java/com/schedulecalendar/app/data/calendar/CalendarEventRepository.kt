package com.schedulecalendar.app.data.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统日历账户信息
 */
data class CalendarAccountInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val calendarIds: Set<Long> = setOf(id)
)

/**
 * 系统日历事件信息
 */
data class CalendarEventInfo(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val description: String?,
    val dtStart: Long,
    val dtEnd: Long,
    val allDay: Boolean,
    val eventLocation: String?,
    val accountName: String,
    val calendarDisplayName: String,
    val rrule: String? = null
)

/**
 * 系统日历数据仓库
 * 提供对 Android Calendar Provider 的读写操作
 */
@Singleton
class CalendarEventRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** 自定义账户类型，与应用包名一致，对应 authenticator.xml 中的 accountType */
        const val ACCOUNT_TYPE = "com.schedulecalendar.app"
        /** 账户名，使用中文应用名 */
        const val ACCOUNT_NAME = "排班日历"
        /** 日程日历显示名称后缀 */
        private const val SCHEDULE_SUFFIX = "-日程"
        /** 纪念日日历显示名称后缀 */
        private const val ANNIVERSARY_SUFFIX = "-纪念日"
        /** 提醒日历显示名称后缀 */
        private const val REMINDER_SUFFIX = "-提醒"
        /** getAllEvents 结果缓存有效时长（毫秒）：短 TTL 避免首次/频繁进入时反复全量拉取系统日历 */
        private const val EVENTS_CACHE_TTL_MS = 5_000L
    }

    /** getAllEvents 结果缓存（带失效），减少首次进入事项 Tab 时的全量 ContentProvider 查询 */
    @Volatile
    private var allEventsCache: List<CalendarEventInfo>? = null
    @Volatile
    private var allEventsCacheTime: Long = 0L

    /**
     * 使 getAllEvents 缓存失效（系统日历事件变化时由观察者调用），确保下次查询拿到最新数据
     */
    fun invalidateEventsCache() {
        allEventsCache = null
        allEventsCacheTime = 0L
    }

    /**
     * 获取设备上所有已同步的日历账户
     */
    fun getAllAccounts(): List<CalendarAccountInfo> {
        val accounts = mutableListOf<CalendarAccountInfo>()
    
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )
    
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null, null, null
        )?.use { cursor ->
            // 所有日历都按单个日历拆分为独立条目
            while (cursor.moveToNext()) {
                val calId = cursor.getLong(0)
                val rawDisplayName = cursor.getString(1)
                val rawAccountName = cursor.getString(2)
                val accountType = cursor.getString(3) ?: ""
    
                val calDisplayName = rawDisplayName?.trim()?.takeIf { it.isNotBlank() } ?: ""
                val accountName = rawAccountName?.trim()?.takeIf { it.isNotBlank() } ?: ""
                val displayName = calDisplayName.ifEmpty { accountName.ifEmpty { "未知日历" } }
                val info = CalendarAccountInfo(
                    id = calId,
                    displayName = displayName,
                    accountName = accountName,
                    accountType = accountType,
                    calendarIds = setOf(calId)
                )
                accounts.add(info)
            }
        }
    
        // 应用自定义账户类型排在最前面，固定顺序：日程 > 纪念日 > 提醒 > 外部账户
        accounts.sortWith(compareBy<CalendarAccountInfo> {
            when {
                it.accountType != ACCOUNT_TYPE -> "ZZZ" // 外部账户排在最后
                it.displayName.contains(SCHEDULE_SUFFIX) -> "A"      // 日程
                it.displayName.contains(ANNIVERSARY_SUFFIX) -> "B"  // 纪念日
                it.displayName.contains(REMINDER_SUFFIX) -> "C"     // 提醒
                else -> "D"
            }
        })
    
        return accounts
    }

    /**
     * 根据事件 ID 查询单个日历事件
     */
    fun getEventById(eventId: Long): CalendarEventInfo? {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.RRULE
        )
        return try {
            context.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                projection, null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val calId = cursor.getLong(1)
                    val calInfo = getCalendarInfo(calId)
                    CalendarEventInfo(
                        id = cursor.getLong(0),
                        calendarId = calId,
                        title = cursor.getString(2) ?: "无标题",
                        description = cursor.getString(3),
                        dtStart = cursor.getLong(4),
                        dtEnd = cursor.getLong(5),
                        allDay = cursor.getInt(6) == 1,
                        eventLocation = cursor.getString(7),
                        accountName = calInfo?.accountName ?: "",
                        calendarDisplayName = calInfo?.displayName ?: "",
                        rrule = cursor.getString(8)
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取所有可见日历事件（不限时间范围）
     * 优化：批量加载日历信息，避免 N+1 查询
     */
    fun getAllEvents(): List<CalendarEventInfo> {
        // 命中有效缓存则直接复用，避免每次进入事项 Tab 都全量查询系统日历
        val cached = allEventsCache
        if (cached != null && System.currentTimeMillis() - allEventsCacheTime < EVENTS_CACHE_TTL_MS) {
            return cached
        }

        val events = mutableListOf<CalendarEventInfo>()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.RRULE
        )

        // 先批量加载所有日历信息
        val calendarInfoMap = loadAllCalendarInfo()

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            null, null,
            "${CalendarContract.Events.DTSTART} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val calendarId = cursor.getLong(1)
                val title = cursor.getString(2) ?: "无标题"
                val description = cursor.getString(3)
                val dtStart = cursor.getLong(4)
                val dtEnd = cursor.getLong(5)
                val allDay = cursor.getInt(6) == 1
                val location = cursor.getString(7)
                val rrule = cursor.getString(8)
                val calInfo = calendarInfoMap[calendarId]
                events.add(
                    CalendarEventInfo(
                        id = eventId, calendarId = calendarId, title = title,
                        description = description, dtStart = dtStart, dtEnd = dtEnd,
                        allDay = allDay, eventLocation = location,
                        accountName = calInfo?.accountName ?: "",
                        calendarDisplayName = calInfo?.displayName ?: "",
                        rrule = rrule
                    )
                )
            }
        }
        allEventsCache = events
        allEventsCacheTime = System.currentTimeMillis()
        return events
    }

    /**
     * 获取单个日历的基本信息
     */
    private fun getCalendarInfo(calendarId: Long): CalendarInfo? {
        val projection = arrayOf(
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME
        )

        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
            projection, null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayName = cursor.getString(0)?.trim()?.takeIf { it.isNotBlank() }
                    ?: cursor.getString(1)?.trim()?.takeIf { it.isNotBlank() }
                    ?: "未知日历"
                return CalendarInfo(
                    displayName = displayName,
                    accountName = cursor.getString(1) ?: ""
                )
            }
        }
        return null
    }

    /**
     * 批量加载所有可见日历的信息（避免 N+1 查询）
     */
    private fun loadAllCalendarInfo(): Map<Long, CalendarInfo> {
        val map = mutableMapOf<Long, CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME
        )
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection, null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val displayName = cursor.getString(1)?.trim()?.takeIf { it.isNotBlank() }
                        ?: cursor.getString(2)?.trim()?.takeIf { it.isNotBlank() }
                        ?: "未知日历"
                    map[id] = CalendarInfo(
                        displayName = displayName,
                        accountName = cursor.getString(2) ?: ""
                    )
                }
            }
        } catch (_: Exception) {}
        return map
    }

    /**
     * 创建新的日历事件
     * @param title 事件标题
     * @param description 描述
     * @param dtStart 开始时间戳（毫秒）
     * @param dtEnd 结束时间戳（毫秒）
     * @param allDay 是否全天事件
     * @param location 地点
     * @param calendarId 日历ID，null则使用第一个可用日历
     * @param rrule 重复规则 (RRULE)，null表示不重复
     * @param reminderMinutes 提前提醒分钟数，null表示不提醒
     * @param colorHex 事件颜色 (hex格式如 #FF0000)
     * @return 创建成功返回事件ID，失败返回-1
     */
    fun createEvent(
        title: String,
        description: String?,
        dtStart: Long,
        dtEnd: Long,
        allDay: Boolean = false,
        location: String? = null,
        calendarId: Long? = null,
        rrule: String? = null,
        reminderMinutes: Int? = null,
        colorHex: String? = null
    ): Long {
        return try {
            val calId = calendarId ?: getFirstWritableCalendarId() ?: return -1L
            val hasReminder = reminderMinutes != null && reminderMinutes >= 0
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, dtStart)
                put(CalendarContract.Events.DTEND, dtEnd)
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else java.util.TimeZone.getDefault().id)
                if (rrule != null) {
                    put(CalendarContract.Events.RRULE, rrule)
                }
                if (colorHex != null) {
                    try {
                        val colorInt = colorHex.toColorInt()
                        put(CalendarContract.Events.EVENT_COLOR, colorInt)
                    } catch (_: Exception) {}
                }
                // 设置 HAS_ALARM 标志，通知系统此事件有提醒
                if (hasReminder) {
                    put(CalendarContract.Events.HAS_ALARM, 1)
                }
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = ContentUris.parseId(uri ?: return -1L)

            // 添加提醒
            if (hasReminder) {
                addReminder(eventId, reminderMinutes)
                // 国产 ROM 兼容：通过 ExtendedProperties 设置 need_alarm 标志
                // 国产 ROM（小米/华为/OPPO/vivo）不使用标准 hasAlarm 字段，
                // 而是通过此扩展属性判断是否触发闹钟提醒
                addExtendedProperty(eventId, "{\"need_alarm\":true}")
            }

            eventId
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * 为事件添加提醒
     */
    private fun addReminder(eventId: Long, minutesBefore: Int): Long? {
        return try {
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutesBefore)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            val uri = context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
            val reminderId = uri?.let { ContentUris.parseId(it) }
            if (reminderId != null) {
                // reminder added successfully
            } else {
                // addReminder returned null uri
            }
            reminderId
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 为事件添加扩展属性（国产 ROM 兼容）
     * 国产 ROM（小米/华为/OPPO/vivo）通过 ExtendedProperties 中的
     * {"need_alarm":true} 判断是否触发闹钟提醒
     */
    private fun addExtendedProperty(eventId: Long, value: String): Long? {
        return try {
            val values = android.content.ContentValues().apply {
                put(CalendarContract.ExtendedProperties.EVENT_ID, eventId)
                put(CalendarContract.ExtendedProperties.NAME, "customAppData")
                put(CalendarContract.ExtendedProperties.VALUE, value)
            }
            val uri = context.contentResolver.insert(
                CalendarContract.ExtendedProperties.CONTENT_URI, values
            )
            val propId = uri?.let { ContentUris.parseId(it) }
            if (propId != null) {
                // extended property added
            }
            propId
        } catch (e: Exception) {
            // 部分国产 ROM 可能不支持 ExtendedProperties 写入，忽略错误
            null
        }
    }

    /**
     * 获取第一个可写日历的ID
     * 优先使用应用本地日历（显示名称为应用名），其次使用其他可写日历
     */
    private fun getFirstWritableCalendarId(): Long? {
        // 优先查找应用本地日历
        val localCalId = getOrCreateLocalCalendarId()
        if (localCalId != null) return localCalId

        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取或创建应用日历
     * 使用自定义账户类型（应用包名），通过 AccountAuthenticator 注册到系统，
     * 使日历账户在系统日历账户管理中可见。
     * @return 日历ID，如果无法创建则返回null
     */
    fun getOrCreateLocalCalendarId(): Long? {
        val appName = try {
            context.getString(context.applicationInfo.labelRes)
        } catch (_: Exception) {
            ACCOUNT_NAME
        }
        val scheduleDisplayName = "$appName$SCHEDULE_SUFFIX"
        return try {
            // 0. 先确保 AccountManager 账户存在
            val accountManager = AccountManager.get(context)
            val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
            val existingAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE)
            val accountExists = existingAccounts.isNotEmpty()

            if (!accountExists) {
                val hasWritePermission = android.content.pm.PackageManager.PERMISSION_GRANTED ==
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR)
                if (!hasWritePermission) {
                    return null
                }
                val added = accountManager.addAccountExplicitly(account, null, null)
                if (added) {
                    android.content.ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1)
                    android.content.ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true)
                    android.content.ContentResolver.setMasterSyncAutomatically(true)
                } else {
                    return null
                }
            }

            // 1. 查找已存在的日程日历（排除纪念日日历）
            var existingCalId: Long? = null
            var needsUpdate = false
            
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} NOT LIKE ?",
                arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE, "%$ANNIVERSARY_SUFFIX"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    existingCalId = cursor.getLong(0)
                    val currentDisplayName = cursor.getString(1) ?: ""
                    needsUpdate = currentDisplayName != scheduleDisplayName
                }
            }
            
            if (existingCalId != null && needsUpdate) {
                val values = android.content.ContentValues().apply {
                    put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, scheduleDisplayName)
                    put(CalendarContract.Calendars.NAME, scheduleDisplayName)
                }
                val updateUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                    .build()
                context.contentResolver.update(
                    updateUri, values,
                    "${CalendarContract.Calendars._ID} = ?",
                    arrayOf(existingCalId.toString())
                )
            }
            
            if (existingCalId != null) {
                return existingCalId
            }

            // 2. 创建新的日程日历
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                put(CalendarContract.Calendars.NAME, scheduleDisplayName)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, scheduleDisplayName)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF4285F4.toInt())
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
                put(CalendarContract.Calendars.CAN_ORGANIZER_RESPOND, 0)
                put(CalendarContract.Calendars.ALLOWED_REMINDERS, "0,1,2,3")
                put(CalendarContract.Calendars.ALLOWED_AVAILABILITY, "0,1,2")
                put(CalendarContract.Calendars.ALLOWED_ATTENDEE_TYPES, "0,1,2")
            }
            val uri = context.contentResolver.insert(
                CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                    .build(),
                values
            )
            val newCalId = uri?.let { ContentUris.parseId(it) }
            newCalId
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取或创建提醒专用日历ID
     * 与应用日历使用相同的账户（AccountManager），但创建独立的日历条目，
     * 显示名称为 "应用名-提醒"，颜色为绿色。
     * 用于上下班提醒日历事件的创建和管理。
     * @return 提醒日历ID，失败返回null
     */
    fun getOrCreateReminderCalendarId(): Long? {
        val appName = try {
            context.getString(context.applicationInfo.labelRes)
        } catch (_: Exception) {
            ACCOUNT_NAME
        }
        val displayName = "$appName$REMINDER_SUFFIX"
        return try {
            // 查找已有的提醒日历
            var existingCalId: Long? = null
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} = ?",
                arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE, displayName),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    existingCalId = cursor.getLong(0)
                }
            }
            if (existingCalId != null) return existingCalId

            // 确保账户存在
            getOrCreateLocalCalendarId() ?: return null

            // 创建提醒日历
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                put(CalendarContract.Calendars.NAME, displayName)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF059669.toInt()) // 绿色
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
                put(CalendarContract.Calendars.CAN_ORGANIZER_RESPOND, 0)
                put(CalendarContract.Calendars.ALLOWED_REMINDERS, "0,1,2,3")
                put(CalendarContract.Calendars.ALLOWED_AVAILABILITY, "0,1,2")
                put(CalendarContract.Calendars.ALLOWED_ATTENDEE_TYPES, "0,1,2")
            }
            val uri = context.contentResolver.insert(
                CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                    .build(),
                values
            )
            val newCalId = uri?.let { ContentUris.parseId(it) }
            newCalId
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取或创建纪念日专用日历ID
     * 与应用日历使用相同的账户（AccountManager），但创建独立的日历条目，
     * 显示名称为 "应用名-纪念日"，颜色为红色。
     * @return 纪念日日历ID，失败返回null
     */
    fun getOrCreateAnniversaryCalendarId(): Long? {
        val appName = try {
            context.getString(context.applicationInfo.labelRes)
        } catch (_: Exception) {
            ACCOUNT_NAME
        }
        val displayName = "$appName$ANNIVERSARY_SUFFIX"
        return try {
            // 查找已有的纪念日日历
            var existingCalId: Long? = null
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} = ?",
                arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE, displayName),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    existingCalId = cursor.getLong(0)
                }
            }
            if (existingCalId != null) return existingCalId

            // 确保账户存在
            getOrCreateLocalCalendarId() ?: return null

            // 创建纪念日日历
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                put(CalendarContract.Calendars.NAME, displayName)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFFE53935.toInt()) // 红色
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
                put(CalendarContract.Calendars.CAN_ORGANIZER_RESPOND, 0)
                put(CalendarContract.Calendars.ALLOWED_REMINDERS, "0,1,2,3")
                put(CalendarContract.Calendars.ALLOWED_AVAILABILITY, "0,1,2")
                put(CalendarContract.Calendars.ALLOWED_ATTENDEE_TYPES, "0,1,2")
            }
            val uri = context.contentResolver.insert(
                CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                    .build(),
                values
            )
            val newCalId = uri?.let { ContentUris.parseId(it) }
            newCalId
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 更新日历事件
     */
    fun updateEvent(event: CalendarEventInfo): Boolean {
        return try {
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.TITLE, event.title)
                put(CalendarContract.Events.DESCRIPTION, event.description)
                put(CalendarContract.Events.DTSTART, event.dtStart)
                put(CalendarContract.Events.DTEND, event.dtEnd)
                put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_LOCATION, event.eventLocation)
                put(CalendarContract.Events.RRULE, event.rrule)
            }
            val rows = context.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id),
                values, null, null
            )
            rows > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 删除日历事件
     */
    fun deleteEvent(eventId: Long): Boolean {
        return try {
            val rows = context.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                null, null
            )
            rows > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 查找应用本地日历中符合标题前缀的事件
     * 用于上下班提醒日历事件的管理
     * @param titlePrefix 标题前缀，如 "[上下班提醒]"
     * @return 事件ID列表
     */
    fun findEventsByTitlePrefix(titlePrefix: String): List<Long> {
        val eventIds = mutableListOf<Long>()
        val calId = getOrCreateReminderCalendarId() ?: return eventIds
        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.TITLE} LIKE ?",
                arrayOf(calId.toString(), "$titlePrefix%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    eventIds.add(cursor.getLong(0))
                }
            }
            eventIds
        } catch (e: Exception) {
            eventIds
        }
    }

    /**
     * 批量删除事件
     * @return 删除的事件数
     */
    fun deleteEvents(eventIds: List<Long>): Int {
        var deleted = 0
        for (id in eventIds) {
            if (deleteEvent(id)) deleted++
        }
        return deleted
    }

    /**
     * 查找指定日期和标题的提醒事件是否已存在
     * 用于避免重复创建上下班提醒日历事件
     */
    fun findEventByDateAndTitle(date: String, title: String): Long? {
        val calId = getOrCreateReminderCalendarId() ?: return null
        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.TITLE} = ? AND ${CalendarContract.Events.DESCRIPTION} LIKE ?",
                arrayOf(calId.toString(), title, "%$date%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 禁用日历账户（关闭同步）
     */
    fun setAccountSync(calendarId: Long, sync: Boolean): Boolean {
        return try {
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Calendars.SYNC_EVENTS, if (sync) 1 else 0)
            }
            val rows = context.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
                values, null, null
            )
            rows > 0
        } catch (e: Exception) {
            false
        }
    }

    private data class CalendarInfo(
        val displayName: String,
        val accountName: String
    )

    /**
     * 获取指定日期的所有事件（含年度重复纪念日）
     * @param dateStr 日期字符串，格式 "yyyy-MM-dd"
     * @return 当天所有的事件列表（纪念日 + 日程）
     */
    /**
     * 获取指定日期区间内的事件（在 ContentProvider 端用 DTSTART/DTEND 过滤，避免全量拉取）。
     * 用于按日期/按月高效查询，替代 [getAllEvents] 的全量扫描。
     * @param rangeStartMs 区间起始毫秒（含）
     * @param rangeEndMs 区间结束毫秒（不含）
     */
    /**
     * 获取指定日期区间内的事件（在 ContentProvider 端用 DTSTART/DTEND 过滤，避免全量拉取）。
     * 用于按日期/按月高效查询，替代 [getAllEvents] 的全量扫描。
     * @param rangeStartMs 区间起始毫秒（含）
     * @param rangeEndMs 区间结束毫秒（不含）
     */
    fun getAllEventsInRange(rangeStartMs: Long, rangeEndMs: Long): List<CalendarEventInfo> {
        val events = mutableListOf<CalendarEventInfo>()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.RRULE
        )
        // 选择参数为 (DTEND > rangeStart) AND (DTSTART < rangeEnd)，覆盖区间内发生或跨越边界的事件
        val selection = "(${CalendarContract.Events.DTEND} > ?) AND (${CalendarContract.Events.DTSTART} < ?)"
        val selectionArgs = arrayOf(rangeStartMs.toString(), rangeEndMs.toString())

        val calendarInfoMap = loadAllCalendarInfo()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection, selection, selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val calendarId = cursor.getLong(1)
                val calInfo = calendarInfoMap[calendarId]
                events.add(
                    CalendarEventInfo(
                        id = eventId, calendarId = calendarId,
                        title = cursor.getString(2) ?: "无标题",
                        description = cursor.getString(3),
                        dtStart = cursor.getLong(4),
                        dtEnd = cursor.getLong(5),
                        allDay = cursor.getInt(6) == 1,
                        eventLocation = cursor.getString(7),
                        accountName = calInfo?.accountName ?: "",
                        calendarDisplayName = calInfo?.displayName ?: "",
                        rrule = cursor.getString(8)
                    )
                )
            }
        }
        return events
    }

    /**
     * 获取指定日期的所有事件（含年度重复纪念日）
     * 优化：使用 [queryEventsInRange] 在 ContentProvider 端按区间过滤，
     * 仅把当天相关事件拉入 JVM，避免每次点选日期都全量扫描所有日历事件。
     * @param dateStr 日期字符串，格式 "yyyy-MM-dd"
     * @return 当天所有的事件列表（纪念日 + 日程）
     */
    fun getEventsForDate(dateStr: String): List<CalendarEventInfo> {
        val parts = dateStr.split("-")
        if (parts.size != 3) return emptyList()
        val year = parts[0].toIntOrNull() ?: return emptyList()
        val month = parts[1].toIntOrNull() ?: return emptyList()
        val day = parts[2].toIntOrNull() ?: return emptyList()

        val selCal = java.util.Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val rangeStart = selCal.timeInMillis
        val rangeEnd = rangeStart + 86400000L

        val events = getAllEventsInRange(rangeStart, rangeEnd)
        val result = mutableListOf<CalendarEventInfo>()
        for (event in events) {
            if (event.allDay) {
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = event.dtStart
                val eventMonth = cal.get(java.util.Calendar.MONTH) + 1
                val eventDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
                if (eventMonth == month && eventDay == day) {
                    result.add(event)
                    continue
                }
                if (event.rrule?.contains("FREQ=YEARLY") == true) continue
            } else {
                if (event.dtStart < rangeEnd && event.dtEnd > rangeStart) {
                    result.add(event)
                }
            }
        }
        return result
    }

    /**
     * 获取账户的分类 key
     * 所有日历都使用 "calId:<id>"，因为每个日历都是独立条目
     */
    fun getAccountKey(account: CalendarAccountInfo): String {
        return "calId:${account.calendarIds.first()}"
    }
}
