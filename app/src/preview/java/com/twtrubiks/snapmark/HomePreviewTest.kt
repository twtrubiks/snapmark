package com.twtrubiks.snapmark

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** 首頁三頁的樣子（示意圖、圓形大按鈕）與右上角的深色模式。截圖說明見 [PreviewTest]。 */
@RunWith(RobolectricTestRunner::class)
class HomePreviewTest : PreviewTest() {
    private val theme get() = compose.activity.container.theme

    // 設定存在 SharedPreferences：別的預覽照預設（跟系統）
    @After
    fun resetTheme() {
        compose.runOnUiThread { theme.set(ThemeMode.SYSTEM) }
    }

    @Test
    fun startPagesInLightAndDark() {
        for (tab in listOf(HomeTab.CAMERA, HomeTab.PHOTOS, HomeTab.BLANK)) {
            compose.onNodeWithTag(tab.tag).performClick()
            settle(500)
            shot("light-${tab.name.lowercase()}")
        }

        // 右上角 → 深色（選單是另一個視窗，截不到；點得到）
        compose.onNodeWithTag("theme_button").performClick()
        compose.onNodeWithTag(ThemeMode.DARK.tag).performClick()
        compose.waitForIdle()
        assertEquals(ThemeMode.DARK, theme.value)
        for (tab in listOf(HomeTab.BLANK, HomeTab.PHOTOS, HomeTab.CAMERA, HomeTab.WORKS)) {
            compose.onNodeWithTag(tab.tag).performClick()
            settle(500)
            shot("dark-${tab.name.lowercase()}")
        }

        // 深色時畫布也是深色的底
        openBlank()
        drag(0.25f, 0.65f, 0.7f, 0.35f)
        waitForNodes(1)
        shot("dark-canvas")
    }

    @Test
    fun landscape() {
        RuntimeEnvironment.setQualifiers("+land")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag(HomeTab.CAMERA.tag).performClick()
        settle(500)
        shot("landscape-camera")
        compose.onNodeWithTag(HomeTab.BLANK.tag).performClick()
        settle(500)
        shot("landscape-blank")
    }
}
