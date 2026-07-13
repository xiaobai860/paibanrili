package com.schedulecalendar.app.data.calendar

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
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
    val isEnabled: Boolean = true
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
    val calendarDisplayName: String
)

/**
 * 系统日历数据仓库
 * 提供对 Android Calendar Provider 的读写操作
 */
@Singleton
class CalendarEventRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
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
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.SYNC_EVENTS
        )

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null,
            null
        )?.use { cursor ->
            // 按账户分组统计
            val accountMap = mutableMapOf<String, MutableList<CalendarAccountInfo>>()

            while (cursor.moveToNext()) {
                val calId = cursor.getLong(0)
                val displayName = cursor.getString(1) ?: "未知日历"
                val accountName = cursor.getString(2) ?: ""
                val accountType = cursor.getString(3) ?: ""
                val syncEvents = cursor.getInt(5) == 1

                val key = "$accountName|$accountType"
                val existing = accountMap[key]
                if (existing != null) {
                    // 同账户的另一个日历，合并计数
                    val first = existing[0]
                    existing[0] = first.copy(calendarCount = first.calendarCount + 1)
                } else {
                    accountMap[key] = mutableListOf(
                        CalendarAccountInfo(
                            id = calId,
                            name = displayName,
                            displayName = displayName,
                            accountName = accountName,
                            accountType = accountType,
                            calendarCount = 1,
                            isEnabled = syncEvents
                        )
                    )
                }
            }

            accountMap.values.flatten().let { accounts.addAll(it) }
        }

        return accounts
    }

    /**
     * 获取所有可见日历事件（不限时间范围）
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
            CalendarContract.Events.EVENT_LOCATION
        )

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
                val calInfo = getCalendarInfo(calendarId)
                events.add(
                    CalendarEventInfo(
                        id = eventId, calendarId = calendarId, title = title,
                        description = description, dtStart = dtStart, dtEnd = dtEnd,
                        allDay = allDay, eventLocation = location,
                        accountName = calInfo?.accountName ?: "",
                        calendarDisplayName = calInfo?.displayName ?: ""
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
            CalendarContract.Events.EVENT_LOCATION
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

                // 获取日历显示名和账户名
                val calInfo = getCalendarInfo(calendarId)

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
                        calendarDisplayName = calInfo?.displayName ?: ""
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
                return CalendarInfo(
                    displayName = cursor.getString(0) ?: "",
                    accountName = cursor.getString(1) ?: ""
                )
            }
        }
        return null
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
     * @return 创建成功返回事件ID，失败返回-1
     */
    fun createEvent(
        title: String,
        description: String?,
        dtStart: Long,
        dtEnd: Long,
        allDay: Boolean = false,
        location: String? = null,
        calendarId: Long? = null
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
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ContentUris.parseId(uri ?: return -1L)
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * 获取第一个可写日历的ID
     */
    private fun getFirstWritableCalendarId(): Long? {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
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
