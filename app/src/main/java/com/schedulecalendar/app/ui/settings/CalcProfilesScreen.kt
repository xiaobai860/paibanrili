// app/src/main/java/com/schedulecalendar/app/ui/settings/CalcProfilesScreen.kt
package com.schedulecalendar.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.schedulecalendar.app.data.repository.CalcProfileRepository
import com.schedulecalendar.app.domain.model.CalcProfile
import com.schedulecalendar.app.domain.util.DateFormats
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** 口径档案页的一次性事件 */
sealed class CalcProfilesUiEvent {
    data class ShowMessage(val msg: String) : CalcProfilesUiEvent()
    data class ShowError(val msg: String)   : CalcProfilesUiEvent()
}

/**
 * 计算口径档案页（只读 + 复制为新口径）。
 *
 * 设计约定：
 * - **历史口径不可删除** —— 删掉某段区间后，那段日期会回退到「当前设置」→ 历史数值会变；
 *   想回到旧口径请用「复制为新口径」。
 * - 「复制为新口径」= 以选中档案的内容生成一份今天生效的新档案（用于回滚 / 微调）。
 */
@HiltViewModel
class CalcProfilesViewModel @Inject constructor(
    private val profileRepo: CalcProfileRepository
) : ViewModel() {

    private val _profiles = MutableStateFlow<List<CalcProfile>>(emptyList())
    val profiles = _profiles.asStateFlow()

    private val _uiEvent = Channel<CalcProfilesUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _profiles.value = runCatching { profileRepo.getAll() }.getOrDefault(emptyList())
    }

    /** 把某个档案复制为「今天生效」的新档案（回滚到旧口径 / 基于旧口径微调） */
    fun copyAsNew(id: String) = viewModelScope.launch {
        runCatching {
            val src = _profiles.value.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("口径不存在")
            val today = DateFormats.date(LocalDate.now())
            val created = profileRepo.createDerivedProfile(
                effectiveFrom = today,
                name = "${today.take(7)} 回滚至 ${src.name}",
                salary = src.salary,
                attend = src.attend,
                breaks = src.breaks
            )
            refresh()
            _uiEvent.send(
                CalcProfilesUiEvent.ShowMessage(
                    "已创建新口径「${created.name}」，自 $today 起生效；之前的记录不变"
                )
            )
        }.onFailure {
            _uiEvent.send(CalcProfilesUiEvent.ShowError("操作失败：${it.message}"))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalcProfilesScreen(
    navController: NavController,
    vm: CalcProfilesViewModel = hiltViewModel()
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.uiEvent.collect { e ->
            when (e) {
                is CalcProfilesUiEvent.ShowMessage -> snackbar.showSnackbar(e.msg)
                is CalcProfilesUiEvent.ShowError   -> snackbar.showSnackbar(e.msg)
            }
        }
    }

    Scaffold(
        topBar = { ScheduleTopBar("计算口径档案", onBack = { navController.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "口径 = 薪资 + 考勤 + 休息时段",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "每次修改配置都会生成一份新口径并按生效日接力；生效日之前的历史记录仍按旧口径计算，\n" +
                                "因此历史工时与薪资不会被之后的修改影响。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "历史口径不可删除（删除会让那段日期回退到当前设置，历史数值会变）；\n" +
                                "需要回到旧口径请用「复制为新口径」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(profiles, key = { it.id }) { profile ->
                ProfileCard(profile = profile, onCopy = { vm.copyAsNew(profile.id) })
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: CalcProfile, onCopy: () -> Unit) {
    val today = DateFormats.date(LocalDate.now())
    val isCurrent = profile.isEffectiveOn(today)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (isCurrent) "生效中" else "历史",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "生效区间：${profile.effectiveFrom} ~ ${profile.effectiveTo ?: "至今"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "时薪：正常 ${profile.salary.normalRate} / 加班 ${profile.salary.overtimeRate} / " +
                    "周末 ${profile.salary.weekendRate} / 节假日 ${profile.salary.holidayRate}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "加班粒度 ${profile.attend.overtimeGranMin} 分钟 · 休息时段 ${profile.breaks.size} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onCopy) { Text("复制为新口径（今天生效）") }
        }
    }
}

