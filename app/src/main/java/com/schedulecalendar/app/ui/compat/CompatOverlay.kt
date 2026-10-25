package com.schedulecalendar.app.ui.compat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.schedulecalendar.app.data.compat.CompatGate
import com.schedulecalendar.app.data.compat.DataFormat

/**
 * 数据升级遮罩。
 *
 * - 检查中：全屏遮罩 + 进度提示（稍候）；
 * - 失败：显示失败原因（数据需从备份恢复），可关闭继续使用；
 * - 版本过旧：阻断式提示（必须先装回兼容版）。
 */
@Composable
fun CompatOverlay(
    state: CompatGate.State,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        is CompatGate.State.Idle, is CompatGate.State.Ready -> Unit

        is CompatGate.State.Checking -> BlockingBox("正在检查数据版本…")


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

        is CompatGate.State.Unsupported -> BlockingBox(
            "当前数据版本为 v${state.version}，本版本最低支持 v${state.required}。" +
                "请先安装兼容版（${DataFormat.COMPAT_VERSION_LABEL}）打开并导出数据，再升级到当前版本。"
        )
    }
}

@Composable
private fun BlockingBox(message: String) {
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
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
