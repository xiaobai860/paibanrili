package com.schedulecalendar.app.data.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **兼容栅栏策略锁定测试**。
 *
 * ## 为什么需要它
 * 栅栏一旦抬起就**只能升不能降**：把 `MIN_SUPPORTED_*` 调低，等于让本项目重新背上
 * 历史格式兼容层（旧数据被"尽力解析"→ 历史工时/薪资静默算错）。
 * 这个测试把「栅栏值」和「拒绝规则」钉死，任何人想放开兼容范围都会先红。
 *
 * 覆盖面：① 三条版本轴的最低可接受版本；② 闸门对数据库版本的决策；
 * ③ 备份版本闸门（太老 → 拒绝并指向兼容版；缺版本 → 拒绝；当前格式 → 放行）。
 */
class DataFormatFenceTest {

    // ── 1. 栅栏值 ──────────────────────────────────────────────────────

    @Test
    fun minSupportedDbVersionEqualsFence() {
        assertEquals(DataFormat.COMPAT_FENCE, DataFormat.MIN_SUPPORTED_DB_VERSION)
        assertEquals(7, DataFormat.MIN_SUPPORTED_DB_VERSION)
    }

    @Test
    fun minSupportedBackupVersionsEqualCurrentFormat() {
        assertEquals(DataFormat.APP_DATA_BACKUP, DataFormat.MIN_SUPPORTED_APP_DATA_BACKUP)
        assertEquals(DataFormat.SHIFT_CONFIG_BACKUP, DataFormat.MIN_SUPPORTED_SHIFT_CONFIG_BACKUP)
        assertEquals(5, DataFormat.MIN_SUPPORTED_APP_DATA_BACKUP)
        assertEquals(7, DataFormat.MIN_SUPPORTED_SHIFT_CONFIG_BACKUP)
    }

    @Test
    fun fenceMayOnlyRiseNeverFall() {
        assertTrue(
            "数据库最低支持版本不得低于栅栏 ${DataFormat.COMPAT_FENCE}",
            DataFormat.MIN_SUPPORTED_DB_VERSION >= DataFormat.COMPAT_FENCE
        )
        assertTrue(
            "应用数据备份最低版本不得低于 ${DataFormat.APP_DATA_BACKUP}",
            DataFormat.MIN_SUPPORTED_APP_DATA_BACKUP >= DataFormat.APP_DATA_BACKUP
        )
        assertTrue(
            "班次配置备份最低版本不得低于 ${DataFormat.SHIFT_CONFIG_BACKUP}",
            DataFormat.MIN_SUPPORTED_SHIFT_CONFIG_BACKUP >= DataFormat.SHIFT_CONFIG_BACKUP
        )
    }

    @Test
    fun currentVersionIsNotBelowMinSupported() {
        assertTrue(DataFormat.CURRENT >= DataFormat.MIN_SUPPORTED_DB_VERSION)
    }

    // ── 2. 闸门决策 ────────────────────────────────────────────────────

    @Test
    fun missingDatabaseFileMeansFreshInstall() {
        assertEquals(
            DataFormat.DbVersionDecision.FRESH_INSTALL,
            DataFormat.decideByDbVersion(null)
        )
    }

    @Test
    fun dbVersionBelowFenceIsRejected() {
        for (v in 1..6) {
            assertEquals(
                "v$v 的库必须被拒绝",
                DataFormat.DbVersionDecision.UNSUPPORTED,
                DataFormat.decideByDbVersion(v)
            )
        }
    }

    @Test
    fun dbVersionAtOrAboveFenceIsOpened() {
        assertEquals(
            DataFormat.DbVersionDecision.OPEN,
            DataFormat.decideByDbVersion(DataFormat.MIN_SUPPORTED_DB_VERSION)
        )
        assertEquals(
            DataFormat.DbVersionDecision.OPEN,
            DataFormat.decideByDbVersion(DataFormat.MIN_SUPPORTED_DB_VERSION + 1)
        )
    }

    // ── 3. 备份版本闸门 ────────────────────────────────────────────────

    @Test
    fun legacyAppDataBackupIsRejectedWithCompatVersionHint() {
        for (v in 1 until DataFormat.MIN_SUPPORTED_APP_DATA_BACKUP) {
            val msg = BackupFormat.checkRestoreAllowed(BackupKind.APP_DATA, v)
            assertNotNull("v$v 应用数据备份必须被拒绝", msg)
            assertTrue(
                "拒绝原因应指向兼容版：$msg",
                msg!!.contains(DataFormat.COMPAT_VERSION_LABEL)
            )
        }
    }

    @Test
    fun legacyShiftConfigBackupIsRejected() {
        for (v in 1 until DataFormat.MIN_SUPPORTED_SHIFT_CONFIG_BACKUP) {
            assertNotNull(
                "v$v 班次配置备份必须被拒绝",
                BackupFormat.checkRestoreAllowed(BackupKind.SHIFT_CONFIG, v)
            )
        }
    }

    @Test
    fun currentFormatBackupIsAllowed() {
        assertNull(
            BackupFormat.checkRestoreAllowed(BackupKind.APP_DATA, DataFormat.APP_DATA_BACKUP)
        )
        assertNull(
            BackupFormat.checkRestoreAllowed(BackupKind.SHIFT_CONFIG, DataFormat.SHIFT_CONFIG_BACKUP)
        )
    }

    @Test
    fun backupWithoutVersionFieldIsRejected() {
        assertNotNull(BackupFormat.checkRestoreAllowed(BackupKind.APP_DATA, null))
        assertNotNull(BackupFormat.checkRestoreAllowed(BackupKind.SHIFT_CONFIG, null))
    }
}
