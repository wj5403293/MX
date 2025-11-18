package moe.fuqiuluo.mamu.ui.theme

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.fuqiuluo.mamu.utils.PreviewSafeMMKV

/**
 * 主题管理器 - 管理应用主题设置
 */
object ThemeManager {
    private const val MMKV_ID = "theme_config"
    private const val KEY_THEME = "app_theme"
    private const val KEY_DYNAMIC_COLOR = "use_dynamic_color"

    private val mmkv by lazy {
        PreviewSafeMMKV.mmkvWithID(MMKV_ID)
    }

    private val _currentTheme = MutableStateFlow(loadTheme())
    val currentTheme: StateFlow<AppTheme> = _currentTheme.asStateFlow()

    private val _useDynamicColor = MutableStateFlow(loadDynamicColorPreference())
    val useDynamicColor: StateFlow<Boolean> = _useDynamicColor.asStateFlow()

    /**
     * 设置应用主题
     */
    fun setTheme(theme: AppTheme) {
        mmkv.encode(KEY_THEME, theme.name)
        _currentTheme.value = theme
    }

    /**
     * 设置是否使用动态颜色
     */
    fun setUseDynamicColor(useDynamic: Boolean) {
        mmkv.encode(KEY_DYNAMIC_COLOR, useDynamic)
        _useDynamicColor.value = useDynamic
    }

    /**
     * 从MMKV加载主题
     */
    private fun loadTheme(): AppTheme {
        val themeName = mmkv.decodeString(KEY_THEME, null)
        Log.d("ThemeManager", "Loaded theme from MMKV: $themeName")
        return AppTheme.fromName(themeName)
    }

    /**
     * 从MMKV加载动态颜色偏好
     */
    private fun loadDynamicColorPreference(): Boolean {
        return mmkv.decodeBool(KEY_DYNAMIC_COLOR, true) // 默认启用动态颜色
    }
}