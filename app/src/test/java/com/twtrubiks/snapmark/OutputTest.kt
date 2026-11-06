package com.twtrubiks.snapmark

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class OutputTest {
    private fun work(hasBase: Boolean) = Work("w", hasBase, 1440, 1920, Markup(), 0, 0)

    @Test
    fun photosAreSavedAsJpegAndBlankCanvasesAsPng() {
        assertEquals(OutputFormat.JPEG, OutputFormat.of(work(hasBase = true)))
        assertEquals(OutputFormat.PNG, OutputFormat.of(work(hasBase = false)))
        assertEquals("image/jpeg", OutputFormat.JPEG.mimeType)
        assertEquals("image/png", OutputFormat.PNG.mimeType)
    }

    @Test
    fun outputNamesCarryTheTime() {
        val time = LocalDateTime.of(2026, 1, 5, 9, 3, 7)
        assertEquals("SnapMark-20260105-090307.jpg", outputName(time, OutputFormat.JPEG))
        assertEquals("SnapMark-20260105-090307.png", outputName(time, OutputFormat.PNG))
    }

    @Test
    fun sameSecondGetsANumber() {
        val time = LocalDateTime.of(2026, 11, 5, 15, 30, 0)
        val taken = setOf("SnapMark-20261105-153000.jpg", "SnapMark-20261105-153000-2.jpg")
        assertEquals("SnapMark-20261105-153000-3.jpg", outputName(time, OutputFormat.JPEG, taken::contains))
        // 只有同一種格式的才算撞名
        assertEquals("SnapMark-20261105-153000.png", outputName(time, OutputFormat.PNG, taken::contains))
    }

    @Test
    fun thumbnailsFitIn512() {
        assertEquals(384 to 512, fitWithin(1440, 1920, THUMB_MAX_SIDE))
        assertEquals(512 to 384, fitWithin(4096, 3072, THUMB_MAX_SIDE))
        // 本來就很小的照片：不放大
        assertEquals(300 to 200, fitWithin(300, 200, THUMB_MAX_SIDE))
    }
}
