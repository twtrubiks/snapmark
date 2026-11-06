package com.twtrubiks.snapmark

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** 畫布的畫面：跟 DemoRecordingTest 差不多的流程，加上轉螢幕。截圖說明見 [PreviewTest]。 */
@RunWith(RobolectricTestRunner::class)
class CanvasPreviewTest : PreviewTest() {
    @Test
    fun arrowsColorsUndoZoomAndRotation() {
        openBlank()
        shot("01-empty")

        drag(0.25f, 0.65f, 0.7f, 0.35f)
        val first = waitForNodes(1).single() as Node.Arrow
        assertEquals(MarkupColor.PINK, first.color)
        assertEquals(StrokeSize.M, first.size)

        compose.onNodeWithTag("color_button").performClick()
        shot("02-color-picker")
        compose.onNodeWithTag(MarkupColor.BLUE.tag).performClick()
        compose.onNodeWithTag("color_button").performClick()
        compose.onNodeWithTag(StrokeSize.XL.tag).performClick()
        drag(0.8f, 0.75f, 0.45f, 0.5f)
        val second = waitForNodes(2).last() as Node.Arrow
        assertEquals(MarkupColor.BLUE, second.color)
        assertEquals(StrokeSize.XL, second.size)
        shot("03-two-arrows")

        compose.onNodeWithTag("tool_button").performClick()
        shot("04-tool-picker")
        compose.onNodeWithTag("tool_button").performClick()

        compose.onNodeWithTag("undo").performClick()
        waitForNodes(1)
        compose.onNodeWithTag("redo").performClick()
        waitForNodes(2)

        compose.onNodeWithTag("canvas").performTouchInput {
            pinch(center - Offset(50f, 50f), center - Offset(250f, 250f), center + Offset(50f, 50f), center + Offset(250f, 250f), 300)
        }
        // 縮放不會多出標註
        waitForNodes(2)
        shot("05-zoomed")

        // 轉螢幕：Activity 重建，復原、顏色、粗細都還在
        RuntimeEnvironment.setQualifiers("+land")
        compose.activityRule.scenario.recreate()
        waitTag("canvas")
        shot("06-landscape")
        compose.onNodeWithTag("undo").assertExists()
        compose.onNodeWithTag("redo").assertDoesNotExist()
        compose.onNodeWithTag("color_button").assertContentDescriptionEquals("顏色：藍，粗細：特大")
    }

    @Test
    fun everyTool() {
        openBlank()
        val shapes = listOf(
            ShapeKind.RECT to listOf(0.1f, 0.22f, 0.4f, 0.32f),
            ShapeKind.ROUND_RECT to listOf(0.55f, 0.22f, 0.9f, 0.32f),
            ShapeKind.LINE to listOf(0.1f, 0.42f, 0.4f, 0.36f),
            ShapeKind.ELLIPSE to listOf(0.55f, 0.36f, 0.9f, 0.46f),
        )
        for ((i, pair) in shapes.withIndex()) {
            val (kind, at) = pair
            pickTool(Tool.SHAPE, kind.tag)
            drag(at[0], at[1], at[2], at[3])
            assertEquals(kind, (waitForNodes(i + 1).last() as Node.Shape).kind)
        }

        pickTool(Tool.PEN, penTag(highlighter = false))
        compose.onNodeWithTag("canvas").performTouchInput {
            down(Offset(width * 0.1f, height * 0.55f))
            for (k in 1..20) moveTo(Offset(width * (0.1f + k * 0.017f), height * (0.55f + if (k % 2 == 0) 0.02f else -0.02f)))
            up()
        }
        assertEquals(false, (waitForNodes(5).last() as Node.Stroke).highlighter)
        pickTool(Tool.PEN, penTag(highlighter = true))
        drag(0.55f, 0.55f, 0.9f, 0.55f)
        assertEquals(true, (waitForNodes(6).last() as Node.Stroke).highlighter)

        pickTool(Tool.TEXT)
        tap(0.1f, 0.65f)
        waitTag("text_field")
        compose.onNodeWithTag("text_field").performTextInput("這裡！\nSecond line")
        shot("typing")
        compose.onNodeWithTag("text_done").performClick()
        assertEquals("這裡！\nSecond line", (waitForNodes(7).last() as Node.Text).text)

        for ((i, kind) in StampKind.entries.withIndex()) {
            pickTool(Tool.STAMP, kind.tag)
            // 離上面的字遠一點：點在已經畫好的東西上是選取它，不是蓋印章
            tap(0.15f + i * 0.17f, 0.78f)
            assertEquals(kind, (waitForNodes(8 + i).last() as Node.Stamp).kind)
        }
        shot("all-tools")

        // 子選項往左展開的樣子
        compose.onNodeWithTag("tool_button").performClick()
        compose.onNodeWithTag(Tool.STAMP.tag).performClick()
        shot("stamp-options")
    }

    @Test
    fun leavingSavesAndDropsEmptyNewWorks() {
        openBlank()
        drag(0.25f, 0.65f, 0.7f, 0.35f)
        waitForNodes(1)

        compose.onNodeWithTag("canvas_back").performClick()
        compose.onNodeWithTag("tab_works").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("1 個標註", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shot("works")

        // 再開一張空白、什麼都不畫就返回：直接刪掉
        openBlank()
        compose.waitUntil(5_000) { works.all().size == 2 }
        compose.onNodeWithTag("canvas_back").performClick()
        compose.waitUntil(5_000) { works.all().size == 1 }

        // 從作品分頁打開舊的、沒有標註的作品再返回：不刪（只有這次才開的才刪）。復原是空的（只記這一次打開的期間）
        val old = works.create(1440, 1920, hasBase = false)
        compose.onNodeWithTag("tab_works").performClick()
        waitTag("work_${old.id}")
        compose.onNodeWithTag("work_${old.id}").performClick()
        waitTag("canvas")
        compose.onNodeWithTag("undo").assertDoesNotExist()
        compose.onNodeWithTag("canvas_back").performClick()
        compose.waitForIdle()
        Thread.sleep(300)
        assertEquals(2, works.all().size)
    }
}
