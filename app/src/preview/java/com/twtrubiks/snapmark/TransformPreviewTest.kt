package com.twtrubiks.snapmark

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer

/** 旋轉、裁切（連底圖）、復原換回舊的底圖、離開時刪掉沒在用的底圖。截圖說明見 [PreviewTest]。 */
@RunWith(RobolectricTestRunner::class)
class TransformPreviewTest : PreviewTest() {
    @Test
    fun rotateCropUndoAndCleanUp() {
        val file = File(compose.activity.cacheDir, "demo.jpg")
        val phone = DemoPhoto.write(file)
        val photo = runBlocking { compose.activity.container.importPhoto(ImageDecoder.createSource(ByteBuffer.wrap(file.readBytes()))) }
        compose.onNodeWithTag("tab_works").performClick()
        waitTag("work_${photo.id}")
        compose.onNodeWithTag("work_${photo.id}").performClick()
        waitTag("canvas")

        // 馬賽克蓋住電話號碼、一支箭頭
        pickTool(Tool.PIXELATE)
        swipeImage(photo.width, photo.height, Point(phone.left, phone.top), Point(phone.right, phone.bottom))
        pickTool(Tool.ARROW)
        drag(0.3f, 0.65f, 0.5f, 0.55f)
        waitForNodes(2)

        // 向右轉：1600×1200 → 1200×1600，底圖換一張新的檔
        compose.onNodeWithTag("more_button").performClick()
        compose.onNodeWithTag("menu_rotate_right").performClick()
        compose.waitUntil(10_000) { works.all().single().width == 1200 }
        val rotated = works.all().single()
        assertNotEquals(WorkStore.DEFAULT_BASE, rotated.baseName)
        val base = BitmapFactory.decodeFile(works.baseFile(rotated).path)
        assertEquals(1200 to 1600, base.width to base.height)
        settle(500)
        shot("01-rotated")

        // 向左轉回來，再裁切：進入裁切畫面，拉左上角
        compose.onNodeWithTag("more_button").performClick()
        compose.onNodeWithTag("menu_rotate_left").performClick()
        compose.waitUntil(10_000) { works.all().single().width == 1600 }
        settle(300)
        compose.onNodeWithTag("more_button").performClick()
        compose.onNodeWithTag("menu_crop").performClick()
        waitTag("crop_done")
        shot("02-crop-start")
        swipeImage(1600, 1200, Point(0f, 0f), Point(150f, 190f))
        swipeImage(1600, 1200, Point(1600f, 1200f), Point(1450f, 1010f))
        shot("03-crop-box")
        compose.onNodeWithTag("crop_done").performClick()
        compose.waitUntil(10_000) { works.all().single().width < 1600 }
        val cropped = works.all().single()
        assertTrue("裁切的大小：${cropped.width}×${cropped.height}", cropped.width in 1280..1320 && cropped.height in 800..840)
        assertEquals(2, cropped.markup.nodes.size)
        settle(500)
        shot("04-cropped")

        // 復原：換回裁切前的底圖（那張檔還在）
        compose.onNodeWithTag("undo").performClick()
        compose.waitUntil(10_000) { works.all().single().width == 1600 }
        settle(500)
        shot("05-undo")
        compose.onNodeWithTag("redo").performClick()
        compose.waitUntil(10_000) { works.all().single().width == cropped.width }

        // 離開畫布：只留現在用的底圖與縮圖
        compose.onNodeWithTag("canvas_back").performClick()
        val dir = works.dir(photo.id)
        compose.waitUntil(10_000) { dir.list()?.toSet() == setOf(works.all().single().baseName, "thumb.jpg") }
        val thumb = BitmapFactory.decodeFile(works.thumbFile(photo.id).path)
        assertEquals(512, thumb.width)
        settle()
        shot("06-works")
    }

    /** 在畫布上從底圖的 [from] 拉到 [to]（作品 [width]×[height]，還沒縮放平移）。 */
    private fun swipeImage(width: Int, height: Int, from: Point, to: Point) {
        val canvas = compose.onNodeWithTag("canvas").fetchSemanticsNode().size
        val padding = with(compose.density) { CANVAS_PADDING.toPx() }
        val viewport = CanvasFrame(canvas.width.toFloat(), canvas.height.toFloat(), width, height, padding).viewport(Zoom())
        val a = viewport.toScreen(from)
        val b = viewport.toScreen(to)
        compose.onNodeWithTag("canvas").performTouchInput { swipe(Offset(a.x, a.y), Offset(b.x, b.y), 300) }
    }
}
