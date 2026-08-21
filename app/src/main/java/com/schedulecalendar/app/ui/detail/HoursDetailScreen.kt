package com.schedulecalendar.app.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import com.schedulecalendar.app.ui.detail.HoursDetailType

@Composable
fun HoursDetailScreen(navController: NavController, vm: HoursDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ScheduleTopBar(
                // REMARK 类型 TopBar 标题改为"本月备注"，其他类型保持 type.label
                title  = "${state.year}年${state.month}月 \u00b7 ${
                    if (state.type == HoursDetailType.REMARK) "本月备注" else state.type.label
                }",
                onBack = { navController.popBackStack() }
            )
        }
    ) { padding ->
        if (state.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (state.items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("\u2713", fontSize = 48.sp, color = Color(0xFF059669))
                    Spacer(Modifier.height(8.dp))
                    Text("本月无记录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp)
                }
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("本月共 ", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("${state.totalCount}", fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        Text(" 条记录", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        // 当月时附加显示"截至到今天 Y 条"
                        if (state.isCurrentMonth) {
                            Text("，截至到今天 ", fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("${state.elapsedCount}", fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                            Text(" 条", fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }

            items(state.items, key = { it.date + it.type }) { item ->
                HoursDetailItemCard(
                    item = item,
                    allowMultiline = state.type == HoursDetailType.REMARK
                )
            }
        }
    }
}

private val HoursDetailItem.type: String get() = primaryText.take(4)

@Composable
private fun HoursDetailItemCard(
    item: HoursDetailItem,
    allowMultiline: Boolean = false
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (item.isAlert) Color(0xFFFFF7ED) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (item.isAlert) Color(0xFFFED7AA) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 日期（月-日）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(36.dp)
            ) {
                Text(item.date.substring(5, 7), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.date.substring(8), fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
            }

            // 班次 + 附加状态 badge（与统计页"每日明细" ShiftTypeBadge 风格一致：4dp 圆角矩形 + 满色背景 + 白文字）
            if (item.shiftName != null || item.appliedStatusName != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.shiftName?.let { name ->
                        val bgColor = item.shiftColor?.let { hex ->
                            runCatching { Color(hex.toColorInt()) }.getOrElse { Color(0xFF059669) }
                        } ?: Color(0xFF059669)
                        DetailBadge(name, bgColor)
                    }
                    item.appliedStatusName?.let { name ->
                        val bgColor = item.appliedStatusColor?.let { hex ->
                            runCatching { Color(hex.toColorInt()) }.getOrElse { Color(0xFFF97316) }
                        } ?: Color(0xFFF97316)
                        DetailBadge(name, bgColor)
                    }
                }
            }

            // 主要文本（备注类型 allowMultiline=true 自动换行不截断，其他类型 2 行省略）
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.primaryText,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (allowMultiline) Int.MAX_VALUE else 2,
                    overflow = if (allowMultiline) TextOverflow.Clip else TextOverflow.Ellipsis
                )
                if (item.secondaryText.isNotBlank()) {
                    Text(item.secondaryText, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (item.highlightText.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (item.isAlert) Color(0xFFFEE2E2) else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        item.highlightText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (item.isAlert) Color(0xFFDC2626)
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * 详情页 badge：与统计页"每日明细"中 ShiftTypeBadge 风格一致
 * （4dp 圆角矩形 + 满色背景 + 白色文字）
 */
@Composable
private fun DetailBadge(label: String, bgColor: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = bgColor) {
        Text(
            label, fontSize = 11.sp, color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}