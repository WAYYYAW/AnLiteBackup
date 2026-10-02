package com.anlite.backup.utils

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.anlite.backup.PREFS_LANGUAGES_SYSTEM
import com.anlite.backup.R
import com.anlite.backup.THEME
import com.anlite.backup.data.preferences.traceDebug
import com.anlite.backup.ui.compose.theme.Contrast
import java.util.Locale

fun Context.setCustomTheme() {
    AppCompatDelegate.setDefaultNightMode(getThemeStyleX(styleTheme))
    if (!(isDynamicTheme && DynamicColors.isDynamicColorAvailable())) {
        setTheme(R.style.AppTheme)
    }
    if (isBlackTheme && isNightMode())
        theme.applyStyle(R.style.Black, true)
}

val isBlackTheme: Boolean
    get() = when (styleTheme) {
        THEME.BLACK.ordinal,
        THEME.BLACK_MEDIUM.ordinal,
        THEME.BLACK_HIGH.ordinal,
        THEME.SYSTEM_BLACK.ordinal,
        THEME.DYNAMIC_BLACK.ordinal,
             -> true

        else -> false
    }

val isDynamicTheme: Boolean
    get() = when (styleTheme) {
        THEME.DYNAMIC.ordinal,
        THEME.DYNAMIC_LIGHT.ordinal,
        THEME.DYNAMIC_DARK.ordinal,
        THEME.DYNAMIC_BLACK.ordinal,
             -> true

        else -> false
    }

fun getThemeContrast(theme: Int): Contrast = when (theme) {
    THEME.LIGHT_MEDIUM.ordinal,
    THEME.DARK_MEDIUM.ordinal,
    THEME.BLACK_MEDIUM.ordinal,
         -> Contrast.MEDIUM

    THEME.LIGHT_HIGH.ordinal,
    THEME.DARK_HIGH.ordinal,
    THEME.BLACK_HIGH.ordinal,
         -> Contrast.HIGH

    else -> Contrast.NORMAL
}

private var sysLocale: LocaleList? = null
private var sysLocaleJVM: Locale? = null

fun Context.setLanguage(lang: String = ""): Configuration {
    var setLocaleCode = if (lang.isEmpty()) language else lang
    traceDebug { "Locale.set: $setLocaleCode" }

    val config = resources.configuration

    if (sysLocale == null) {
        sysLocale = config.locales
        sysLocaleJVM = Locale.getDefault()
        traceDebug { "Locale.sys: $sysLocale $sysLocaleJVM" }
    }

    var wantSystem = false
    if (setLocaleCode == PREFS_LANGUAGES_SYSTEM) {
        wantSystem = true
        setLocaleCode = sysLocale.toString()
    }

    if (wantSystem) {
        config.setLocales(sysLocale)
        sysLocaleJVM?.let { Locale.setDefault(it) }
    } else {
        val newLocale = getLocaleOfCode(setLocaleCode)
        traceDebug { "Locale.new: $newLocale" }
        config.setLocale(newLocale)
        Locale.setDefault(newLocale)
    }

    return config
}

fun getThemeStyleX(theme: Int) = when (theme) {
    THEME.LIGHT.ordinal,
    THEME.LIGHT_MEDIUM.ordinal,
    THEME.LIGHT_HIGH.ordinal,
    THEME.DYNAMIC_LIGHT.ordinal,
         -> AppCompatDelegate.MODE_NIGHT_NO

    THEME.DARK.ordinal,
    THEME.BLACK.ordinal,
    THEME.DARK_MEDIUM.ordinal,
    THEME.BLACK_MEDIUM.ordinal,
    THEME.DARK_HIGH.ordinal,
    THEME.BLACK_HIGH.ordinal,
    THEME.DYNAMIC_DARK.ordinal,
    THEME.DYNAMIC_BLACK.ordinal,
         -> AppCompatDelegate.MODE_NIGHT_YES

    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
}

val Context.isDarkTheme: Boolean
    get() = when (styleTheme) {
        THEME.LIGHT.ordinal,
        THEME.LIGHT_MEDIUM.ordinal,
        THEME.LIGHT_HIGH.ordinal,
        THEME.DYNAMIC_LIGHT.ordinal,
             -> false

        THEME.DARK.ordinal,
        THEME.BLACK.ordinal,
        THEME.DARK_MEDIUM.ordinal,
        THEME.BLACK_MEDIUM.ordinal,
        THEME.DARK_HIGH.ordinal,
        THEME.BLACK_HIGH.ordinal,
        THEME.DYNAMIC_DARK.ordinal,
        THEME.DYNAMIC_BLACK.ordinal,
             -> true

        else -> isNightMode()
    }

fun Context.isNightMode() =
    resources?.configuration?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
