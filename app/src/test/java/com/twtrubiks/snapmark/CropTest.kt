package com.twtrubiks.snapmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropTest {
    private val start = Crop(1000, 800)
    private val reach = 30f

    private fun Crop.dragFrom(from: Point, to: Point) = press(from, reach).drag(Point((from.x + to.x) / 2, (from.y + to.y) / 2)).drag(to).release()

    @Test
    fun startsWithTheWholePicture() {
        assertTrue(start.isWhole)
        assertEquals(IntBox(0, 0, 1000, 800), start.rect)
    }

    @Test
    fun draggingACornerKeepsTheOppositeOne() {
        val crop = start.dragFrom(Point(10f, 5f), Point(200f, 100f))
        assertEquals(Bounds(200f, 100f, 1000f, 800f), crop.box)
        assertFalse(crop.isWhole)
        // 拉過頭變成反過來也可以
        val flipped = crop.dragFrom(Point(1000f, 800f), Point(100f, 50f))
        assertEquals(Bounds(100f, 50f, 200f, 100f), flipped.box)
    }

    @Test
    fun cornersStayInsideThePicture() {
        val crop = start.dragFrom(Point(1000f, 800f), Point(1500f, -100f))
        // 拉到外面被拉回邊上：右上角那一點是 (1000, 0)，框變成扁的 → 太小，退回去
        assertEquals(start.box, crop.box)
    }

    @Test
    fun draggingInsideMovesTheBoxUntilTheEdge() {
        val small = start.copy(box = Bounds(100f, 100f, 300f, 300f))
        val moved = small.dragFrom(Point(200f, 200f), Point(250f, 260f))
        assertEquals(Bounds(150f, 160f, 350f, 360f), moved.box)
        // 拖到外面：停在邊上，大小不變
        val edge = small.dragFrom(Point(200f, 200f), Point(-500f, 2000f))
        assertEquals(Bounds(0f, 600f, 200f, 800f), edge.box)
    }

    @Test
    fun draggingOutsideStartsANewBox() {
        val small = start.copy(box = Bounds(100f, 100f, 300f, 300f))
        val fresh = small.dragFrom(Point(500f, 400f), Point(700f, 600f))
        assertEquals(Bounds(500f, 400f, 700f, 600f), fresh.box)
    }

    @Test
    fun tooSmallGoesBack() {
        val small = start.copy(box = Bounds(100f, 100f, 300f, 300f))
        // 在外面點一下（拉出來的框是一個點）
        assertEquals(small.box, small.press(Point(600f, 600f), reach).release().box)
        // 角拉到比 16 還小
        assertEquals(small.box, small.dragFrom(Point(300f, 300f), Point(110f, 110f)).box)
        assertNull(small.dragFrom(Point(300f, 300f), Point(110f, 110f)).grab)
    }

    @Test
    fun secondFingerCancelsTheDrag() {
        val dragging = start.press(Point(0f, 0f), reach).drag(Point(300f, 300f))
        assertEquals(Bounds(300f, 300f, 1000f, 800f), dragging.box)
        val cancelled = dragging.cancel()
        assertEquals(start.box, cancelled.box)
        assertNull(cancelled.grab)
    }

    @Test
    fun rectSnapsToPixels() {
        val crop = start.copy(box = Bounds(10.4f, 20.6f, 500.5f, 600.2f))
        assertEquals(IntBox(10, 21, 501, 600), crop.rect)
    }
}
