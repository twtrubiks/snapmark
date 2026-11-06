package com.twtrubiks.snapmark

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    private val prefs = MapPreferences()

    private fun theme() = EnumSetting(prefs, "theme", ThemeMode.entries, ThemeMode.SYSTEM)

    @Test
    fun defaultsToFollowingTheSystem() {
        assertEquals(ThemeMode.SYSTEM, theme().value)
    }

    @Test
    fun choiceIsSavedByName() {
        theme().set(ThemeMode.DARK)
        assertEquals("DARK", prefs.values["theme"])
        // 下次打開 App 讀得回來
        assertEquals(ThemeMode.DARK, theme().value)
    }

    @Test
    fun unknownSavedValueFallsBackToTheDefault() {
        prefs.values["theme"] = "SEPIA"
        assertEquals(ThemeMode.SYSTEM, theme().value)
    }

    @Test
    fun menuTagsAreUnique() {
        assertEquals(ThemeMode.entries.size, ThemeMode.entries.map { it.tag }.toSet().size)
    }

    @Test
    fun toolsStartWithThePen() {
        assertEquals(ToolChoice.DEFAULT, ToolMemory(prefs).value)
        assertEquals(Tool.PEN, ToolMemory(prefs).value.tool)
    }

    @Test
    fun lastToolColorAndSizeAreRemembered() {
        val choice = ToolChoice(Tool.PEN, ShapeKind.LINE, highlighter = true, stamp = StampKind.HEART, color = MarkupColor.BLUE, size = StrokeSize.XL)
        ToolMemory(prefs).remember(choice)
        // 下次打開 App 讀得回來
        assertEquals(choice, ToolMemory(prefs).value)
        assertEquals("PEN", prefs.values["tool"])
        assertEquals("HIGHLIGHTER", prefs.values["tool_pen"])
    }

    @Test
    fun panIsNotRemembered() {
        val memory = ToolMemory(prefs)
        memory.remember(ToolChoice(Tool.ARROW, color = MarkupColor.RED))
        memory.remember(ToolChoice(Tool.PAN, color = MarkupColor.RED))
        assertEquals(Tool.ARROW, ToolMemory(prefs).value.tool)
    }

    @Test
    fun unknownSavedToolFallsBackToTheDefault() {
        prefs.values["tool"] = "LASSO"
        prefs.values["tool_color"] = "BLUE"
        assertEquals(ToolChoice.DEFAULT.copy(color = MarkupColor.BLUE), ToolMemory(prefs).value)
    }

    /** 只有字串的 SharedPreferences（`EnumSetting`、`ToolMemory` 只用到這些）。 */
    class MapPreferences : SharedPreferences {
        val values = mutableMapOf<String, String?>()

        override fun getAll(): Map<String, *> = values
        override fun getString(key: String, defValue: String?) = if (key in values) values[key] else defValue
        override fun getStringSet(key: String, defValues: Set<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, String?>()
            private val removed = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) values.clear()
                removed.forEach { values.remove(it) }
                values.putAll(pending)
            }
        }
    }
}
