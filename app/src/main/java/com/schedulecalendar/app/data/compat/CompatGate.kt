package com.schedulecalendar.app.data.compat
import com.schedulecalendar.app.domain.util.AppLog
import com.schedulecalendar.app.domain.util.LogTags

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.schedulecalendar.app.data.db.AppDatabase
import com.schedulecalendar.app.data.repository.CalcProfileRepository
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
 * 启动期数据闸门：**只接受栅栏之后的数据**。
 *
 * ## 启动流程
 * 1. 读数据库文件的 PRAGMA user_version（只读打开，不触发 Room，零副作用）；
 * 2. 版本低于 [DataFormat.MIN_SUPPORTED_DB_VERSION] → [State.Unsupported]，
 *    **阻断界面**并提示装回兼容版（这是「明确拒绝」，而不是「打开了但算错」）；
 * 3. 版本正常 → 打开 Room（当前只有单一 schema，无需任何迁移）；
 * 4. 口径档案兜底 + 接力自愈；
 * 5. 数据校验（引用列可解析 / 口径档案存在且不重叠）→ [State.Ready] 或 [State.Failed]。
 *
 * ## 为什么不再有「快照 + 回滚」
 * 栅栏已抬起：不再执行历史迁移（v1→v7），因此也不存在「迁移把数据改坏」这一风险，
 * 没有需要回滚的中间态。恢复备份仍有独立的「恢复前自动快照 → 校验失败回滚」机制
 * （见 `BackupManager`），那条路径与本类无关。
 */
@Singleton
class CompatGate @Inject constructor(
@param:ApplicationContext private val context: Context,
private val db: AppDatabase,
private val calcProfileRepo: CalcProfileRepository,
private val checker: DataIntegrityChecker
) {

    /** 闸门状态 */
    sealed class State {
        /** 尚未开始 */
        object Idle : State()

        /** 正在检查版本 */
        object Checking : State()

        /** 数据版本过旧，本版本已不再支持（只能装回兼容版） */
        data class Unsupported(
            /** 实际版本 */
            val version: Int,
            /** 本版本要求的最低版本 */
            val required: Int
        ) : State()

        /** 就绪（数据可用） */
        data class Ready(
            /** 数据校验结论（可能有警告） */
            val report: DataIntegrityChecker.Report?
        ) : State()

        /** 校验失败（数据不可用，需从备份恢复） */
        data class Failed(
            /** 失败原因 */
            val message: String
        ) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()

    /** 是否已处理过（保证只跑一次，失败后不重复触发） */
    private var finished = false

    /**
     * 执行数据闸门（幂等）。
     * 必须在**非主线程**调用（会触发 Room 打开）。
     */
    suspend fun ensureUpgraded() = mutex.withLock {
        if (finished) return@withLock
        _state.value = State.Checking

        val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
        val fileVersion = readUserVersion(dbFile)
        when (DataFormat.decideByDbVersion(fileVersion)) {
            DataFormat.DbVersionDecision.FRESH_INSTALL -> {
                // 全新安装：没有旧数据要处理
                finished = true
                _state.value = State.Ready(null)
                return@withLock
            }
            DataFormat.DbVersionDecision.UNSUPPORTED -> {
                // 低于栅栏：明确拒绝，绝不尝试打开（Room 会因缺迁移而抛异常）
                AppLog.e(
                    LogTags.COMPAT_GATE,
                    "数据版本 v$fileVersion 低于最低支持版本 v${DataFormat.MIN_SUPPORTED_DB_VERSION}，拒绝打开"
                )
                finished = true
                _state.value = State.Unsupported(fileVersion!!, DataFormat.MIN_SUPPORTED_DB_VERSION)
                return@withLock
            }
            DataFormat.DbVersionDecision.OPEN -> Unit
        }

        // 打开 Room（当前只有单一 schema，无需迁移）
        try {
            db.openHelper.writableDatabase
        } catch (e: Throwable) {
            finished = true
            // 闸门失败会阻断整个 App 启动，必须留日志：否则现场只有「打不开」，
            // 看不到真正的异常类型与栈（这正是需要排查的场景）
            AppLog.e(LogTags.COMPAT_GATE, "数据库打开失败", e)
            _state.value = State.Failed(
                "数据库打开失败：${e.message ?: e::class.java.simpleName}"
            )
            return@withLock
        }

        // 口径档案：必须有一份覆盖全部历史的初始档案，否则历史计算会回退到现值
        runCatching { calcProfileRepo.ensureInitialProfile() }
            .onFailure { AppLog.w(LogTags.COMPAT_GATE, "创建初始计算口径失败", it) }
        runCatching { calcProfileRepo.repairRelay() }
            .onFailure { AppLog.w(LogTags.COMPAT_GATE, "口径接力自愈失败", it) }

        // 数据校验
        val report = checker.check()
        if (!report.ok) {
            // 失败必须留下日志：否则「校验失败」只有用户看得见弹窗，排查时无从下手
            AppLog.e(LogTags.COMPAT_GATE, "数据校验未通过：${report.summary()}")
            finished = true
            _state.value = State.Failed("数据校验未通过：${report.summary()}。请从备份恢复数据。")
            return@withLock
        }

        finished = true
        // 校验警告写入日志：它们不影响使用，但排查数据问题时非常关键
        report.warnings.forEach { AppLog.w(LogTags.COMPAT_GATE, it) }
        _state.value = State.Ready(report)
    }

    /**
     * 等待闸门结束。
     *
     * @return true = 数据可用（[State.Ready]）；false = **数据不可用**
     *         （[State.Unsupported] 数据太老 / [State.Failed] 校验未过）。
     *         调用方据此**跳过一切读库操作** —— 库太老时 Room 会因缺迁移而抛异常，
     *         此时既不能读库，也不能排提醒/刷组件。
     */
    suspend fun awaitReady(): Boolean {
        ensureUpgraded()
        return _state.value is State.Ready
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
        return direct
    }
}
