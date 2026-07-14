package com.schedulecalendar.app.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * iOS 风格滚轮选择器
 *
 * @param items         数据列表
 * @param initialIndex  初始选中项索引
 * @param itemHeight    每项高度
 * @param visibleCount  可见项数（必须为奇数）
 * @param modifier      修饰符
 * @param onItemSelected 选中项回调 (index, item)
 */
@Composable
fun WheelPicker(
    items: List<String>,
    initialIndex: Int = 0,
    itemHeight: Dp = 48.dp,
    visibleCount: Int = 5,
    modifier: Modifier = Modifier,
    onItemSelected: (index: Int, item: String) -> Unit = { _, _ -> }
) {
    require(items.isNotEmpty()) { "items 不能为空" }
    require(visibleCount % 2 == 1) { "visibleCount 必须为奇数" }

    val halfPadding = visibleCount / 2
    val itemCount = items.size
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    var isSnapping by remember { mutableStateOf(false) }

    /**
     * 计算当前视口中心对应的数据项索引。
     *
     * LazyColumn 有 contentPadding(top = halfPadding * itemHeightPx)，
     * 使得内容区起始位置偏移了 halfPadding 个 item。
     * 视口中心在内容坐标中位于：
     *   viewportHeight/2 - contentPadding.top
     * = visibleCount * itemHeightPx / 2 - halfPadding * itemHeightPx
     * = (visibleCount/2 - halfPadding) * itemHeightPx
     * = 0 （因为 visibleCount/2 == halfPadding）
     *
     * 因此中心项索引 = firstVisibleItemIndex + firstVisibleItemScrollOffset / itemHeightPx
     */
    fun calcCenterIndex(): Int {
        val topIdx = listState.firstVisibleItemIndex
        val topOffset = listState.firstVisibleItemScrollOffset
        if (itemHeightPx <= 0f) return topIdx.coerceIn(0, itemCount - 1)
        val centerIdx = (topIdx + topOffset / itemHeightPx).roundToInt()
        return centerIdx.coerceIn(0, itemCount - 1)
    }

    // 监听滚动停止 → 回调 + 吸附
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling || isSnapping) return@collect

                // 滚动刚停止，计算中心项
                val target = calcCenterIndex()
                if (target !in items.indices) return@collect

                // 回调（仅当选中项变化时）
                if (target != currentIndex) {
                    currentIndex = target
                    onItemSelected(target, items[target])
                }

                // 吸附动画：确保目标项居中
                // animateScrollToItem(pos) 使 pos 成为第一个可见项，
                // 内容偏移 halfPadding 后，pos + halfPadding 项会出现在中心
                val scrollTarget = (target + halfPadding).coerceIn(0, itemCount - 1)
                if (listState.firstVisibleItemIndex != scrollTarget) {
                    isSnapping = true
                    listState.animateScrollToItem(scrollTarget)
                    isSnapping = false
                }
            }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(itemHeight * visibleCount),
            contentPadding = PaddingValues(vertical = itemHeight * halfPadding)
        ) {
            items(itemCount) { idx ->
                val off = idx - currentIndex
                val aOff = abs(off)
                val sel = off == 0
                val a = when { sel -> 1f; aOff == 1 -> 0.6f; aOff == 2 -> 0.35f; else -> 0.15f }
                val s = when { sel -> 1f; aOff == 1 -> 0.85f; aOff <= 3 -> 0.72f; else -> 0.72f }
                Box(Modifier.fillMaxWidth().height(itemHeight), contentAlignment = Alignment.Center) {
                    Text(
                        text = items[idx],
                        fontSize = if (sel) 18.sp else 14.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().graphicsLayer {
                            alpha = a; scaleX = s; scaleY = s
                        }
                    )
                }
            }
        }
        HorizontalDivider(Modifier.fillMaxWidth().offset(y = -(itemHeight / 2)),
            thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        HorizontalDivider(Modifier.fillMaxWidth().offset(y = itemHeight / 2),
            thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    }
}
