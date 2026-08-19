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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import com.schedulecalendar.app.ui.navigation.AppNavHost
import com.schedulecalendar.app.ui.settings.WidgetSettingsViewModel
import com.schedulecalendar.app.ui.theme.ScheduleCalendarTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPreferences: com.schedulecalendar.app.data.prefs.AppPreferences

    companion object {
        const val ACTION_CLOCK_IN = "com.schedulecalendar.app.ACTION_CLOCK_IN"
        const val ACTION_CLOCK_OUT = "com.schedulecalendar.app.ACTION_CLOCK_OUT"
        const val EXTRA_NAVIGATE_DATE = "navigate_date"
    }

    var pendingNavigateDate: String? = null
        private set

    // -- 生命周期 ---------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        CoroutineScope(Dispatchers.IO).launch {
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
                                CoroutineScope(Dispatchers.IO).launch {
                                    appPreferences.setInitialPermissionsDone()
                                }
                            }
                        )
                    }

                    AppNavHost()
                }
            }
        }
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
                "\u6743\u9650\u7533\u8bf7",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "\u4e3a\u4e86\u6b63\u5e38\u8fd0\u884c\uff0c\u5e94\u7528\u9700\u8981\u4ee5\u4e0b\u6743\u9650\uff1a",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                PermissionReasonItem("\ud83d\udcc5", "\u65e5\u5386\u6743\u9650", "\u8bfb\u5199\u7cfb\u7edf\u65e5\u5386\uff0c\u540c\u6b65\u6392\u73ed\u8bb0\u5f55\u548c\u521b\u5efa\u63d0\u9192\u4e8b\u4ef6")
                PermissionReasonItem("\ud83d\udd14", "\u901a\u77e5\u6743\u9650", "\u53d1\u9001\u4e0a\u4e0b\u73ed\u6253\u5361\u63d0\u9192\u548c\u73ed\u6b21\u53d8\u66f4\u901a\u77e5")
                Spacer(Modifier.height(4.dp))
                Text(
                    "\u60a8\u53ef\u4ee5\u7a0d\u540e\u5728\u201c\u8bbe\u7f6e \u2192 \u6743\u9650\u7ba1\u7406\u201d\u4e2d\u8c03\u6574",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("\u53bb\u5f00\u542f")
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text("\u7a0d\u540e\u518d\u8bf4")
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
