package com.twtrubiks.snapmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CI 錄影的展示流程：`record.sh` 在這個 class 執行期間用 screenrecord 錄整個螢幕。
 * 一個 @Test 跑完整的流程，每個操作之間停一下讓影片看得清楚，全長控制在 150 秒內。
 * 每做完一個功能就把它加進來；寫法的注意事項在 CLAUDE.md 的「錄影測試」。
 */
@RunWith(AndroidJUnit4::class)
class DemoRecordingTest : UiTest() {
    @Test
    fun demo() {
        clearWorks()
        launchApp()

        // 骨架：四個分頁切一輪
        for (tab in HomeTab.entries) {
            find(By.res(tab.tag)).click()
            pause()
        }
        screenshot("01-works-empty")

        // 空白 → 開一張作品 → 進畫布
        find(By.res(HomeTab.BLANK.tag)).click()
        pause()
        find(By.res("blank_button")).click()
        waitFor(By.res("canvas"))
        pause(1500)
        screenshot("02-canvas-blank")

        // 返回 → 作品分頁多了一張
        find(By.res("canvas_back")).click()
        find(By.res(HomeTab.WORKS.tag)).click()
        waitFor(By.res("work_list"))
        pause(1500)
        screenshot("03-works-one")
    }
}
