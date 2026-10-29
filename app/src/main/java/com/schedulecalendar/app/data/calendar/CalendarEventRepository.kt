package com.schedulecalendar.app.data.calendar

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.schedulecalendar.app.domain.util.AppLog
import com.schedulecalendar.app.domain.util.LogTags
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.util.Calendar
import java.util.TimeZone
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
    @param:ApplicationContext private val context: Context
) {
    /**
     * 提醒专用日历 ID 缓存。
     *
     * `getOrCreateReminderCalendarId()` 内部有 1 次 Provider query，账户不存在时还会触发
     * `addAccountExplicitly` + insert。而 `findEventsByTitlePrefix`
     * 每次调用都会走一遍，ReminderScheduler 逐条创建提醒时会反复执行。
     * 这里按进程缓存，写入/删除事件后由调用方按需调用 [invalidateReminderCalendarCache]。
     */
    @Volatile
    private var cachedReminderCalendarId: Long? = null

    /** 使提醒日历 ID 缓存失效（删除事件/清理后调用，保证下次重新解析） */
    fun invalidateReminderCalendarCache() {
        cachedReminderCalendarId = null
    }

    /**
     * 提醒日历 ID（读取「事项」时用于排除）。
     *
     * 提醒日历是应用为「系统闹钟提醒」创建的第三个日历，它的事件**不是用户的事项**
     * （只是给系统闹钟用的载体），因此所有事项读取入口都必须排除 ——
     * 即使用户在「日历账户」里启用了该账户也一样。
     *
     * 取不到时返回 null（无写权限 / 账户尚未创建）→ 退化为不过滤，不影响主流程。
     */
    private fun reminderCalendarIdForFilter(): Long? =
        cachedReminderCalendarId ?: getOrCreateReminderCalendarId()

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

        /** 分类标识：日程 */
        const val CATEGORY_SCHEDULE = "schedule"
        /** 分类标识：纪念日 */
        const val CATEGORY_ANNIVERSARY = "anniversary"
        /**
         * 未设置分类时的**默认分类**：日程。
         *
         * 系统日历与第三方应用账户默认没有分类标记，其事件统一按日程处理
         * （显示在「事项 → 日程」）。
         *
         * ⚠️ 读路径与 UI 显示必须用**同一个**兜底值：否则会出现
         * 「界面写未分类、实际按日程走」的自相矛盾（用户看到的与实际行为对不上）。
         */
        const val DEFAULT_CATEGORY = CATEGORY_SCHEDULE
        /** 国产 ROM 闹钟提醒标志（写入 ExtendedProperties 的 VALUE，全项目取值唯一） */
        private const val NEED_ALARM_PROPERTY_VALUE = "{\"need_alarm\":true}"
        /** getAllEvents 结果缓存有效时长（毫秒）：短 TTL 避免首次/频繁进入时反复全量拉取系统日历 */
        private const val EVENTS_CACHE_TTL_MS = 30_000L
    }

    /** getAllEvents 结果缓存（带失效），减少首次进入事项 Tab 时的全量 ContentProvider 查询 */
    @Volatile
    private var allEventsCache: List<CalendarEventInfo>? = null
    @Volatile
    private var allEventsCacheTime: Long = 0L

    /** 日历名映射缓存（TTL）：loadAllCalendarInfo 每次全量查 Calendars 表，高频调用时开销明显 */
    @Volatile
    private var calendarInfoCache: Map<Long, CalendarInfo>? = null
    @Volatile
    private var calendarInfoCacheTime: Long = 0L

    /**
     * 使 getAllEvents 缓存失效（系统日历事件变化时由观察者调用），确保下次查询拿到最新数据
     */
    fun invalidateEventsCache() {
        allEventsCache = null
        allEventsCacheTime = 0L
        calendarInfoCache = null
        calendarInfoCacheTime = 0L
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
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        // 只返回「可写且可见」的日历：只读/隐藏日历出现在选择列表里，选中后写入必然失败
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? " +
            "AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
    
        // Provider 在权限被单独撤销 / ROM 定制异常时会抛 SQLiteException、IllegalStateException，
        // 与同类方法保持一致加 try 保护，避免异常直接抛给调用方
        val cursor = try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection, selectionArgs, null
            )
        } catch (e: Exception) {
            AppLog.w(LogTags.CALENDAR_EVENT_REPOSITORY, "getAllAccounts query failed", e)
            null
        }
        cursor?.use {
            // 所有日历都按单个日历拆分为独立条目
            while (it.moveToNext()) {
                val calId = it.getLong(0)
                val rawDisplayName = it.getString(1)
                val rawAccountName = it.getString(2)
                val accountType = it.getString(3) ?: ""

                val calDisplayName = rawDisplayName?.trim()?.takeIf { s -> s.isNotBlank() } ?: ""
                val accountName = rawAccountName?.trim()?.takeIf { s -> s.isNotBlank() } ?: ""
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
        // ⚠️ 用 endsWith 而非 contains：contains 会把「我的日程备份」这类名字误判成日程日历
        accounts.sortWith(compareBy {
            when {
                it.accountType != ACCOUNT_TYPE -> "ZZZ" // 外部账户排在最后
                it.displayName.endsWith(SCHEDULE_SUFFIX) -> "A"      // 日程
                it.displayName.endsWith(ANNIVERSARY_SUFFIX) -> "B"  // 纪念日
                it.displayName.endsWith(REMINDER_SUFFIX) -> "C"     // 提醒
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
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 获取所有可见日历事件（不限时间范围）
     * 优化：批量加载日历信息，避免 N+1 查询
     *
     * ⚠️ 事项列表/纪念日列表必须走本方法（不能改用 [getAllEventsInRange]）：
     * 系统日历的重复事件在 Events 表里只有一条主记录，DTSTART = 首次发生时间；
     * 若按时间区间过滤，早年创建的年度重复纪念日会整体消失（用户表现为「纪念日不见了」）。
     * 缓存 30s + ContentObserver 失效已足够，避免频繁全量拉取。
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
        // ⚠️ 提醒日历的事件**不是用户的事项**（它是给系统闹钟用的载体）→ 事项入口一律排除，
        // 即使用户在「日历账户」里启用了该账户也一样。
        val reminderCalId = reminderCalendarIdForFilter()

        // 系统日历查询：缺权限/日历账户异常时不应崩溃（调用方也有兜底，函数内自保护）
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                null, null,
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(0)
                    val calendarId = cursor.getLong(1)
                    if (calendarId == reminderCalId) continue // 提醒日历 → 不是事项
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
        } catch (e: Exception) {
            AppLog.w(LogTags.CALENDAR_EVENT_REPOSITORY, "查询系统日历事件失败", e)
            return emptyList()
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
        val nowMs = System.currentTimeMillis()
        calendarInfoCache?.let { if (nowMs - calendarInfoCacheTime < EVENTS_CACHE_TTL_MS) return it }
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
        } catch (e: Exception) {
            // 静默返回空 Map 会让所有事件的日历名变空且无从排查，这里记日志
            AppLog.w(LogTags.CALENDAR_EVENT_REPOSITORY, "loadAllCalendarInfo failed", e)
        }
        calendarInfoCache = map
        calendarInfoCacheTime = nowMs
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
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, dtStart)
                put(CalendarContract.Events.DTEND, dtEnd)
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
                if (rrule != null) {
                    put(CalendarContract.Events.RRULE, rrule)
                }
                if (colorHex != null) {
                    try {
                        val colorInt = colorHex.toColorInt()
                        put(CalendarContract.Events.EVENT_COLOR, colorInt)
                    } catch (e: Exception) {
                        // 颜色解析失败不阻断事件创建，但需留痕，否则用户设的颜色被静默丢弃
                        AppLog.w(LogTags.CALENDAR_EVENT_REPOSITORY, "bad event color: $colorHex", e)
                    }
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
                val reminderId = addReminder(eventId, reminderMinutes)
                // 国产 ROM 兼容：通过 ExtendedProperties 设置 need_alarm 标志
                // 国产 ROM（小米/华为/OPPO/vivo）不使用标准 hasAlarm 字段，
                // 而是通过此扩展属性判断是否触发闹钟提醒
                val propId = addExtendedProperty(eventId)
                // 提醒写入失败必须回滚：否则留下「HAS_ALARM=1 但无 reminder 行」的僵尸事件，
                // 用户以为设了提醒实际不响，且部分日历 App 会显示异常。
                if (reminderId == null) {
                    runCatching { deleteEvent(eventId) }
                    return -1L
                }
                if (propId == null) {
                    AppLog.w(LogTags.CALENDAR_EVENT_REPOSITORY, "extended property write failed for $eventId")
                }
            }

            eventId
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * 为事件添加提醒
     */
    private fun addReminder(eventId: Long, minutesBefore: Int): Long? {
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutesBefore)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            val uri = context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
            // 成功返回提醒行 ID；失败（uri 为 null）返回 null，由调用方决定回滚
            uri?.let { ContentUris.parseId(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 为事件添加扩展属性（国产 ROM 兼容）
     * 国产 ROM（小米/华为/OPPO/vivo）通过 ExtendedProperties 中的
     * {"need_alarm":true} 判断是否触发闹钟提醒
     */
    private fun addExtendedProperty(eventId: Long): Long? {
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.ExtendedProperties.EVENT_ID, eventId)
                put(CalendarContract.ExtendedProperties.NAME, "customAppData")
                put(CalendarContract.ExtendedProperties.VALUE, NEED_ALARM_PROPERTY_VALUE)
            }
            val uri = context.contentResolver.insert(
                CalendarContract.ExtendedProperties.CONTENT_URI, values
            )
            uri?.let { ContentUris.parseId(it) }
        } catch (e: Exception) {
            // 部分国产 ROM 可能不支持 ExtendedProperties 写入 → 不阻断事件创建（提醒行已写好）。
            // 但必须记下**原因**：need_alarm 可能正是 ROM 用来判断「响铃提醒」开关的那个字段，
            // 只记 eventId 看不出是权限不足、字段被拒还是 provider 抛异常。
            AppLog.w(LogTags.CALENDAR_EVENT_REPOSITORY, "addExtendedProperty failed for $eventId", e)
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
            // 「查询失败」与「没有日历」在返回值上无法区分 → 必须留日志，
            // 否则上层会误判成"还没建日历"，进而反复重建 / 静默不显示日程
            AppLog.w(LogTags.CALENDAR_EVENT_REPOSITORY, "查询现有日历失败", e)
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
        } catch (e: Exception) {
            // 回退到内置账户名是安全的（仅影响日历显示名），但仍留一条日志便于追因
            AppLog.w(LogTags.CALENDAR_EVENT_REPOSITORY, "读取应用名失败，回退到内置账户名", e)
            ACCOUNT_NAME
        }
        val scheduleDisplayName = "$appName$SCHEDULE_SUFFIX"
        return try {
            // 0. 权限前置校验：无写权限时任何账户/日历操作都会失败，直接返回
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            // 0. 先确保 AccountManager 账户存在
            val accountManager = AccountManager.get(context)
            val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
            val existingAccounts = accountManager.getAccountsByType(ACCOUNT_TYPE)
            val accountExists = existingAccounts.isNotEmpty()
            // 记录本次是否新建了账户，便于后续失败时回滚清理，避免系统账户列表残留空账户
            var accountAddedNow = false

            if (!accountExists) {
                val added = accountManager.addAccountExplicitly(account, null, null)
                if (added) {
                    accountAddedNow = true
                    ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1)
                    ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true)
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
                // ⚠️ 必须同时排除「纪念日」与「提醒」两个后缀：
                // 只排除纪念日时，提醒日历可能被误当成日程日历取到 → 下面的 needsUpdate
                // 会把它的显示名改写成「排班日历-日程」，造成重名并毁掉提醒日历。
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} NOT LIKE ? AND ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} NOT LIKE ?",
                arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE, "%$ANNIVERSARY_SUFFIX", "%$REMINDER_SUFFIX"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    existingCalId = cursor.getLong(0)
                    val currentDisplayName = cursor.getString(1) ?: ""
                    needsUpdate = currentDisplayName != scheduleDisplayName
                }
            }
            
            if (existingCalId != null && needsUpdate) {
                val values = ContentValues().apply {
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
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                put(CalendarContract.Calendars.NAME, scheduleDisplayName)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, scheduleDisplayName)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF4285F4.toInt())
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
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
            // 日历创建失败：回滚本次新建的账户，避免系统账户列表残留空账户
            if (newCalId == null && accountAddedNow) {
                runCatching { accountManager.removeAccountExplicitly(account) }
            }
            newCalId
        } catch (_: Exception) {
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
        // 进程内缓存命中直接返回，省去一次 Provider query（高频调用路径）
        cachedReminderCalendarId?.let { return it }
        val appName = try {
            context.getString(context.applicationInfo.labelRes)
        } catch (e: Exception) {
            // 回退到内置账户名是安全的（仅影响日历显示名），但仍留一条日志便于追因
            AppLog.w(LogTags.CALENDAR_EVENT_REPOSITORY, "读取应用名失败，回退到内置账户名", e)
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
            if (existingCalId != null) {
                cachedReminderCalendarId = existingCalId
                return existingCalId
            }

            // 确保账户存在
            getOrCreateLocalCalendarId() ?: return null

            // 创建提醒日历
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                put(CalendarContract.Calendars.NAME, displayName)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF059669.toInt()) // 绿色
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
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
            if (newCalId != null) cachedReminderCalendarId = newCalId
            newCalId
        } catch (_: Exception) {
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
        } catch (e: Exception) {
            // 回退到内置账户名是安全的（仅影响日历显示名），但仍留一条日志便于追因
            AppLog.w(LogTags.CALENDAR_EVENT_REPOSITORY, "读取应用名失败，回退到内置账户名", e)
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
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                put(CalendarContract.Calendars.NAME, displayName)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFFE53935.toInt()) // 红色
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
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
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 更新日历事件
     *
     * ⚠️ 必须同步 EVENT_TIMEZONE：全天事件的 dtStart/dtEnd 以 **UTC 毫秒** 存储，
     * 若保留旧的 "UTC" 而调用方用本地毫秒计算，UTC+8 用户保存后事件会整体偏移一天。
     * 因此这里按 [CalendarEventInfo.allDay] 显式写入正确的时区，与 [createEvent] 口径一致。
     */
    fun updateEvent(event: CalendarEventInfo): Boolean {
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, event.title)
                put(CalendarContract.Events.DESCRIPTION, event.description)
                put(CalendarContract.Events.DTSTART, event.dtStart)
                put(CalendarContract.Events.DTEND, event.dtEnd)
                put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_LOCATION, event.eventLocation)
                put(CalendarContract.Events.RRULE, event.rrule)
                // 与 createEvent 保持一致：全天事件用 UTC，定时事件用系统默认时区
                put(
                    CalendarContract.Events.EVENT_TIMEZONE,
                    if (event.allDay) "UTC" else TimeZone.getDefault().id
                )
            }
            val rows = context.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id),
                values, null, null
            )
            // 更新 DTSTART/DTEND/RRULE 后，旧 reminder 行仍指向原时间，需删除让用户按需重建；
            // 否则会出现「事件时间已改、提醒还在旧时刻」的错位。
            if (rows > 0) {
                runCatching { deleteRemindersForEvent(event.id) }
            }
            rows > 0
        } catch (_: Exception) {
            false
        }
    }

    /** 删除某事件的所有提醒行（Reminders 表） */
    fun deleteRemindersForEvent(eventId: Long): Int {
        return try {
            context.contentResolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString())
            )
        } catch (_: Exception) {
            0
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
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 查找应用本地日历中符合标题前缀的事件
     * 用于上下班提醒日历事件的管理
     * @param titlePrefix 标题前缀，如 `[上下班提醒]`
     * @return 事件ID列表
     */
    fun findEventsByTitlePrefix(titlePrefix: String): List<Long> {
        val eventIds = mutableListOf<Long>()
        val calId = getOrCreateReminderCalendarId() ?: return eventIds
        // 注意：本函数仅查询，不修改日历结构，无需失效缓存
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
        } catch (_: Exception) {
            eventIds
        }
    }

    /**
     * 批量删除事件（单次 IPC，避免逐条 delete 的 N 次跨进程调用）
     * @return 删除的事件数
     */
    fun deleteEvents(eventIds: List<Long>): Int {
        if (eventIds.isEmpty()) return 0
        return try {
            val placeholders = eventIds.joinToString(",") { "?" }
            val rows = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID} IN ($placeholders)",
                eventIds.map { it.toString() }.toTypedArray()
            )
            // 顺带清理这些事件的提醒行，避免 Reminders 表残留孤儿数据
            eventIds.forEach { runCatching { deleteRemindersForEvent(it) } }
            rows
        } catch (_: Exception) {
            // 批量失败时退化为逐条删除
            var deleted = 0
            for (id in eventIds) {
                if (deleteEvent(id)) deleted++
            }
            deleted
        }
    }

    /**
     * 禁用日历账户（关闭同步）
     */
    fun setAccountSync(calendarId: Long, sync: Boolean): Boolean {
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.SYNC_EVENTS, if (sync) 1 else 0)
            }
            val rows = context.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
                values, null, null
            )
            rows > 0
        } catch (_: Exception) {
            false
        }
    }

    private data class CalendarInfo(
        val displayName: String,
        val accountName: String
    )

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
        // 并排除提醒日历（在 Provider 端过滤，省得把提醒事件拉进 JVM 再丢）
        val reminderCalId = reminderCalendarIdForFilter()
        val selection = buildString {
            append("(${CalendarContract.Events.DTEND} > ?) AND (${CalendarContract.Events.DTSTART} < ?)")
            if (reminderCalId != null) {
                append(" AND ${CalendarContract.Events.CALENDAR_ID} != ?")
            }
        }
        val selectionArgs = if (reminderCalId != null) {
            arrayOf(rangeStartMs.toString(), rangeEndMs.toString(), reminderCalId.toString())
        } else {
            arrayOf(rangeStartMs.toString(), rangeEndMs.toString())
        }

        val calendarInfoMap = loadAllCalendarInfo()
        // Provider 异常（权限撤销 / ROM 定制）时与同类方法一致加 try 保护
        val cursor = try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection, selection, selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )
        } catch (e: Exception) {
            AppLog.w(LogTags.CALENDAR_EVENT_REPOSITORY, "getAllEventsInRange query failed", e)
            null
        }
        cursor?.use {
            while (it.moveToNext()) {
                val eventId = it.getLong(0)
                val calendarId = it.getLong(1)
                val calInfo = calendarInfoMap[calendarId]
                events.add(
                    CalendarEventInfo(
                        id = eventId, calendarId = calendarId,
                        title = it.getString(2) ?: "无标题",
                        description = it.getString(3),
                        dtStart = it.getLong(4),
                        dtEnd = it.getLong(5),
                        allDay = it.getInt(6) == 1,
                        eventLocation = it.getString(7),
                        accountName = calInfo?.accountName ?: "",
                        calendarDisplayName = calInfo?.displayName ?: "",
                        rrule = it.getString(8)
                    )
                )
            }
        }
        return events
    }

    /** 是否为年度重复事件（RRULE 含 FREQ=YEARLY） */
    fun isYearlyRecurring(event: CalendarEventInfo): Boolean =
        event.rrule?.contains("FREQ=YEARLY", ignoreCase = true) == true

    /**
     * 计算年度重复事件在指定年份的发生日；非年度重复或日期非法返回 null。
     * 用于 RRULE 展开（CalendarProvider 自身不展开 RRULE）。
     */
    fun yearlyOccurrenceInYear(
        event: CalendarEventInfo,
        year: Int
    ): LocalDate? {
        if (!isYearlyRecurring(event)) return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = event.dtStart
        return runCatching {
            LocalDate.of(year, cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        }.getOrNull()
    }

    /**
     * 获取指定日期的所有事件（含年度重复纪念日）
     * 实现：基于 [getAllEventsInRange] 在 ContentProvider 端按区间过滤，
     * 仅把当天相关事件拉入 JVM，避免每次点选日期都全量扫描所有日历事件。
     *
     * 年度重复（RRULE=FREQ=YEARLY）事件：CalendarProvider **不会展开 RRULE**，
     * 若事件的 DTSTART 落在往年，区间过滤会直接把它排除。因此这里对 YEARLY 事件
     * 额外按「查询年份」重算一次发生日，命中则补入结果。
     * @param dateStr 日期字符串，格式 "yyyy-MM-dd"
     * @return 当天所有的事件列表（纪念日 + 日程）
     */
    fun getEventsForDate(dateStr: String): List<CalendarEventInfo> {
        val parts = dateStr.split("-")
        if (parts.size != 3) return emptyList()
        val year = parts[0].toIntOrNull() ?: return emptyList()
        val month = parts[1].toIntOrNull() ?: return emptyList()
        val day = parts[2].toIntOrNull() ?: return emptyList()

        val selCal = Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val rangeStart = selCal.timeInMillis
        val rangeEnd = rangeStart + 86400000L

        val events = getAllEventsInRange(rangeStart, rangeEnd)
        val result = mutableListOf<CalendarEventInfo>()
        for (event in events) {
            if (event.allDay) {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = event.dtStart
                val eventMonth = cal.get(Calendar.MONTH) + 1
                val eventDay = cal.get(Calendar.DAY_OF_MONTH)
                if (eventMonth == month && eventDay == day) {
                    result.add(event)
                    continue
                }
                // 年度重复：按查询年重算发生日，命中（如 2024 年建的纪念日在 2025 年同日）则补入
                if (isYearlyRecurring(event) &&
                    yearlyOccurrenceInYear(event, year)?.let { it.monthValue == month && it.dayOfMonth == day } == true
                ) {
                    result.add(event)
                }
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
