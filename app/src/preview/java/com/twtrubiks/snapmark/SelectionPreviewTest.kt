package com.twtrubiks.snapmark

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 選取：移動、拉控制點、改顏色、刪除，文字的泡泡與點兩下重新打字，印章的尾巴與文字。截圖說明見 [PreviewTest]。 */
@RunWith(RobolectricTestRunner::class)
class SelectionPreviewTest : PreviewTest() {
    @Test
    fun selectMoveResizeRecolorDelete() {
        openBlank()
        drag(0.2f, 0.35f, 0.7f, 0.35f)
        val drawn = waitForNodes(1).single() as Node.Arrow

        tap(0.45f, 0.35f)
        compose.onNodeWithTag("delete").assertExists()
        shot("01-arrow-selected")

        // 拖著走
        drag(0.45f, 0.35f, 0.45f, 0.5f)
        compose.waitUntil(5_000) { (works.all().single().markup.nodes.single() as Node.Arrow).from.y > drawn.from.y + 100 }
        val moved = works.all().single().markup.nodes.single() as Node.Arrow

        // 拉尖端的控制點改方向
        drag(0.7f, 0.5f, 0.8f, 0.62f)
        compose.waitUntil(5_000) { (works.all().single().markup.nodes.single() as Node.Arrow).to != moved.to }
        assertEquals(moved.from, (works.all().single().markup.nodes.single() as Node.Arrow).from)

        // 改顏色、粗細是改它
        compose.onNodeWithTag("color_button").performClick()
        compose.onNodeWithTag(MarkupColor.GREEN.tag).performClick()
        compose.onNodeWithTag("color_button").performClick()
        compose.onNodeWithTag(StrokeSize.XL.tag).performClick()
        compose.waitUntil(5_000) { (works.all().single().markup.nodes.single() as Node.Arrow).size == StrokeSize.XL }
        assertEquals(MarkupColor.GREEN, (works.all().single().markup.nodes.single() as Node.Arrow).color)
        shot("02-moved-recolored")

        compose.onNodeWithTag("delete").performClick()
        waitForNodes(0)
        compose.onNodeWithTag("delete").assertDoesNotExist()
        compose.onNodeWithTag("undo").performClick()
        waitForNodes(1)
    }

    @Test
    fun textBubbleAndDoubleTap() {
        openBlank()
        pickTool(Tool.TEXT)
        tap(0.2f, 0.5f)
        waitTag("text_field")
        compose.onNodeWithTag("text_field").performTextInput("Hello")
        compose.onNodeWithTag("text_done").performClick()
        waitForNodes(1)

        tap(0.25f, 0.5f)
        waitTag("bubble_plain")
        shot("01-text-selected")
        compose.onNodeWithTag("bubble_plain").performClick()
        compose.waitUntil(5_000) { !(works.all().single().markup.nodes.single() as Node.Text).outlined }

        // 點兩下重新打字
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("canvas").performTouchInput { doubleClick(Offset(width * 0.25f, height * 0.5f)) }
        waitTag("text_field")
        compose.onNodeWithTag("text_field").performTextReplacement("Hello again")
        shot("02-editing-again")
        compose.onNodeWithTag("text_done").performClick()
        compose.waitUntil(5_000) { (works.all().single().markup.nodes.single() as Node.Text).text == "Hello again" }
        assertFalse((works.all().single().markup.nodes.single() as Node.Text).outlined)
    }

    @Test
    fun stampTailAndLabel() {
        openBlank()
        pickTool(Tool.STAMP, StampKind.NOTICE.tag)
        tap(0.35f, 0.45f)
        waitForNodes(1)

        tap(0.35f, 0.45f)
        waitTag("bubble_tail")
        compose.onNodeWithTag("bubble_tail").performClick()
        compose.waitUntil(5_000) { (works.all().single().markup.nodes.single() as Node.Stamp).tail != null }
        compose.onNodeWithTag("bubble_label").performClick()
        waitTag("text_field")
        compose.onNodeWithTag("text_field").performTextInput("注意這裡")
        compose.onNodeWithTag("text_done").performClick()
        compose.waitUntil(5_000) { (works.all().single().markup.nodes.single() as Node.Stamp).label == "注意這裡" }

        // 印章：改粗細是改大小
        compose.onNodeWithTag("color_button").performClick()
        compose.onNodeWithTag(StrokeSize.L.tag).performClick()
        compose.waitUntil(5_000) { (works.all().single().markup.nodes.single() as Node.Stamp).size == StrokeSize.L }
        val stamp = works.all().single().markup.nodes.single() as Node.Stamp
        assertNotNull(stamp.tail)
        assertNotEquals(stamp.at, stamp.tail)
        shot("01-stamp-tail-label")
    }
}
