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
import com.schedulecalendar.app.ui.theme.ScheduleCalendarTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_CLOCK_IN = "com.schedulecalendar.app.ACTION_CLOCK_IN"
        const val ACTION_CLOCK_OUT = "com.schedulecalendar.app.ACTION_CLOCK_OUT"
    }

    // 存储待处理的快捷方式动作
    var pendingShortcutAction: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 处理快捷方式Intent
        handleShortcutIntent(intent)

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
}
