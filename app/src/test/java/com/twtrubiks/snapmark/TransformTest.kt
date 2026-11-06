package com.twtrubiks.snapmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformTest {
    private val page = Page(2000, 1000)
    private val arrow = Node.Arrow("a", Point(600f, 300f), Point(900f, 500f), MarkupColor.PINK, StrokeSize.M)

    private fun assertNear(expected: Point, actual: Point, tolerance: Float = 0.01f) {
        assertTrue("期望 $expected，實際 $actual", distance(expected, actual) <= tolerance)
    }

    @Test
    fun cropMovesEverythingToTheNewCorner() {
        val box = IntBox(500, 200, 1500, 700)
        val cropped = Markup(listOf(arrow)).cropped(box, page).nodes.single() as Node.Arrow

        assertEquals(Point(100f, 100f), cropped.from)
        assertEquals(Point(400f, 300f), cropped.to)
    }

    @Test
    fun cropKeepsThePixelSizesOfWhatWasDrawn() {
        // 長邊 2000 → 1000：之前畫的要兩倍的 scale，畫出來才是原本的像素
        val box = IntBox(500, 200, 1500, 700)
        val text = Node.Text("t", Point(700f, 300f), "這裡", MarkupColor.RED, StrokeSize.L)
        val stamp = Node.Stamp("s", StampKind.HEART, Point(800f, 400f))
        val mosaic = Node.Pixelate("m", Point(600f, 250f), Point(900f, 450f))
        val nodes = Markup(listOf(arrow, text, stamp, mosaic)).cropped(box, page).nodes
        val after = Page(box.width, box.height)

        assertEquals(arrow.lineWidth(2000, 1000), nodes[0].lineWidth(after.width, after.height), 0.001f)
        assertEquals(text.fontSize(2000, 1000), (nodes[1] as Node.Text).fontSize(after.width, after.height), 0.001f)
        assertEquals(stamp.radius(2000, 1000), (nodes[2] as Node.Stamp).radius(after.width, after.height), 0.001f)
        // 馬賽克的格子不會變細（變細的話看得出底下的字）
        assertEquals(mosaicBlock(2000, 1000), (nodes[3] as Node.Pixelate).cell(after.width, after.height))
        // 再裁一次：倍數乘上去
        val again = Markup(nodes).cropped(IntBox(0, 0, 500, 250), after).nodes.first()
        assertEquals(arrow.lineWidth(2000, 1000), again.lineWidth(500, 250), 0.001f)
    }

    @Test
    fun cropDropsWhatIsOutsideAndCutsTheMosaic() {
        val outside = Node.Stamp("out", StampKind.CHECK, Point(1800f, 900f))
        val half = Node.Pixelate("m", Point(1400f, 100f), Point(1700f, 400f))
        val box = IntBox(500, 200, 1500, 700)
        val nodes = Markup(listOf(arrow, outside, half)).cropped(box, page).nodes

        assertEquals(listOf("a", "m"), nodes.map { it.id })
        // 馬賽克只留框裡那一塊：x 1400～1500、y 200～400，往左上移
        val m = nodes[1] as Node.Pixelate
        assertEquals(Point(900f, 0f), m.from)
        assertEquals(Point(1000f, 200f), m.to)
    }

    @Test
    fun aLineCrossingTheCropStays() {
        // 兩端都在框外，但中間穿過框：看得到一段，留著
        val line = Node.Shape("l", ShapeKind.LINE, Point(0f, 450f), Point(2000f, 450f), MarkupColor.BLUE, StrokeSize.S)
        val nodes = Markup(listOf(line)).cropped(IntBox(500, 200, 1500, 700), page).nodes
        assertEquals(Point(-500f, 250f), (nodes.single() as Node.Shape).from)
    }

    @Test
    fun rotateRightTurnsTheLeftEdgeIntoTheTop() {
        // 2000×1000 向右轉 → 1000×2000：(x, y) → (1000 - y, x)
        val turned = Markup(listOf(arrow)).rotated(clockwise = true, page).nodes.single() as Node.Arrow
        assertEquals(Point(700f, 600f), turned.from)
        assertEquals(Point(500f, 900f), turned.to)
        // 大小不變（長邊一樣）
        assertEquals(arrow.scale, turned.scale)
    }

    @Test
    fun rotateLeftTurnsTheTopIntoTheLeftEdge() {
        // 向左轉：(x, y) → (y, 2000 - x)
        val turned = Markup(listOf(arrow)).rotated(clockwise = false, page).nodes.single() as Node.Arrow
        assertEquals(Point(300f, 1400f), turned.from)
        assertEquals(Point(500f, 1100f), turned.to)
    }

    @Test
    fun fourRightTurnsComeBack() {
        val stroke = Node.Stroke("p", listOf(Point(10f, 20f), Point(300f, 40f), Point(500f, 900f)), MarkupColor.GREEN, StrokeSize.S)
        val stamp = Node.Stamp("s", StampKind.HEART, Point(100f, 100f), tail = Point(400f, 300f))
        val box = Node.Shape("r", ShapeKind.RECT, Point(50f, 60f), Point(700f, 800f), MarkupColor.RED, StrokeSize.M)
        val start = Markup(listOf(arrow, stroke, stamp, box))
        var markup = start
        var p = page
        repeat(4) {
            markup = markup.rotated(clockwise = true, p)
            p = Page(p.height, p.width)
        }
        assertEquals(start, markup)
        // 向右再向左也回來
        assertEquals(start, start.rotated(true, page).rotated(false, Page(1000, 2000)))
    }

    @Test
    fun textStaysUprightAroundItsCenter() {
        val text = Node.Text("t", Point(100f, 200f), "這裡！", MarkupColor.RED, StrokeSize.M)
        val before = text.bounds(page)
        val turned = Markup(listOf(text)).rotated(clockwise = true, page).nodes.single() as Node.Text
        val after = turned.bounds(Page(1000, 2000))

        // 字還是橫的：一樣大
        assertEquals(before.width, after.width, 0.01f)
        assertEquals(before.height, after.height, 0.01f)
        // 中心點照著轉
        val c = Point((before.left + before.right) / 2, (before.top + before.bottom) / 2)
        assertNear(Point(1000 - c.y, c.x), Point((after.left + after.right) / 2, (after.top + after.bottom) / 2))
    }
}
