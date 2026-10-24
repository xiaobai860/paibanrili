package com.schedulecalendar.app.data.compat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.schedulecalendar.app.data.db.AppDatabase
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.CalcProfileRepository
import com.schedulecalendar.app.ui.settings.BackupManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 升级入口（兼容版的「中转站」）。
 *
 * 启动流程：
 * 1. 读旧库的 PRAGMA user_version（不需要打开 Room，零副作用）；
 * 2. 版本低于当前 → 先抓一份迁移前快照（只读复制旧库）；
 * 3. 打开 Room → Room 自动执行迁移链（v1→…→v7）；
 * 4. 迁移后做数据校验（行数 / JSON 可解析性 / 口径完整性）；
 * 5. 校验不通过 → 用快照回滚，并把失败原因告诉用户；
 * 6. 校验通过 → 盖上「数据格式版本」戳，后续版本据此判断数据是否还需要再转换。
 */
@Singleton
class CompatGate @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val prefs: AppPreferences,
    private val calcProfileRepo: CalcProfileRepository,
    private val checker: DataIntegrityChecker,
    private val backupManager: BackupManager
) {

    /** 升级状态 */
    sealed class State {
        /** 尚未开始 */
        object Idle : State()

        /** 正在检查版本 */
        object Checking : State()

        /** 正在迁移数据 */
        object Migrating : State()

        /** 升级完成 */
        data class Ready(
            /** 数据校验结论（可能有警告） */
            val report: DataIntegrityChecker.Report?,
            /** 升级前的数据库版本（全新安装为 null） */
            val fromVersion: Int?,
            /** 升级说明 */
            val notes: List<String>
        ) : State()

        /** 升级失败（已回滚或无法回滚） */
        data class Failed(
            /** 失败原因 */
            val message: String,
            /** 是否已回滚到升级前的数据 */
            val rolledBack: Boolean
        ) : State()

        /** 数据版本过旧，本版本已不再支持（只能装回兼容版） */
        data class Unsupported(
            /** 实际版本 */
            val version: Int,
            /** 本版本要求的最低版本 */
            val required: Int
        ) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()

    /** 是否已处理过（保证只跑一次，失败后不重复触发） */
    private var finished = false

    /**
     * 执行升级（幂等）。
     * 必须在**非主线程**调用（会触发 Room 打开与迁移）。
     */
    suspend fun ensureUpgraded() = mutex.withLock {
        if (finished) return@withLock
        _state.value = State.Checking

        val dbFile = context.getDatabasePath(LegacyDbSnapshot.DB_NAME)
        val fileVersion = readUserVersion(dbFile)
        if (fileVersion == null) {
            // 全新安装：没有旧数据要转换
            stampCurrentFormat()
            finished = true
            _state.value = State.Ready(null, null, emptyList())
            return@withLock
        }
        if (fileVersion < DataFormat.MIN_SUPPORTED_DB_VERSION) {
            finished = true
            _state.value = State.Unsupported(fileVersion, DataFormat.MIN_SUPPORTED_DB_VERSION)
            return@withLock
        }

        val notes = mutableListOf<String>()
        var snapshot: LegacyDbSnapshot.Snapshot? = null
        if (fileVersion < DataFormat.CURRENT) {
            _state.value = State.Migrating
            notes += "正在把数据从 v$fileVersion 升级到 v${DataFormat.CURRENT}"
            // 迁移前快照：Room 一旦被打开就会就地迁移，届时旧数据已不可得
            snapshot = LegacyDbSnapshot.capture(context, dbFile)
            if (snapshot == null) {
                notes += "未能生成升级前快照（升级失败时将无法自动回滚）"
            }
        }

        // 触发 Room 打开 → Room 自动执行迁移链
        try {
            db.openHelper.writableDatabase
        } catch (e: Throwable) {
            finished = true
            _state.value = State.Failed(
                "数据库升级失败：${e.message ?: e::class.java.simpleName}",
                rolledBack = false
            )
            return@withLock
        }

        // 口径档案：迁移后必须有一份覆盖全部历史的初始档案，否则历史计算会回退到现值
        runCatching { calcProfileRepo.ensureInitialProfile() }
            .onFailure { notes += "创建初始计算口径失败：${it.message}" }
        runCatching { calcProfileRepo.repairRelay() }

        // 迁移后校验
        val report = checker.check(snapshot?.counts)
        if (!report.ok) {
            // 失败必须留下日志：否则「升级失败」只有用户看得见弹窗，排查时无从下手
            android.util.Log.e("CompatGate", "升级后校验未通过：${report.summary()}")
            val rollbackError = snapshot?.let {
                runCatching { backupManager.restoreFromJson(it.json) }.exceptionOrNull()
            }
            val rolledBack = snapshot != null && rollbackError == null
            if (rollbackError != null) {
                android.util.Log.e("CompatGate", "回滚失败", rollbackError)
            }
            finished = true
            _state.value = State.Failed(
                message = "升级后数据校验未通过：${report.summary()}" +
                    if (rolledBack) "。已回滚到升级前的数据。" else "。请从备份恢复数据。",
                rolledBack = rolledBack
            )
            return@withLock
        }

        stampCurrentFormat()
        finished = true
        // 升级说明与校验警告写入日志：它们不影响使用，但排查数据问题时非常关键
        notes.forEach { android.util.Log.i("CompatGate", it) }
        report.warnings.forEach { android.util.Log.w("CompatGate", it) }
        _state.value = State.Ready(report, fileVersion, notes)
    }

    /** 等待升级结束（成功或失败都返回），供其它启动任务挂起等待 */
    suspend fun awaitReady() {
        ensureUpgraded()
    }

    /** 读取数据库文件版本（PRAGMA user_version）；文件不存在返回 null（全新安装） */
    private fun readUserVersion(dbFile: File): Int? {
        if (!dbFile.exists()) return null
        val direct = runCatching {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }
        }.getOrNull()
        if (direct != null) return direct
        // 读不到（极端情况下 WAL/锁冲突）→ 复制一份再读
        return runCatching { LegacyDbSnapshot.capture(context, dbFile)?.dbVersion }.getOrNull()
    }

    /** 盖上「数据格式版本」戳：数据已经是当前格式了 */
    private suspend fun stampCurrentFormat() {
        runCatching { prefs.setDataFormatVersion(DataFormat.CURRENT) }
    }
}
