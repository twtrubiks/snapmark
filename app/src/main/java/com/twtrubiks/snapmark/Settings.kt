package com.twtrubiks.snapmark

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** 深色模式：首頁右上角的選單（`HomeScreen` 的 `ThemeMenu`），[tag] 是選單裡那一項的 testTag。 */
enum class ThemeMode(val label: String, val tag: String) {
    SYSTEM("跟系統", "theme_system"),
    LIGHT("淺色", "theme_light"),
    DARK("深色", "theme_dark"),
}

/**
 * 使用者選的設定（深色模式 [ThemeMode]），存在 SharedPreferences（存名字，不存序號）。
 * [value] 是 Compose 的狀態，畫面直接讀。
 */
class EnumSetting<E : Enum<E>>(private val prefs: SharedPreferences, private val key: String, entries: List<E>, default: E) {
    var value by mutableStateOf(prefs.getString(key, null)?.let { saved -> entries.firstOrNull { it.name == saved } } ?: default)
        private set

    fun set(value: E) {
        this.value = value
        prefs.edit { putString(key, value.name) }
    }
}

/**
 * 上次用的工具、顏色、粗細（[ToolChoice]），打開畫布時用；第一次用是 [ToolChoice.DEFAULT]（筆）。
 * 存在 SharedPreferences `settings`，存名字（不存序號）：認不得的（之後改了名字、拿掉的選項）用預設。
 */
class ToolMemory(private val prefs: SharedPreferences) {
    var value: ToolChoice = read()
        private set

    /** 選了工具、子選項、顏色、粗細時呼叫。平移不記（見 [ToolChoice.remembered]）。 */
    fun remember(choice: ToolChoice) {
        val next = choice.remembered(value)
        if (next == value) return
        value = next
        prefs.edit {
            putString(TOOL, next.tool.name)
            putString(SHAPE, next.shape.name)
            putString(PEN, if (next.highlighter) HIGHLIGHTER else MARKER)
            putString(STAMP, next.stamp.name)
            putString(COLOR, next.color.name)
            putString(SIZE, next.size.name)
        }
    }

    private fun read(): ToolChoice {
        val default = ToolChoice.DEFAULT
        return ToolChoice(
            tool = saved(TOOL, Tool.entries, default.tool),
            shape = saved(SHAPE, ShapeKind.entries, default.shape),
            highlighter = prefs.getString(PEN, null)?.let { it == HIGHLIGHTER } ?: default.highlighter,
            stamp = saved(STAMP, StampKind.entries, default.stamp),
            color = saved(COLOR, MarkupColor.entries, default.color),
            size = saved(SIZE, StrokeSize.entries, default.size),
        )
    }

    private fun <E : Enum<E>> saved(key: String, entries: List<E>, default: E): E =
        prefs.getString(key, null)?.let { name -> entries.firstOrNull { it.name == name } } ?: default

    private companion object {
        const val TOOL = "tool"
        const val SHAPE = "tool_shape"
        const val PEN = "tool_pen"
        const val STAMP = "tool_stamp"
        const val COLOR = "tool_color"
        const val SIZE = "tool_size"
        const val MARKER = "MARKER"
        const val HIGHLIGHTER = "HIGHLIGHTER"
    }
}
