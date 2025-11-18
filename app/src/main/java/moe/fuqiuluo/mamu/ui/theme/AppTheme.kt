package moe.fuqiuluo.mamu.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import moe.fuqiuluo.mamu.R

/**
 * 应用主题枚举
 */
enum class AppTheme(
    @StringRes val displayNameRes: Int,
    @StringRes val descriptionRes: Int,
    val primaryLight: Color,
    val secondaryLight: Color,
    val tertiaryLight: Color,
    val primaryDark: Color,
    val secondaryDark: Color,
    val tertiaryDark: Color
) {
    TECH(
        displayNameRes = R.string.theme_tech,
        descriptionRes = R.string.theme_tech_desc,
        primaryLight = TechColors.primary40,
        secondaryLight = TechColors.secondary40,
        tertiaryLight = TechColors.tertiary40,
        primaryDark = TechColors.primary80,
        secondaryDark = TechColors.secondary80,
        tertiaryDark = TechColors.tertiary80
    ),

    CYAN(
        displayNameRes = R.string.theme_cyan,
        descriptionRes = R.string.theme_cyan_desc,
        primaryLight = CyanColors.primary40,
        secondaryLight = CyanColors.secondary40,
        tertiaryLight = CyanColors.tertiary40,
        primaryDark = CyanColors.primary80,
        secondaryDark = CyanColors.secondary80,
        tertiaryDark = CyanColors.tertiary80
    ),

    BLUE(
        displayNameRes = R.string.theme_blue,
        descriptionRes = R.string.theme_blue_desc,
        primaryLight = BlueColors.primary40,
        secondaryLight = BlueColors.secondary40,
        tertiaryLight = BlueColors.tertiary40,
        primaryDark = BlueColors.primary80,
        secondaryDark = BlueColors.secondary80,
        tertiaryDark = BlueColors.tertiary80
    ),

    GREEN(
        displayNameRes = R.string.theme_green,
        descriptionRes = R.string.theme_green_desc,
        primaryLight = GreenColors.primary40,
        secondaryLight = GreenColors.secondary40,
        tertiaryLight = GreenColors.tertiary40,
        primaryDark = GreenColors.primary80,
        secondaryDark = GreenColors.secondary80,
        tertiaryDark = GreenColors.tertiary80
    ),

    ORANGE(
        displayNameRes = R.string.theme_orange,
        descriptionRes = R.string.theme_orange_desc,
        primaryLight = OrangeColors.primary40,
        secondaryLight = OrangeColors.secondary40,
        tertiaryLight = OrangeColors.tertiary40,
        primaryDark = OrangeColors.primary80,
        secondaryDark = OrangeColors.secondary80,
        tertiaryDark = OrangeColors.tertiary80
    ),

    RED(
        displayNameRes = R.string.theme_red,
        descriptionRes = R.string.theme_red_desc,
        primaryLight = RedColors.primary40,
        secondaryLight = RedColors.secondary40,
        tertiaryLight = RedColors.tertiary40,
        primaryDark = RedColors.primary80,
        secondaryDark = RedColors.secondary80,
        tertiaryDark = RedColors.tertiary80
    ),

    PURPLE(
        displayNameRes = R.string.theme_purple,
        descriptionRes = R.string.theme_purple_desc,
        primaryLight = PurpleColors.primary40,
        secondaryLight = PurpleColors.secondary40,
        tertiaryLight = PurpleColors.tertiary40,
        primaryDark = PurpleColors.primary80,
        secondaryDark = PurpleColors.secondary80,
        tertiaryDark = PurpleColors.tertiary80
    ),

    PINK(
        displayNameRes = R.string.theme_pink,
        descriptionRes = R.string.theme_pink_desc,
        primaryLight = PinkColors.primary40,
        secondaryLight = PinkColors.secondary40,
        tertiaryLight = PinkColors.tertiary40,
        primaryDark = PinkColors.primary80,
        secondaryDark = PinkColors.secondary80,
        tertiaryDark = PinkColors.tertiary80
    ),

    AMBER(
        displayNameRes = R.string.theme_amber,
        descriptionRes = R.string.theme_amber_desc,
        primaryLight = AmberColors.primary40,
        secondaryLight = AmberColors.secondary40,
        tertiaryLight = AmberColors.tertiary40,
        primaryDark = AmberColors.primary80,
        secondaryDark = AmberColors.secondary80,
        tertiaryDark = AmberColors.tertiary80
    ),

    INDIGO(
        displayNameRes = R.string.theme_indigo,
        descriptionRes = R.string.theme_indigo_desc,
        primaryLight = IndigoColors.primary40,
        secondaryLight = IndigoColors.secondary40,
        tertiaryLight = IndigoColors.tertiary40,
        primaryDark = IndigoColors.primary80,
        secondaryDark = IndigoColors.secondary80,
        tertiaryDark = IndigoColors.tertiary80
    );

    companion object {
        /**
         * 从字符串名称获取主题
         */
        fun fromName(name: String?): AppTheme {
            if (name == null) {
                return TECH
            }
            return entries.find { it.name == name } ?: TECH
        }
    }
}