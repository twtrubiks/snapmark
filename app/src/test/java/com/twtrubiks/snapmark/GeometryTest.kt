package com.twtrubiks.snapmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeometryTest {
    private fun assertNear(expected: Point, actual: Point, tolerance: Float = 0.01f) {
        assertTrue("期望 $expected，實際 $actual", distance(expected, actual) <= tolerance)
    }

    /** 點到 from→to 這條直線的有號距離（左邊為正）。 */
    private fun side(from: Point, to: Point, p: Point): Float {
        val length = distance(from, to)
        return ((to.x - from.x) * (p.y - from.y) - (to.y - from.y) * (p.x - from.x)) / length
    }

    /** 點投影到 from→to 上離 from 多遠。 */
    private fun along(from: Point, to: Point, p: Point): Float {
        val length = distance(from, to)
        return ((to.x - from.x) * (p.x - from.x) + (to.y - from.y) * (p.y - from.y)) / length
    }

    @Test
    fun strokeWidthGrowsWithSizeAndFollowsTheLongSide() {
        val widths = StrokeSize.entries.map { it.width(1440, 1920) }
        assertEquals(widths.sorted(), widths)
        assertEquals(widths.toSet().size, widths.size)
        // 直的、橫的同一張圖一樣粗；兩倍大的圖兩倍粗
        assertEquals(StrokeSize.M.width(1440, 1920), StrokeSize.M.width(1920, 1440), 0f)
        assertEquals(StrokeSize.M.width(1440, 1920) * 2, StrokeSize.M.width(2880, 3840), 0.001f)
    }

    @Test
    fun textAndStampFollowTheLongSide() {
        val sizes = StrokeSize.entries.map { it.textSize(1440, 1920) }
        assertEquals(sizes.sorted(), sizes)
        assertEquals(StrokeSize.M.textSize(1440, 1920) * 2, StrokeSize.M.textSize(2880, 3840), 0.001f)
        assertEquals(stampRadius(StrokeSize.M, 1440, 1920), stampRadius(StrokeSize.M, 1920, 1440), 0f)
        assertEquals(stampRadius(StrokeSize.M, 1440, 1920) * 2, stampRadius(StrokeSize.M, 2880, 3840), 0.001f)
        val stamps = StrokeSize.entries.map { stampRadius(it, 1440, 1920) }
        assertEquals(stamps.sorted(), stamps)
    }

    @Test
    fun arrowPointsAtTheEndAndIsSymmetric() {
        val from = Point(100f, 500f)
        val to = Point(700f, 200f)
        val outline = arrowOutline(from, to, width = 10f)

        assertEquals(7, outline.size)
        assertNear(to, outline[3])
        // 左右對稱：第 i 點與倒數第 i 點離中線一樣遠、方向相反，沿著箭頭的位置一樣
        for (i in 0..2) {
            val left = outline[i]
            val right = outline[6 - i]
            assertEquals(side(from, to, left), -side(from, to, right), 0.01f)
            assertEquals(along(from, to, left), along(from, to, right), 0.01f)
            assertTrue("左邊的點要在左邊：$left", side(from, to, left) > 0)
        }
        // 尖端以外，沒有一點超過尖端
        outline.forEach { assertTrue(along(from, to, it) <= distance(from, to) + 0.01f) }
    }

    @Test
    fun arrowTailIsThinAndHeadIsWide() {
        val from = Point(0f, 0f)
        val to = Point(1000f, 0f)
        val outline = arrowOutline(from, to, width = 10f)
        val tail = abs(outline[0].y)
        val neck = abs(outline[1].y)
        val wing = abs(outline[2].y)

        assertTrue("尾巴 $tail 要比頸部 $neck 細", tail < neck)
        assertTrue("頸部 $neck 要比翼 $wing 窄", neck < wing)
        // 尾巴在起點
        assertEquals(0f, outline[0].x, 0.01f)
        // 兩翼往後掠：翼的根部比頸部離尖端遠
        assertTrue(outline[2].x < outline[1].x)
    }

    @Test
    fun shortArrowShrinksSoItIsNotAllHead() {
        val from = Point(0f, 0f)
        val long = arrowOutline(from, Point(1000f, 0f), width = 10f)
        val short = arrowOutline(from, Point(30f, 0f), width = 10f)

        val longHead = 1000f - long[2].x
        val shortHead = 30f - short[2].x
        assertTrue("短箭頭的頭 $shortHead 要比長箭頭的頭 $longHead 短", shortHead < longHead)
        assertTrue("頭最多佔 60%：$shortHead", shortHead <= 30f * 0.6f + 0.01f)
        assertTrue(abs(short[2].y) < abs(long[2].y))
    }

    @Test
    fun arrowWithoutLengthHasNoShape() {
        assertTrue(arrowOutline(Point(5f, 5f), Point(5f, 5f), width = 10f).isEmpty())
    }

    // 1080×2000 的畫面放 1440×1920 的畫布，四邊留 20
    private val frame = CanvasFrame(viewWidth = 1080f, viewHeight = 2000f, imageWidth = 1440, imageHeight = 1920, padding = 20f)

    @Test
    fun fitPutsTheWholeImageInTheMiddle() {
        val viewport = frame.viewport(Zoom())

        assertEquals((1080f - 40f) / 1440f, viewport.scale, 0.0001f)
        val topLeft = viewport.toScreen(Point(0f, 0f))
        val bottomRight = viewport.toScreen(Point(1440f, 1920f))
        assertEquals(20f, topLeft.x, 0.01f)
        assertEquals(1060f, bottomRight.x, 0.01f)
        // 上下置中
        assertEquals(2000f - bottomRight.y, topLeft.y, 0.01f)
    }

    @Test
    fun screenAndImageCoordinatesRoundTrip() {
        val viewport = frame.viewport(Zoom(2.5f, Point(400f, 900f)))
        val p = Point(123f, 456f)

        assertNear(p, viewport.toImage(viewport.toScreen(p)))
        // 畫面正中間就是 center
        assertNear(Point(400f, 900f), viewport.toImage(Point(540f, 1000f)))
    }

    @Test
    fun pinchKeepsThePointUnderTheFingers() {
        val focus = Point(300f, 700f)
        val before = frame.viewport(Zoom()).toImage(focus)

        val zoomed = frame.transform(Zoom(), pan = Point(0f, 0f), factor = 2f, focus = focus)

        assertEquals(2f, zoomed.zoom, 0.0001f)
        assertNear(before, frame.viewport(zoomed).toImage(focus))
    }

    @Test
    fun panMovesTheImageWithTheFingers() {
        val zoom = Zoom(3f, Point(720f, 960f))
        val finger = Point(500f, 1000f)
        val under = frame.viewport(zoom).toImage(finger)

        val moved = frame.transform(zoom, pan = Point(-100f, 50f), factor = 1f, focus = finger)

        assertNear(under, frame.viewport(moved).toImage(finger + Point(-100f, 50f)))
    }

    @Test
    fun zoomStaysBetweenFitAndMax() {
        val out = frame.transform(Zoom(), Point(0f, 0f), factor = 0.3f, focus = Point(540f, 1000f))
        assertEquals(1f, out.zoom, 0f)

        val deep = frame.transform(Zoom(6f), Point(0f, 0f), factor = 5f, focus = Point(540f, 1000f))
        assertEquals(Zoom.MAX, deep.zoom, 0f)
    }

    @Test
    fun cannotDragTheImageOutOfView() {
        // 剛好放進去時拖不動
        val fit = frame.transform(Zoom(), pan = Point(400f, -300f), factor = 1f, focus = Point(540f, 1000f))
        assertNear(Point(720f, 960f), frame.viewport(fit).toImage(Point(540f, 1000f)))

        // 放大後拖到最左上：圖的左上角停在留白的地方，不會再往右下跑
        val zoomed = frame.transform(Zoom(4f), pan = Point(100_000f, 100_000f), factor = 1f, focus = Point(540f, 1000f))
        val topLeft = frame.viewport(zoomed).toScreen(Point(0f, 0f))
        assertEquals(20f, topLeft.x, 0.01f)
        assertEquals(20f, topLeft.y, 0.01f)
    }

    @Test
    fun zoomPositionSurvivesRotation() {
        // 焦點靠近中間：太靠邊的話，橫的畫面比較寬，會被「不能拖出畫面」拉回來
        val zoom = frame.transform(Zoom(), Point(0f, 0f), factor = 3f, focus = Point(500f, 950f))
        val center = frame.viewport(zoom).toImage(Point(540f, 1000f))

        // 轉成橫的：畫面正中間還是同一點
        val landscape = CanvasFrame(2000f, 1080f, 1440, 1920, padding = 20f)
        assertNear(center, landscape.viewport(zoom).toImage(Point(1000f, 540f)))
    }

    @Test
    fun revealLeavesVisiblePointsAlone() {
        val zoom = Zoom(2f, Point(720f, 960f))
        assertEquals(zoom, frame.reveal(zoom, Point(720f, 960f), margin = 100f))
    }

    @Test
    fun revealPansSoThePointIsAboveTheKeyboard() {
        // 放大兩倍時鍵盤跳出來：畫面只剩上面 900 高，原本靠下面的那一點（畫布的 y=1700）被擋住了，要拉進來
        val short = CanvasFrame(1080f, 900f, 1440, 1920, padding = 20f)
        val zoom = Zoom(2f, Point(720f, 960f))
        val target = Point(400f, 1700f)
        assertTrue(short.viewport(zoom).toScreen(target).y > 900f)

        val revealed = short.reveal(zoom, target, margin = 100f)

        val y = short.viewport(revealed).toScreen(target).y
        assertTrue("拉進來之後在 $y", y in 100f..800f)
        assertEquals(2f, revealed.zoom, 0f)
    }

    @Test
    fun revealStopsAtTheImageEdge() {
        // 點在畫布外面（右下角之外）：最多拉到畫布的邊，不會拉出畫面
        val zoomed = Zoom(3f, Point(720f, 960f))
        val revealed = frame.reveal(zoomed, Point(5000f, 5000f), margin = 100f)
        assertEquals(frame.clamp(revealed), revealed)
        val bottomRight = frame.viewport(revealed).toScreen(Point(1440f, 1920f))
        assertEquals(1060f, bottomRight.x, 0.01f)
        assertEquals(1980f, bottomRight.y, 0.01f)
    }

    @Test
    fun fitWithinShrinksTheLongSideOnly() {
        // 1200 萬畫素橫拍：長邊縮到 4096，比例不變
        assertEquals(4096 to 3072, fitWithin(4032 * 2, 3024 * 2, BASE_MAX_SIDE))
        // 直拍
        assertEquals(2304 to 4096, fitWithin(3456, 6144, BASE_MAX_SIDE))
        // 本來就在上限以內：不放大
        assertEquals(1080 to 2400, fitWithin(1080, 2400, BASE_MAX_SIDE))
        assertEquals(4096 to 4096, fitWithin(4096, 4096, BASE_MAX_SIDE))
        // 很細長的圖：短邊至少 1
        assertEquals(4096 to 1, fitWithin(100_000, 3, BASE_MAX_SIDE))
    }

    @Test
    fun mosaicBlocksFollowTheLongSide() {
        assertEquals(85, mosaicBlock(4096, 3072))
        assertEquals(85, mosaicBlock(3072, 4096))
        assertEquals(40, mosaicBlock(1440, 1920))
        // 很小的圖也至少 4 像素一格
        assertEquals(4, mosaicBlock(100, 80))
        // 不滿一格的也算一格：85 × 49 = 4165 ≥ 4096、85 × 37 = 3145 ≥ 3072
        assertEquals(49 to 37, mosaicGrid(4096, 3072))
        assertEquals(36 to 48, mosaicGrid(1440, 1920))
    }
}
