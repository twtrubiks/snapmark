package com.twtrubiks.snapmark

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 打開畫布時的工具：第一次是筆（粉紅、中），之後是上次選的工具、顏色、粗細；平移不記。截圖說明見 [PreviewTest]。
 */
@RunWith(RobolectricTestRunner::class)
class ToolMemoryPreviewTest : PreviewTest() {
    private val tools get() = compose.activity.container.tools

    @Test
    fun startsWithThePenAndRemembersTheLastChoice() {
        // 第一次用 App（PreviewTest 先設成箭頭了，這裡換回預設）
        compose.runOnUiThread { tools.remember(ToolChoice.DEFAULT) }
        openBlank()
        shot("01-pen-by-default")
        drag(0.25f, 0.65f, 0.7f, 0.35f)
        val pen = waitForNodes(1).single() as Node.Stroke
        assertEquals(MarkupColor.PINK, pen.color)
        assertEquals(StrokeSize.M, pen.size)
        assertFalse(pen.highlighter)
        val first = works.all().single().id

        // 換成藍色、特大的箭頭，最後按一下平移（平移不記）
        pickTool(Tool.ARROW)
        compose.onNodeWithTag("color_button").performClick()
        compose.onNodeWithTag(MarkupColor.BLUE.tag).performClick()
        compose.onNodeWithTag("color_button").performClick()
        compose.onNodeWithTag(StrokeSize.XL.tag).performClick()
        pickTool(Tool.PAN)
        assertEquals(ToolChoice(Tool.ARROW, color = MarkupColor.BLUE, size = StrokeSize.XL), tools.value)
        compose.onNodeWithTag("canvas_back").performClick()

        // 再開一張：一拉就是藍色、特大的箭頭
        openBlank()
        shot("02-remembered-arrow")
        drag(0.25f, 0.65f, 0.7f, 0.35f)
        compose.waitUntil(5_000) { works.all().any { it.id != first && it.markup.nodes.size == 1 } }
        val arrow = works.all().first { it.id != first }.markup.nodes.single() as Node.Arrow
        assertEquals(MarkupColor.BLUE, arrow.color)
        assertEquals(StrokeSize.XL, arrow.size)
    }
}
