/*
 * Copyright (C) 2015-present, osfans
 * waxaca@163.com https://github.com/osfans
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.osfans.trime.data.theme

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.annotation.ColorInt
import androidx.core.math.MathUtils
import com.osfans.trime.core.Rime
import com.osfans.trime.data.AppPrefs
import com.osfans.trime.data.DataManager.userDataDir
import com.osfans.trime.data.sound.SoundEffectManager
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.util.CollectionUtils
import com.osfans.trime.util.ColorUtils
import com.osfans.trime.util.bitmapDrawable
import com.osfans.trime.util.dp2px
import timber.log.Timber
import java.io.File
import java.lang.IllegalArgumentException
import java.util.Objects
import kotlin.system.measureTimeMillis

/** 主题和样式配置  */
class Theme(private var isDarkMode: Boolean) {
    private var generalStyle: Map<String, Any?>? = null
    private var fallbackColors: Map<String, String>? = null
    private var presetColorSchemes: Map<String, Map<String, Any>?>? = null
    private var presetKeyboards: Map<String, Any?>? = null
    private var liquidKeyboard: Map<String, Any?>? = null
    lateinit var allKeyboardIds: List<String>

    // 当前配色 id
    lateinit var currentColorSchemeId: String

    // 遍历当前配色方案的值、fallback的值，从而获得当前方案的全部配色Map
    private val currentColors: MutableMap<String, Any> = hashMapOf()

    // 上一个 light 配色
    private var lastLightColorSchemeId: String? = null

    // 上一个 dark 配色
    private var lastDarkColorSchemeId: String? = null

    @JvmField
    val style = Style(this)

    @JvmField
    val liquid = Liquid(this)

    @JvmField
    val colors = Colors(this)

    @JvmField
    val keyboards = Keyboards(this)

    companion object {
        private const val VERSION_KEY = "config_version"
        private val appPrefs = AppPrefs.defaultInstance()

        private const val DEFAULT_THEME_NAME = "trime"

        fun isFileString(str: String?): Boolean {
            return str?.contains(Regex("""\.[a-z]{3,4}$""")) == true
        }
    }

    init {
        init()
        Timber.d("Setting sound from color ...")
        SoundEffectManager.switchSound(colors.getString("sound"))
        Timber.d("Initialization finished")
    }

    fun init() {
        val active = appPrefs.theme.selectedTheme
        Timber.i("Initializing theme, currentThemeName=%s ...", active)
        runCatching {
            val themeFileName = "$active.yaml"
            Timber.i("Deploying theme '%s' ...", themeFileName)
            if (!Rime.deployRimeConfigFile(themeFileName, VERSION_KEY)) {
                Timber.w("Deploying theme '%s' failed", themeFileName)
            }
            Timber.d("Fetching global theme config map ...")
            measureTimeMillis {
                var fullThemeConfigMap: Map<String, Any>?
                if (Rime.getRimeConfigMap(active, "").also { fullThemeConfigMap = it } == null) {
                    fullThemeConfigMap = Rime.getRimeConfigMap(DEFAULT_THEME_NAME, "")
                }
                Objects.requireNonNull(fullThemeConfigMap, "The theme file cannot be empty!")
                Timber.d("Fetching done")
                generalStyle = fullThemeConfigMap!!["style"] as Map<String, Any?>?
                fallbackColors = fullThemeConfigMap!!["fallback_colors"] as Map<String, String>?
                Key.presetKeys = fullThemeConfigMap!!["preset_keys"] as Map<String, Map<String, Any?>?>?
                presetColorSchemes = fullThemeConfigMap!!["preset_color_schemes"] as Map<String, Map<String, Any>?>?
                presetKeyboards = fullThemeConfigMap!!["preset_keyboards"] as Map<String, Any?>?
                liquidKeyboard = fullThemeConfigMap!!["liquid_keyboard"] as Map<String, Any?>?
                // 将 presetKeyboards 的所有 key 转为 allKeyboardIds
                allKeyboardIds = presetKeyboards!!.keys.toList()
            }.also {
                Timber.d("Setting up all theme config map takes $it ms")
            }
            measureTimeMillis {
                initColorScheme()
            }.also {
                Timber.d("Initializing cache takes $it ms")
            }
            Timber.i("The theme is initialized")
        }.getOrElse {
            Timber.e("Failed to parse the theme: ${it.message}")
            if (appPrefs.theme.selectedTheme != DEFAULT_THEME_NAME) {
                appPrefs.theme.selectedTheme = DEFAULT_THEME_NAME
                init()
            }
        }
    }

    class Style(private val theme: Theme) {
        fun getString(key: String): String {
            return CollectionUtils.obtainString(theme.generalStyle, key, "")
        }

        fun getInt(key: String): Int {
            return CollectionUtils.obtainInt(theme.generalStyle, key, 0)
        }

        fun getFloat(key: String): Float {
            return CollectionUtils.obtainFloat(theme.generalStyle, key, 0f)
        }

        fun getBoolean(key: String): Boolean {
            return CollectionUtils.obtainBoolean(theme.generalStyle, key, false)
        }

        fun getObject(key: String): Any? {
            return CollectionUtils.obtainValue(theme.generalStyle, key)
        }
    }

    class Liquid(private val theme: Theme) {
        fun getObject(key: String): Any? {
            return CollectionUtils.obtainValue(theme.liquidKeyboard, key)
        }

        fun getInt(key: String): Int {
            return CollectionUtils.obtainInt(theme.liquidKeyboard, key, 0)
        }

        fun getFloat(key: String): Float {
            return CollectionUtils.obtainFloat(theme.liquidKeyboard, key, theme.style.getFloat(key))
        }
    }

    class Colors(private val theme: Theme) {
        fun getString(key: String): String {
            return CollectionUtils.obtainString(theme.presetColorSchemes, key, "")
        }

        // API 2.0
        @ColorInt
        fun getColor(key: String?): Int? {
            val o = theme.currentColors[key]
            return if (o is Int) o else null
        }

        @ColorInt
        fun getColor(
            m: Map<String, Any?>,
            key: String?,
        ): Int? {
            val value = theme.getColorValue(m[key] as String?)
            return if (value is Int) value else null
        }

        //  返回drawable。  Config 2.0
        //  参数可以是颜色或者图片。如果参数缺失，返回null
        fun getDrawable(key: String): Drawable? {
            val o = theme.currentColors[key]
            if (o is Int) {
                return GradientDrawable().apply { setColor(o) }
            } else if (o is Drawable) {
                return o
            }
            return null
        }

        // API 2.0
        fun getDrawable(
            m: Map<String, Any?>,
            key: String,
        ): Drawable? {
            val value = theme.getColorValue(m[key] as String?)
            if (value is Int) {
                return GradientDrawable().apply { setColor(value) }
            } else if (value is Drawable) {
                return value
            }
            return null
        }

        //  返回图片或背景的drawable,支持null参数。 Config 2.0
        fun getDrawable(
            key: String?,
            borderKey: String?,
            borderColorKey: String?,
            roundCornerKey: String,
            alphaKey: String?,
        ): Drawable? {
            val value = theme.getColorValue(key)
            if (value is Drawable) {
                if (!alphaKey.isNullOrEmpty() && theme.style.getObject(alphaKey) != null) {
                    value.alpha = MathUtils.clamp(theme.style.getInt(alphaKey), 0, 255)
                }
                return value
            }

            if (value is Int) {
                val gradient = GradientDrawable().apply { setColor(value) }
                if (roundCornerKey.isNotEmpty()) {
                    gradient.cornerRadius = theme.style.getFloat(roundCornerKey)
                }
                if (!borderColorKey.isNullOrEmpty() && !borderKey.isNullOrEmpty()) {
                    val border = dp2px(theme.style.getFloat(borderKey))
                    val stroke = getColor(borderColorKey)
                    if (stroke != null && border > 0) {
                        gradient.setStroke(border.toInt(), stroke)
                    }
                }
                if (!alphaKey.isNullOrEmpty() && theme.style.getObject(alphaKey) != null) {
                    gradient.alpha = MathUtils.clamp(theme.style.getInt(alphaKey), 0, 255)
                }
                return gradient
            }
            return null
        }
    }

    class Keyboards(private val theme: Theme) {
        fun getObject(key: String): Any? {
            return CollectionUtils.obtainValue(theme.presetKeyboards, key)
        }
    }

    /**
     * 第一次载入主题，初始化默认配色
     * */
    private fun initColorScheme() {
        var colorScheme = appPrefs.theme.selectedColor
        if (!presetColorSchemes!!.containsKey(colorScheme)) colorScheme = style.getString("color_scheme") // 主題中指定的配色
        if (!presetColorSchemes!!.containsKey(colorScheme)) colorScheme = "default" // 主題中的default配色
        currentColorSchemeId = colorScheme
        // 配色表中没有这个 id
        if (!presetColorSchemes!!.containsKey(currentColorSchemeId)) {
            Timber.e("Color scheme %s not found", currentColorSchemeId)
            throw IllegalArgumentException("Color scheme $currentColorSchemeId not found!")
        }
        switchDarkMode(isDarkMode)
    }

    /**
     * 切换到指定配色，切换成功后写入 AppPrefs
     * @param colorSchemeId 配色 id
     * */
    fun setColorScheme(colorSchemeId: String) {
        if (!presetColorSchemes!!.containsKey(colorSchemeId)) {
            Timber.w("Color scheme %s not found", colorSchemeId)
            return
        }
        if (currentColorSchemeId == colorSchemeId && currentColors.isNotEmpty()) return
        Timber.d("switch color scheme from %s to %s", currentColorSchemeId, colorSchemeId)
        currentColorSchemeId = colorSchemeId
        refreshColorValues()
        if (isDarkMode) {
            lastDarkColorSchemeId = colorSchemeId
        } else {
            lastLightColorSchemeId = colorSchemeId
        }
        AppPrefs.defaultInstance().theme.selectedColor = colorSchemeId
    }

    /**
     * 切换深色/亮色模式
     * */
    fun switchDarkMode(isDarkMode: Boolean) {
        this.isDarkMode = isDarkMode
        val newId = getColorSchemeId()
        if (newId != null) setColorScheme(newId)
        Timber.d(
            "System changing color, current ColorScheme: $currentColorSchemeId, isDarkMode=$isDarkMode",
        )
    }

    /**
     * @return 切换深色/亮色模式后配色的 id
     */
    private fun getColorSchemeId(): String? {
        val colorMap = presetColorSchemes!![currentColorSchemeId] as Map<String, Any>
        if (isDarkMode) {
            if (colorMap.containsKey("dark_scheme")) {
                return colorMap["dark_scheme"] as String?
            }
            if (lastDarkColorSchemeId != null) {
                return lastDarkColorSchemeId
            }
        } else {
            if (colorMap.containsKey("light_scheme")) {
                return colorMap["light_scheme"] as String?
            }
            if (lastLightColorSchemeId != null) {
                return lastLightColorSchemeId
            }
        }
        return currentColorSchemeId
    }

    private fun joinToFullImagePath(value: String): String {
        val defaultPath = File(userDataDir, "backgrounds/${style.getString("background_folder")}/$value")
        if (!defaultPath.exists()) {
            val fallbackPath = File(userDataDir, "backgrounds/$value")
            if (fallbackPath.exists()) return fallbackPath.path
        }
        return defaultPath.path
    }

    fun getPresetColorSchemes(): List<Pair<String, String>> {
        return if (presetColorSchemes == null) {
            arrayListOf()
        } else {
            presetColorSchemes!!.map { (key, value) ->
                Pair(key, value!!["name"] as String)
            }
        }
    }

    private fun refreshColorValues() {
        currentColors.clear()
        val colorMap = presetColorSchemes!![currentColorSchemeId]
        if (colorMap == null) {
            Timber.w("Color scheme id not found: %s", currentColorSchemeId)
            return
        }

        for ((key, value) in colorMap) {
            if (key == "name" || key == "author" || key == "light_scheme" || key == "dark_scheme") continue
            currentColors[key] = value
        }
        for ((key, value) in fallbackColors!!) {
            if (!currentColors.containsKey(key)) {
                currentColors[key] = value
            }
        }

        // 先遍历一次，处理一下颜色和图片
        // 防止回退时获取到错误值
        for ((key, value) in currentColors) {
            val parsedValue = parseColorValue(value)
            if (parsedValue != null) {
                currentColors[key] = parsedValue
            }
        }

        // fallback
        for ((key, value) in currentColors) {
            if (value is Int || value is Drawable) continue
            val parsedValue = getColorValue(value)
            if (parsedValue != null) {
                currentColors[key] = parsedValue
            } else {
                Timber.w("Cannot parse color key: %s, value: %s", key, value)
            }
        }
        Timber.d("Refresh color scheme, current color scheme: $currentColorSchemeId")
    }

    /** 获取参数的真实value，如果是色彩返回int，如果是背景图返回drawable，都不是则进行 fallback
     * @return Int/Drawable/null
     * */
    private fun getColorValue(value: Any?): Any? {
        val parsedValue = parseColorValue(value)
        if (parsedValue != null) {
            return parsedValue
        }
        var newKey = value
        var newValue: Any?
        val limit = currentColors.size
        for (i in 0 until limit) {
            newValue = currentColors[newKey]
            if (newValue !is String) return newValue
            // fallback
            val parsedNewValue = parseColorValue(newValue)
            if (parsedNewValue != null) {
                return parsedNewValue
            }
            newKey = newValue
        }
        return null
    }

    /** 获取参数的真实value，如果是色彩返回int，如果是背景图返回drawable，如果处理失败返回null
     * @return Int/Drawable/null
     * */
    private fun parseColorValue(value: Any?): Any? {
        if (value !is String) return null
        if (isFileString(value)) {
            // 获取图片的真实地址
            val fullPath = joinToFullImagePath(value)
            if (File(fullPath).exists()) {
                return bitmapDrawable(fullPath)
            }
        }
        // 只对不包含下划线的字符串进行颜色解析
        if (!value.contains("_")) {
            return ColorUtils.parseColor(value)
        }
        return null
    }
}
