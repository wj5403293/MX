package moe.fuqiuluo.mamu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import moe.fuqiuluo.mamu.ui.screen.PermissionSetupScreen
import moe.fuqiuluo.mamu.ui.theme.MXTheme

/**
 * 权限设置启动页面
 * 用于在应用启动时检查和授予必要的权限
 */
class PermissionSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MXTheme {
                PermissionSetupScreen()
            }
        }
    }
}