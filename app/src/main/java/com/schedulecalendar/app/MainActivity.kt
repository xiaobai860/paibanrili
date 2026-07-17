// app/src/main/java/com/schedulecalendar/app/MainActivity.kt
package com.schedulecalendar.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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

    // 存储待处理的快捷方式动作
    var pendingShortcutAction: String? = null
        private set

    // 存储小组件点击跳转的日期
    var pendingNavigateDate: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 处理快捷方式Intent
        handleShortcutIntent(intent)

        // 应用启动时恢复动态快捷方式
        CoroutineScope(Dispatchers.Main).launch {
            if (appPreferences.isShortcutEnabled()) {
                WidgetSettingsViewModel.updateDynamicShortcuts(this@MainActivity, true)
            }
        }

        setContent {
            ScheduleCalendarTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        // 处理小组件日期导航
        intent?.getStringExtra(EXTRA_NAVIGATE_DATE)?.let { date ->
            pendingNavigateDate = date
            intent.removeExtra(EXTRA_NAVIGATE_DATE)
        }
        when (intent?.action) {
            ACTION_CLOCK_IN -> {
                pendingShortcutAction = ACTION_CLOCK_IN
                // 清除action避免重复处理
                intent.action = Intent.ACTION_MAIN
            }
            ACTION_CLOCK_OUT -> {
                pendingShortcutAction = ACTION_CLOCK_OUT
                intent.action = Intent.ACTION_MAIN
            }
        }
    }

    /** 清除待处理的快捷方式动作（处理完成后调用） */
    fun consumeShortcutAction(): String? {
        val action = pendingShortcutAction
        pendingShortcutAction = null
        return action
    }

    /** 清除并返回待处理的导航日期（小组件点击） */
    fun consumeNavigateDate(): String? {
        val date = pendingNavigateDate
        pendingNavigateDate = null
        return date
    }
}
