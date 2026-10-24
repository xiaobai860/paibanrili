// app/src/main/java/com/schedulecalendar/app/MainActivity.kt
package com.schedulecalendar.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import com.schedulecalendar.app.data.compat.CompatGate
import com.schedulecalendar.app.ui.compat.CompatOverlay
import com.schedulecalendar.app.ui.navigation.AppNavHost
import com.schedulecalendar.app.ui.theme.ScheduleCalendarTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPreferences: com.schedulecalendar.app.data.prefs.AppPreferences

    /** 数据兼容闸门（升级中/失败时用遮罩挡住界面，避免用户看到半转换的数据） */
    @Inject lateinit var compatGate: CompatGate

    companion object {
        const val ACTION_CLOCK_IN = "com.schedulecalendar.app.ACTION_CLOCK_IN"
        const val ACTION_CLOCK_OUT = "com.schedulecalendar.app.ACTION_CLOCK_OUT"
        const val EXTRA_NAVIGATE_DATE = "navigate_date"
    }

    /**
     * 小组件点击日期跳转的目标日期（yyyy-MM-dd）。
     * 用 Compose 状态承载：Activity 已存活时 onNewIntent 写入也能触发 CalendarScreen 重组消费，
     * 否则仅冷启动（onCreate 首次组合）生效。
     */
    var pendingNavigateDate by mutableStateOf<String?>(null)
        private set

    // -- 生命周期 ---------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 配置变更（旋转/重建）后先恢复尚未消费的导航日期，避免小组件跳转目标丢失
        savedInstanceState?.getString(EXTRA_NAVIGATE_DATE)?.let { date ->
            pendingNavigateDate = date
        }
        // 从小组件点击启动时首次进入走 onCreate，需在此读取导航日期
        intent?.getStringExtra(EXTRA_NAVIGATE_DATE)?.let { date ->
            pendingNavigateDate = date
            intent.removeExtra(EXTRA_NAVIGATE_DATE)
        }
        enableEdgeToEdge()

        setContent {
            ScheduleCalendarTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showPermissionDialog by remember { mutableStateOf(false) }
                    var permissionsRequested by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        if (!appPreferences.isInitialPermissionsDone() && !permissionsRequested) {
                            showPermissionDialog = true
                        }
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            appPreferences.setInitialPermissionsDone()
                        }
                        permissionsRequested = true
                        showPermissionDialog = false
                    }

                    if (showPermissionDialog) {
                        InitialPermissionDialog(
                            onConfirm = {
                                showPermissionDialog = false
                                permissionsRequested = true
                                val permissions = buildList {
                                    add(Manifest.permission.READ_CALENDAR)
                                    add(Manifest.permission.WRITE_CALENDAR)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }.toTypedArray()
                                permissionLauncher.launch(permissions)
                            },
                            onSkip = {
                                showPermissionDialog = false
                                permissionsRequested = true
                                lifecycleScope.launch(Dispatchers.IO) {
                                    appPreferences.setInitialPermissionsDone()
                                }
                            }
                        )
                    }

                    AppNavHost()

                    // 数据升级遮罩：升级中/失败时挡住界面，避免用户看到「半转换」的中间态
                    val compatState by compatGate.state.collectAsStateWithLifecycle()
                    var compatErrorDismissed by remember { mutableStateOf(false) }
                    LaunchedEffect(compatState) { compatErrorDismissed = false }
                    val showCompatOverlay = when (compatState) {
                        is CompatGate.State.Idle, is CompatGate.State.Ready -> false
                        is CompatGate.State.Failed -> !compatErrorDismissed
                        else -> true
                    }
                    if (showCompatOverlay) {
                        CompatOverlay(
                            state = compatState,
                            onDismissError = { compatErrorDismissed = true }
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingNavigateDate?.let { outState.putString(EXTRA_NAVIGATE_DATE, it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_NAVIGATE_DATE)?.let { date ->
            pendingNavigateDate = date
            intent.removeExtra(EXTRA_NAVIGATE_DATE)
        }
    }

    fun consumeNavigateDate(): String? {
        val date = pendingNavigateDate
        pendingNavigateDate = null
        return date
    }
}

@Composable
private fun InitialPermissionDialog(
    onConfirm: () -> Unit,
    onSkip: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onSkip,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                "权限申请",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "为了正常运行，应用需要以下权限：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                PermissionReasonItem("\ud83d\udcc5", "日历权限", "读写系统日历，同步排班记录和创建提醒事件")
                PermissionReasonItem("\ud83d\udd14", "通知权限", "发送上下班打卡提醒和班次变更通知")
                Spacer(Modifier.height(4.dp))
                Text(
                    "您可以稍后在“设置 → 权限管理”中调整",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("去开启")
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text("稍后再说")
            }
        }
    )
}

@Composable
private fun PermissionReasonItem(emoji: String, title: String, desc: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
