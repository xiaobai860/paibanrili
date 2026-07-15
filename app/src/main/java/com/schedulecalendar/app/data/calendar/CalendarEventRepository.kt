package com.schedulecalendar.app.data.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统日历账户信息
 */
data class CalendarAccountInfo(
    val id: Long,
    val name: String,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val calendarCount: Int,
    val isEnabled: Boolean = true,
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
        /** 纪念日日历显示名称后缀 */
        private const val ANNIVERSARY_SUFFIX = "-纪念日"
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
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.SYNC_EVENTS
        )

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null, null, null
        )?.use { cursor ->
            // 按账户分组统计，收集所有日历ID
            val accountMap = mutableMapOf<String, Pair<MutableList<Long>, MutableList<CalendarAccountInfo>>>()

            while (cursor.moveToNext()) {
                val calId = cursor.getLong(0)
                val rawDisplayName = cursor.getString(1)
                val rawAccountName = cursor.getString(2)
                val accountType = cursor.getString(3) ?: ""
                val syncEvents = cursor.getInt(4) == 1

                // 对于本应用创建的日历账户（自定义类型），强制使用中文应用名
                val isAppCalendarAccount = accountType == ACCOUNT_TYPE
                val calDisplayName = if (isAppCalendarAccount) {
                    val appName = try {
                        context.getString(context.applicationInfo.labelRes)
                    } catch (_: Exception) {
                        ACCOUNT_NAME
                    }
                    appName
                } else {
                    rawDisplayName?.trim()?.takeIf { it.isNotBlank() } ?: ""
                }
                val accountName = rawAccountName?.trim()?.takeIf { it.isNotBlank() } ?: ""
                
                android.util.Log.d("CalendarEventRepo", "getAllAccounts: calId=$calId, displayName='$calDisplayName', accountName='$accountName', accountType='$accountType'")
                
                // displayName 后备：优先使用日历显示名，其次使用账户名，最后使用"未知日历"
                val displayName = calDisplayName.ifEmpty { accountName.ifEmpty { "未知日历" } }

                val key = "$accountName|$accountType"
                val existing = accountMap[key]
                if (existing != null) {
                    // 同账户的另一个日历，追加日历ID并更新计数
                    existing.first.add(calId)
                    val first = existing.second[0]
                    existing.second[0] = first.copy(calendarCount = first.calendarCount + 1)
                } else {
                    val calIds = mutableListOf(calId)
                    val info = CalendarAccountInfo(
                        id = calId,
                        name = displayName,
                        displayName = displayName,
                        accountName = accountName,
                        accountType = accountType,
                        calendarCount = 1,
                        isEnabled = syncEvents,
                        calendarIds = calIds.toSet()
                    )
                    accountMap[key] = Pair(calIds, mutableListOf(info))
                }
            }

            // 用最终的 calendarIds 更新每个账户
            accountMap.forEach { (_, pair) ->
                val calIds = pair.first.toSet()
                pair.second[0] = pair.second[0].copy(calendarIds = calIds)
            }
            accounts.addAll(accountMap.values.flatMap { it.second })
        }

        // 应用自定义账户类型排在最前面，确保默认选中优先级
        accounts.sortByDescending { if (it.accountType == ACCOUNT_TYPE) 1 else 0 }

        android.util.Log.d("CalendarEventRepo", "getAllAccounts total: ${accounts.size}, accounts=${accounts.map { "${it.displayName}(${it.accountName})" }}")
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
            android.util.Log.e("CalendarEventRepo", "getEventById($eventId) failed", e)
            null
        }
    }

    /**
     * 获取所有可见日历事件（不限时间范围）
     * 优化：批量加载日历信息，避免 N+1 查询
     */
    fun getAllEvents(): List<CalendarEventInfo> {
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
        return events
    }

    /**
     * 获取指定账户下所有日历事件（指定时间范围内）
     * @param startTime 范围开始时间戳（毫秒）
     * @param endTime 范围结束时间戳（毫秒）
     * @param accountName 账户名，null 表示不过滤
     */
    fun getEvents(
        startTime: Long,
        endTime: Long,
        accountName: String? = null
    ): List<CalendarEventInfo> {
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

        val selection = buildString {
            append("${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTEND} <= ?")
            if (accountName != null) {
                append(" AND ${CalendarContract.Events.ACCOUNT_NAME} = ?")
            }
        }
        val selectionArgs = if (accountName != null) {
            arrayOf(startTime.toString(), endTime.toString(), accountName)
        } else {
            arrayOf(startTime.toString(), endTime.toString())
        }

        // 批量加载日历信息
        val calendarInfoMap = loadAllCalendarInfo()

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
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

                // 从缓存中获取日历信息
                val calInfo = calendarInfoMap[calendarId]

                events.add(
                    CalendarEventInfo(
                        id = eventId,
                        calendarId = calendarId,
                        title = title,
                        description = description,
                        dtStart = dtStart,
                        dtEnd = dtEnd,
                        allDay = allDay,
                        eventLocation = location,
                        accountName = calInfo?.accountName ?: "",
                        calendarDisplayName = calInfo?.displayName ?: "",
                        rrule = rrule
                    )
                )
            }
        }

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
                        val colorInt = android.graphics.Color.parseColor(colorHex)
                        put(CalendarContract.Events.EVENT_COLOR, colorInt)
                    } catch (_: Exception) {}
                }
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = ContentUris.parseId(uri ?: return -1L)

            // 添加提醒
            if (reminderMinutes != null && reminderMinutes >= 0) {
                addReminder(eventId, reminderMinutes)
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
                android.util.Log.i("CalendarEventRepo", "Added reminder: eventId=$eventId, minutes=$minutesBefore, reminderId=$reminderId")
            } else {
                android.util.Log.w("CalendarEventRepo", "addReminder returned null uri: eventId=$eventId, minutes=$minutesBefore")
            }
            reminderId
        } catch (e: Exception) {
            android.util.Log.e("CalendarEventRepo", "addReminder failed: eventId=$eventId, minutes=$minutesBefore", e)
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
        return try {
            // 0. 先确保 AccountManager 账户存在（清除应用数据后账户可能被删除，但日历数据库中的日历可能还在）
            val accountManager = AccountManager.get(context)
            val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
            val existingAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE)
            val accountExists = existingAccounts.isNotEmpty()

            if (!accountExists) {
                // 检查 WRITE_CALENDAR 权限
                val hasWritePermission = android.content.pm.PackageManager.PERMISSION_GRANTED ==
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR)
                if (!hasWritePermission) {
                    android.util.Log.w("CalendarEventRepo", "Cannot create calendar: WRITE_CALENDAR permission not granted")
                    return null
                }
                val added = accountManager.addAccountExplicitly(account, null, null)
                if (added) {
                    android.content.ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1)
                    android.content.ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true)
                    android.content.ContentResolver.setMasterSyncAutomatically(true)
                    android.util.Log.i("CalendarEventRepo", "Re-registered AccountManager account: $account")
                } else {
                    android.util.Log.e("CalendarEventRepo", "addAccountExplicitly failed for account=$account")
                    return null
                }
            }

            // 1. 查找已存在的自定义类型日历
            var existingCalId: Long? = null
            var needsUpdate = false
            
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    existingCalId = cursor.getLong(0)
                    val currentDisplayName = cursor.getString(1) ?: ""
                    needsUpdate = currentDisplayName != appName
                }
            }
            
            if (existingCalId != null && needsUpdate) {
                val values = android.content.ContentValues().apply {
                    put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, appName)
                    put(CalendarContract.Calendars.NAME, appName)
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
                android.util.Log.d("CalendarEventRepo", "Found existing calendar calId=$existingCalId, accountExists=$accountExists")
                return existingCalId
            }

            // 2. 创建新的自定义类型日历
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                put(CalendarContract.Calendars.NAME, appName)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, appName)
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
            android.util.Log.i("CalendarEventRepo", "Created calendar calId=$newCalId, accountType=$ACCOUNT_TYPE")
            newCalId
        } catch (e: Exception) {
            android.util.Log.e("CalendarEventRepo", "getOrCreateLocalCalendar failed", e)
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
            android.util.Log.i("CalendarEventRepo", "Created anniversary calendar calId=$newCalId")
            newCalId
        } catch (e: Exception) {
            android.util.Log.e("CalendarEventRepo", "getOrCreateAnniversaryCalendarId failed", e)
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
}
