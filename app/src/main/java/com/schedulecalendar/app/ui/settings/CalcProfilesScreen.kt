// app/src/main/java/com/schedulecalendar/app/ui/settings/CalcProfilesScreen.kt
package com.schedulecalendar.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.CalcProfileRepository
import com.schedulecalendar.app.data.repository.ShiftBreakRepository
import com.schedulecalendar.app.domain.model.CalcProfile
import com.schedulecalendar.app.domain.model.ShiftBreak
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
    private val profileRepo: CalcProfileRepository,
    private val prefs: AppPreferences,
    private val breakRepo: ShiftBreakRepository
) : ViewModel() {

    private val _profiles = MutableStateFlow<List<CalcProfile>>(emptyList())
    val profiles = _profiles.asStateFlow()

    private val _deletability = MutableStateFlow<Map<String, CalcProfileRepository.ProfileDeletability>>(emptyMap())
    val deletability = _deletability.asStateFlow()

    private val _uiEvent = Channel<CalcProfilesUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val list = runCatching { profileRepo.getAllDesc() }.getOrDefault(emptyList())
        _profiles.value = list
        // 逐条判定能否安全删除（今天才生效且区间内无排班数据）
        _deletability.value = list.associate { p ->
            p.id to runCatching { profileRepo.deletability(p) }
                .getOrDefault(CalcProfileRepository.ProfileDeletability.NotDeletable("无法判定"))
        }
    }

    /** 删除一条今天才生效、且区间内无数据的口档；不可删时把原因回报给用户 */
    fun delete(id: String) = viewModelScope.launch {
        val profile = _profiles.value.firstOrNull { it.id == id }
        if (profile == null) {
            _uiEvent.send(CalcProfilesUiEvent.ShowError("口径不存在"))
            return@launch
        }
        runCatching { profileRepo.deleteIfUnused(profile) }
            .onSuccess { ok ->
                refresh()
                // 删除后当前口径变了 → 同步 DataStore 现值，避免设置页仍显示被删档案的参数
                if (ok) {
                    runCatching { profileRepo.getCurrentProfile() }.getOrNull()?.let { cur ->
                        prefs.saveSalaryConfig(cur.salary)
                        prefs.saveAttendConfig(cur.attend)
                        syncBreaksToTable(cur.breaks)
                    }
                }
                _uiEvent.send(
                    if (ok) CalcProfilesUiEvent.ShowMessage("已删除「${profile.name}」，今天起恢复使用上一份口径")
                    else CalcProfilesUiEvent.ShowError("该口径不可删除：删除会改变历史计算结果")
                )
            }
            .onFailure {
                _uiEvent.send(CalcProfilesUiEvent.ShowError("删除失败：${it.message}"))
            }
    }

    /**
     * 复制档案时的命名：源名已以当年月开头就沿用，避免
     * 「2026-10 回滚至 2026-10 回滚至 初始口径」这种越叠越长的名字。
     */
    private fun copyAsName(srcName: String, today: String): String {
        val ym = today.take(7)
        return if (srcName.startsWith(ym)) srcName else "$ym $srcName"
    }

    /**
     * 把档案的休息时段同步回 `shift_break` 表。
     *
     * 休息时段有**两份数据**：档案里的 JSON 快照（计算用）+ shift_break 表（编辑器读的）。
     * 之前只做了「改时段 → 先建档案再落库」一个方向，反方向（复制/删除口径）漏了同步，
     * 导致档案已回退、但时段编辑器仍显示改动后的集合。
     *
     * 实现已收口到 [CalcProfileRepository.syncBreaksToTable]（便于测试覆盖这条链路）。
     */
    private suspend fun syncBreaksToTable(breaks: List<ShiftBreak>) {
        profileRepo.syncBreaksToTable(breaks)
    }

    /** 把某个档案复制为「今天生效」的新档案（回滚到旧口径 / 基于旧口径微调） */
    fun copyAsNew(id: String) = viewModelScope.launch {
        runCatching {
            val src = _profiles.value.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("口径不存在")
            val today = DateFormats.date(LocalDate.now())
            // 记录档案数：用于判断「相邻重复合并」是否命中（命中时不会新建档案）
            val countBefore = profileRepo.countAll()
            val created = profileRepo.createDerivedProfile(
                effectiveFrom = today,
                name = copyAsName(src.name, today),
                salary = src.salary,
                attend = src.attend,
                breaks = src.breaks
            )
            val reused = profileRepo.countAll() == countBefore
            // 同步回 DataStore 现值：设置页读的是现值，不同步就会出现
            // 「档案已换成旧口径、但设置页仍显示新参数」的不一致
            prefs.saveSalaryConfig(created.salary)
            prefs.saveAttendConfig(created.attend)
            // 时段集合同步到该口径的快照：
            // ⚠️ 当前有效时段里**不在该口径快照中**的那些会被归档（退出生效），
            //    它们变成「已归档 + 无引用」的废弃数据，需由「数据管理 → 清理废弃数据」清理。
            //    数量 = |当前有效时段集合 \ 该口径快照|，固定不变、不会累积。
            syncBreaksToTable(created.breaks)
            refresh()
            _uiEvent.send(
                if (reused) {
                    // 相邻重复合并：内容与当前生效口径完全一致 → 实际没有新建档案，
                    // 若照旧提示「已创建新口径」会误导用户以为多了一份
                    CalcProfilesUiEvent.ShowMessage(
                        "该口径内容与当前生效口径完全一致，未新建档案；" +
                            "现值与休息时段已同步为该口径（之前的记录不变）"
                    )
                } else {
                    CalcProfilesUiEvent.ShowMessage(
                        "已创建新口径「${created.name}」，自 $today 起生效；之前的记录不变"
                    )
                }
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

    val deletability by vm.deletability.collectAsStateWithLifecycle()
        var pendingDelete by remember { mutableStateOf<CalcProfile?>(null) }
    LaunchedEffect(Unit) {
        vm.uiEvent.collect { e ->
            when (e) {
                is CalcProfilesUiEvent.ShowMessage -> snackbar.showSnackbar(e.msg)
                is CalcProfilesUiEvent.ShowError   -> snackbar.showSnackbar(e.msg)
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除口径") },
            text = { Text("确定删除当前口径「${target.name}」吗？\n\n删除后，${target.effectiveFrom} 起恢复使用上一份口径。\n若该期间已有排班记录按它计算过，这些记录会改用上一份口径重算，数值可能变化（更早的历史口径不受影响）。") },
            confirmButton = { TextButton(onClick = { vm.delete(target.id); pendingDelete = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
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
                        // 明确告诉用户「现在到底按哪条算」——同日多条时只有最新创建的那条生效
                        val today = DateFormats.date(java.time.LocalDate.now())
                        val current = profiles.firstOrNull { it.effectiveTo == null && it.effectiveFrom <= today }
                        if (current != null) {
                            Text(
                                "当前正在生效：${current.name}（自 ${current.effectiveFrom} 起）",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            items(profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    deletability = deletability[profile.id],
                    onCopy = { vm.copyAsNew(profile.id) },
                    onDelete = { pendingDelete = profile }
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: CalcProfile,
    deletability: CalcProfileRepository.ProfileDeletability?,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val today = DateFormats.date(LocalDate.now())
    // 四态：未生效 / 生效中 / 当日旧版 / 历史
    // 「生效中」= 唯一一条 effectiveTo == null 且已开始生效的档案（getEffectiveOn 同日多条时
    //  按 createdAt 取最新，而只有最新那条的 to 才是 null）→ 保证同一时刻只有一条生效中。
    // 「当日旧版」= 同一天内被后续修改取代的档案（区间收成 [今天,今天]，今天仍落在区间内，
    //  但它已不是当前口径）→ 必须与「生效中」区分，否则会出现多条同时显示生效中。
    val stateLabel = when {
        profile.effectiveFrom > today -> "未生效"
        profile.effectiveTo == null   -> "生效中"
        profile.isEffectiveOn(today)  -> "当日旧版"
        else                          -> "历史"
    }
    val stateColor = when (stateLabel) {
        "生效中"   -> MaterialTheme.colorScheme.primary
        "未生效"   -> MaterialTheme.colorScheme.tertiary
        "当日旧版" -> MaterialTheme.colorScheme.error
        else       -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = stateColor
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
            // 删除靠左、复制靠右（不换行）：无删除按钮时用 weight 占位把复制按钮推到右侧
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (deletability is CalcProfileRepository.ProfileDeletability.Deletable) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCopy) { Text("复制为新口径") }
            }
        }
    }
}

