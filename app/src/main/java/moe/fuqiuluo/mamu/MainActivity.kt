package moe.fuqiuluo.mamu

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.fuqiuluo.mamu.data.local.RootFileSystem
import moe.fuqiuluo.mamu.data.settings.autoStartFloatingWindow
import moe.fuqiuluo.mamu.service.FloatingWindowService
import moe.fuqiuluo.mamu.ui.screen.MainScreen
import moe.fuqiuluo.mamu.ui.theme.MXTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MXTheme {
                MainScreen()
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            RootFileSystem.connect(applicationContext)
        }

        lifecycleScope.launch(Dispatchers.Main) {
            // 检查是否需要自动启动悬浮窗
            checkAutoStartFloatingWindow()
        }
    }

    private fun checkAutoStartFloatingWindow() {
        val mmkv = MMKV.defaultMMKV()
        if (mmkv.autoStartFloatingWindow) {
            val intent = Intent(this, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
}