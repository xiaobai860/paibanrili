package com.schedulecalendar.app.ui.compat

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulecalendar.app.data.compat.CompatGate
import com.schedulecalendar.app.data.compat.DataFormat
import com.schedulecalendar.app.domain.util.AppLog
import com.schedulecalendar.app.domain.util.LogTags
import com.schedulecalendar.app.ui.settings.BackupFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据升级遮罩。
 *
 * - 检查中：全屏遮罩 + 进度提示（稍候）；
 * - 失败：显示失败原因（数据需从备份恢复），可关闭继续使用；
 * - 版本过旧：阻断式提示 + **可分享的旧数据备份列表**（见 [UnsupportedScreen]）。
 */
@Composable
fun CompatOverlay(
    state: CompatGate.State,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        is CompatGate.State.Idle, is CompatGate.State.Ready -> Unit

        is CompatGate.State.Checking -> CheckingBox()


        is CompatGate.State.Failed -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "数据校验未通过",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onDismissError, modifier = Modifier.padding(top = 4.dp)) {
                        Text("知道了")
                    }
                }
            }
        }

        is CompatGate.State.Unsupported -> UnsupportedScreen(
            version = state.version,
            required = state.required,
            modifier = modifier
        )
    }
}

/**
 * 数据版本过旧时的阻断页：**分步指引 + 可分享的旧数据备份列表**。
 *
 * ## 为什么这一页要能导出旧数据
 * 用户的真实困境是个死锁：
 * - 本版本**不兼容**旧数据库 schema（栅栏直接拦截，绝不打开）；
 * - 旧版本又**装不回来**（versionCode 不能降级安装，除非卸载）；
 * - 而卸载会**连数据一起清掉**。
 * 于是「打不开 → 降不了级 → 卸载就丢」的闭环里，用户没有任何自救手段。
 *
 * ## 破局点
 * **应用数据备份（JSON）与数据库 schema 是两条独立的版本轴**（见 [DataFormat]）：
 * 本版本读不懂旧数据库，但完全读得懂旧版写出来的备份文件 —— 而且**根本不需要读懂**，
 * 只要把私有目录里的备份文件原样分享出去即可。于是流程变成：
 * ① 在本页分享保存备份 → ② 卸载本版本 → ③ 装兼容版 → ④ 在兼容版里导入该备份 →
 * ⑤ 直接升级回本版本（数据已是新格式，**不需要再导出一次**）。
 *
 * 兼容版能导入它自己写出来的备份，所以这一步无需任何格式转换。
 */
@Composable
private fun UnsupportedScreen(
    version: Int,
    required: Int,
    modifier: Modifier = Modifier
) {
    val vm: CompatOverlayViewModel = hiltViewModel()
    val backups by vm.backups.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // ⚠️ 必须自己处理系统栏内边距：本遮罩挂在 Scaffold 之外，拿不到 Scaffold 的
            // contentWindowInsets；而 Activity 是 enableEdgeToEdge（内容延伸到状态栏下方），
            // 不加这层 padding 首行文字会被状态栏压住。
            // 用 safeDrawing 而非 statusBars：页面可滚动到底，底部也要避开导航栏手势条。
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "数据版本过旧，暂时打不开",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "当前数据为 v$version，本版本最低支持 v$required。" +
                "请先把下面的备份分享保存出来 —— 卸载后本机数据就会被清除。",
            style = MaterialTheme.typography.bodyMedium
        )

        HorizontalDivider()

        Text("恢复步骤", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "1. 点下方「分享」，把「应用数据」备份存到微信 / 网盘 / 文件（务必保留）。\n" +
                "2. 卸载本版本（数据会在卸载时清除，所以第 1 步必须先做）。\n" +
                "3. 安装兼容版（${DataFormat.COMPAT_VERSION_LABEL}）。\n" +
                "4. 在兼容版的「设置 → 存储」中导入刚保存的备份文件。\n" +
                "5. 直接升级安装本版本即可 —— 数据已是新格式，不用再导出一次。",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        Text(
            "可分享的应用数据备份",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp))

            backups.isEmpty() -> Text(
                "没有找到备份文件。这种情况只能先装兼容版，在兼容版里导出数据后再升级回来。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            else -> backups.forEach { file ->
                BackupShareRow(file) { shareBackupFile(context, file) }
            }
        }
    }
}

/** 一行备份文件：名称 + 大小/时间 + 分享按钮 */
@Composable
private fun BackupShareRow(file: BackupFile, onShare: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text(
                "${formatSize(file.sizeBytes)} · ${formatBackupTime(file.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("分享")
        }
    }
}

/**
 * 分享一份备份文件（拉起系统分享面板）。
 *
 * - 私有目录里的文件 → 用 FileProvider 转成 content://（`file_paths.xml` 已覆盖 `files/backups/`）；
 * - 自定义目录（SAF）里的文件 → 本身就是 content:// URI，直接用；
 * - 直接分享 file:// 会被系统拒绝（FileUriExposedException），故必须走 FileProvider。
 */
private fun shareBackupFile(context: Context, file: BackupFile) {
    runCatching {
        val shareUri = if (file.path.startsWith("content://")) {
            file.path.toUri()
        } else {
            val f = File(file.path)
            if (!f.exists()) throw IllegalStateException("备份文件已不存在")
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                f
            )
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出旧数据备份"))
    }.onFailure {
        AppLog.e(LogTags.COMPAT_GATE, "分享旧数据备份失败", it)
        Toast.makeText(
            context, "分享失败：${it.message}", Toast.LENGTH_SHORT
        ).show()
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) {
        String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    } else {
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

private fun formatBackupTime(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

/**
 * 「正在检查数据版本」的阻断框。
 *
 * ⚠️ 早期它是个带 `message` 形参的通用阻断框，但改造后只剩本处一个调用点、
 * 且文案固定 —— 形参恒为常量反而是冗余，故直接内联为固定文案。
 */
@Composable
private fun CheckingBox() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(bottom = 4.dp))
                Text("正在检查数据版本…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
