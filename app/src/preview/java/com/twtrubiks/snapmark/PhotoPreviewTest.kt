package com.twtrubiks.snapmark

import android.graphics.ImageDecoder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer

/** 照片的作品：底圖、馬賽克，只有照片的作品才有馬賽克工具。截圖說明見 [PreviewTest]。 */
@RunWith(RobolectricTestRunner::class)
class PhotoPreviewTest : PreviewTest() {
    @Test
    fun photoWithMosaic() {
        val file = File(compose.activity.cacheDir, "demo.jpg")
        val phone = DemoPhoto.write(file)
        // Robolectric 的 ImageDecoder 讀不了檔案（只在真的 Android 上），改給它整個檔案的內容；手機上的匯入是讀檔案
        val work = runBlocking { compose.activity.container.importPhoto(ImageDecoder.createSource(ByteBuffer.wrap(file.readBytes()))) }
        assertTrue(work.hasBase)
        assertEquals(DemoPhoto.WIDTH to DemoPhoto.HEIGHT, work.width to work.height)

        compose.onNodeWithTag("tab_works").performClick()
        waitTag("work_${work.id}")
        compose.onNodeWithTag("work_${work.id}").performClick()
        waitTag("canvas")
        shot("01-photo")

        compose.onNodeWithTag("tool_button").performClick()
        shot("02-tools-with-mosaic")
        compose.onNodeWithTag(Tool.PIXELATE.tag).performClick()

        // 照著底圖的座標拉：電話號碼那一段
        val canvas = compose.onNodeWithTag("canvas").fetchSemanticsNode().size
        val padding = with(compose.density) { CANVAS_PADDING.toPx() }
        val viewport = CanvasFrame(canvas.width.toFloat(), canvas.height.toFloat(), work.width, work.height, padding).viewport(Zoom())
        val from = viewport.toScreen(Point(phone.left, phone.top))
        val to = viewport.toScreen(Point(phone.right, phone.bottom))
        compose.onNodeWithTag("canvas").performTouchInput { swipe(Offset(from.x, from.y), Offset(to.x, to.y), 300) }
        val mosaic = waitForNodes(1).single() as Node.Pixelate
        assertEquals(phone.left, mosaic.from.x, 2f)
        shot("03-mosaic")

        // 換箭頭指著它
        pickTool(Tool.ARROW)
        drag(0.25f, 0.7f, 0.4f, 0.5f)
        waitForNodes(2)
        shot("04-mosaic-arrow")
    }

    @Test
    fun blankCanvasHasNoMosaicTool() {
        openBlank()
        compose.onNodeWithTag("tool_button").performClick()
        compose.onNodeWithTag(Tool.PAN.tag).assertExists()
        compose.onNodeWithTag(Tool.PIXELATE.tag).assertDoesNotExist()
    }
}
