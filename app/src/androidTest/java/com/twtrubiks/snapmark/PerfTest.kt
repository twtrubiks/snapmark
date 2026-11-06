package com.twtrubiks.snapmark

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/**
 * 畫的時候順不順：作品上已經畫了很多筆（[STROKES] 條筆畫，每條 [POINTS] 個點），再用筆畫幾筆，
 * 量這段期間畫面每一格花多久（`dumpsys gfxinfo`）與每一筆花多久（UiAutomator 拉一筆要等 App 處理完每一下）。
 * 同一次、同一台模擬器上先用改之前的畫法（`AppContainer.splitCanvasLayers` 關掉：每一格全部重畫），
 * 再用分兩層的畫法，各畫 [NEW_STROKES] 筆來比較（後量的那一次畫布上多幾筆，對它比較不利）。
 * `record.sh` 在 SystemUiTest 之後跑，不錄影。
 *
 * 不判斷快慢：模擬器是軟體繪圖（swiftshader），跟手機差很多。數字存成 `screenshots/perf.txt`，每畫完一筆就先存一次
 * （被 timeout 停掉也留得下量到的）。
 */
@RunWith(AndroidJUnit4::class)
class PerfTest : UiTest() {
    @After
    fun restore() {
        container.splitCanvasLayers = true
    }

    @Test
    fun drawingOnABusyCanvas() {
        clearWorks()
        container.tools.remember(ToolChoice(Tool.PEN))
        val work = container.works.create(1440, 1920, hasBase = false)
        container.works.saveMarkup(work.id, Markup(busyStrokes()))
        val report = StringBuilder("畫布上已經有 $STROKES 條筆畫（每條 $POINTS 個點），每種畫法再畫 $NEW_STROKES 筆\n")
        saveText("perf.txt", report.toString())

        var nodes = STROKES
        for ((label, split) in listOf("改之前（一層，每一格全部重畫）" to false, "分兩層（只重畫正在畫的那一筆）" to true)) {
            container.splitCanvasLayers = split
            launchApp()
            openTab(HomeTab.WORKS, By.res("work_${work.id}")).click()
            val bounds = waitFor(By.res("canvas")).visibleBounds
            // 第一次畫完再開始量
            pause(2000)

            report.appendLine().appendLine("== $label")
            shell("dumpsys gfxinfo ${context.packageName} reset")
            val times = mutableListOf<Long>()
            repeat(NEW_STROKES) { i ->
                val y = bounds.top + bounds.height() * (0.3f + 0.1f * i)
                val start = SystemClock.uptimeMillis()
                device.swipe(
                    (bounds.left + bounds.width() * 0.15f).toInt(),
                    y.toInt(),
                    (bounds.left + bounds.width() * 0.85f).toInt(),
                    (y + bounds.height() * 0.04f).toInt(),
                    STEPS,
                )
                times += SystemClock.uptimeMillis() - start
                saveText("perf.txt", "${report}每一筆花的時間（毫秒，還在量）：${times.joinToString()}\n")
                pause(400)
            }
            val stats = shell("dumpsys gfxinfo ${context.packageName}")
            report.appendLine("每一筆花的時間（毫秒，$STEPS 步，不卡的話大約 ${STEPS * 5}）：${times.joinToString()}")
            stats.lines().map { it.trim() }.filter { line -> SUMMARY.any { line.startsWith(it) } }.forEach(report::appendLine)
            saveText("perf.txt", report.toString())
            assertTrue("gfxinfo 沒有資料：$stats", stats.contains("Total frames rendered"))

            nodes += NEW_STROKES
            waitForNodes(work.id, nodes, timeout = 15_000)
            find(By.res("canvas_back")).click()
            waitFor(By.res(HomeTab.CAMERA.tag))
        }
    }

    // 一條一條的波浪，鋪滿整張空白畫布
    private fun busyStrokes() = List(STROKES) { s ->
        val top = 80f + s * 1760f / STROKES
        Node.Stroke(
            id = "busy-$s",
            points = List(POINTS) { i -> Point(60f + i * 1320f / POINTS, top + 40f * sin(i / 4f + s)) },
            color = MarkupColor.entries[s % MarkupColor.entries.size],
            size = StrokeSize.M,
        )
    }

    private companion object {
        const val STROKES = 40
        const val POINTS = 80
        const val NEW_STROKES = 4
        // UiAutomator 一步大約 5 毫秒
        const val STEPS = 60
        val SUMMARY = listOf("Total frames rendered", "Janky frames", "50th percentile", "90th percentile", "95th percentile", "99th percentile", "Number Slow UI thread", "Number Slow issue draw commands")
    }
}
