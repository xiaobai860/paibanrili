package com.schedulecalendar.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.ui.component.ScheduleTopBar

/**
 * 日历账户管理页面
 * 展示设备上所有已同步的日历账户，支持启用/禁用操作
 */
@Composable
fun CalendarAccountSettingsScreen(
    navController: NavController,
    vm: CalendarAccountViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 权限检查状态
    var hasCalendarPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCalendarPermission = granted
        if (granted) vm.loadAccounts()
    }

    // 首次进入时检查权限
    LaunchedEffect(Unit) {
        if (!hasCalendarPermission) {
            permLauncher.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    Scaffold(
        topBar = {
            ScheduleTopBar(
                title = "日程账户",
                onBack = { navController.popBackStack() }
            )
        }
    ) { padding ->
        if (!hasCalendarPermission) {
            // 未授权提示
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "需要日历读取权限才能管理日程账户",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permLauncher.launch(Manifest.permission.READ_CALENDAR) }) {
                        Text("授予权限")
                    }
                }
            }
        } else if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (state.accounts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "未找到系统日历账户",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "系统日历账户",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        "禁用某个账户后，该账户的所有日程将不会在应用中显示。分类后该账户的事件只在对应页面显示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(state.accounts, key = { it.id }) { account ->
                    val accountKey = vm.getAccountKey(account)
                    val category = state.accountCategories[accountKey]
                    AccountCard(
                        account = account,
                        isDisabled = account.calendarIds.any { it in state.disabledAccountIds },
                        category = category,
                        onToggle = { vm.toggleAccount(account) },
                        onCategoryChange = { newCategory ->
                            vm.setAccountCategory(accountKey, newCategory)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 单个日历账户卡片
 */
@Composable
private fun AccountCard(
    account: com.schedulecalendar.app.data.calendar.CalendarAccountInfo,
    isDisabled: Boolean,
    category: String?,
    onToggle: () -> Unit,
    onCategoryChange: (String?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDisabled)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDisabled) 0.dp else 1.dp
        ),
        border = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val acctDisplayName = account.displayName.ifBlank {
                        account.accountName.ifBlank { "未知日历" }
                    }
                    Text(
                        acctDisplayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(2.dp))
                    if (account.accountName.isNotEmpty() && account.accountName != acctDisplayName) {
                        Text(
                            account.accountName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = !isDisabled,
                    onCheckedChange = { onToggle() }
                )
            }

            // 分类选择（仅在账户启用时显示）
            if (!isDisabled) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "分类：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilterChip(
                        selected = category != "anniversary",
                        onClick = { onCategoryChange("schedule") },
                        label = { Text("日程", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = category == "anniversary",
                        onClick = { onCategoryChange("anniversary") },
                        label = { Text("纪念日", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }
}
