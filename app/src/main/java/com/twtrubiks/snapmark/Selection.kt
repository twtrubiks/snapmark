package com.twtrubiks.snapmark

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/*
 * 選取用到的幾何，純 Kotlin（JVM 測試 SelectionTest）：點到哪一個物件、選取框的範圍、控制點、移動、拉控制點改大小。
 * 座標都是底圖的像素。[reach] 是手指點得到的距離（螢幕上大約 20dp 換算成底圖像素）。
 */

/**
 * 量一段字有多大（像素）：字型在 Android 那一層，這裡只拿結果。
 * 畫面上用 [AndroidTextMeasure]（跟 [MarkupPainter] 同一套排版），JVM 測試用 [APPROX]。
 */
fun interface TextMeasure {
    /** 回傳（寬, 高）。只在 `\n` 換行。 */
    fun measure(text: String, textSize: Float): Pair<Float, Float>

    companion object {
        /** 粗略估計：中日韓文字一個字一個字寬，其他半個字寬。 */
        val APPROX = TextMeasure { text, textSize ->
            val lines = text.split('\n')
            val widest = lines.maxOf { line -> line.sumOf { c -> if (c.code >= 0x2E80) 1.0 else 0.55 } }
            (widest * textSize).toFloat() to lines.size * textSize * 1.17f
        }
    }
}

/*
 * 物件實際幾個像素：粗細（[StrokeSize]）照畫布的長邊算，再乘上物件的 [Node.scale]（裁切之前畫的，維持原本的大小）。
 * 畫的（MarkupPainter）、點到哪一個、選取框都用這幾個，不要直接呼叫 StrokeSize.width。
 */

/** 箭頭、形狀、筆的線有多粗（螢光筆再乘 [HIGHLIGHTER_WIDTH]）。 */
fun Node.lineWidth(canvasWidth: Int, canvasHeight: Int): Float = when (this) {
    is Node.Arrow -> size.width(canvasWidth, canvasHeight) * scale
    is Node.Shape -> size.width(canvasWidth, canvasHeight) * scale
    is Node.Stroke -> size.width(canvasWidth, canvasHeight) * scale * (if (highlighter) HIGHLIGHTER_WIDTH else 1f)
    is Node.Text, is Node.Stamp, is Node.Pixelate -> 0f
}

/** 文字的字級。 */
fun Node.Text.fontSize(canvasWidth: Int, canvasHeight: Int) = size.textSize(canvasWidth, canvasHeight) * scale

/** 印章的半徑。 */
fun Node.Stamp.radius(canvasWidth: Int, canvasHeight: Int) = stampRadius(size, canvasWidth, canvasHeight) * scale

/**
 * 馬賽克一格幾個像素：裁切之前畫的維持原本的格子（裁切後長邊變短，不能變細到看得出底下的字）。
 * 長邊 × [Node.scale] 就是畫的時候的長邊，照 [mosaicBlock] 一樣算（先乘再取整數，不會少一個像素）。
 */
fun Node.Pixelate.cell(canvasWidth: Int, canvasHeight: Int): Int =
    if (scale == 1f) mosaicBlock(canvasWidth, canvasHeight) else max(4, (max(canvasWidth, canvasHeight) * scale / 48f + 1e-3f).toInt())

/** 印章的字（[Node.Stamp.label]）的大小：跟著印章大小。 */
fun Node.Stamp.labelTextSize(canvasWidth: Int, canvasHeight: Int) = size.textSize(canvasWidth, canvasHeight) * scale

/** 印章的字放在印章右邊、上下置中；回傳字的左上角。 */
fun Node.Stamp.labelAt(text: String, canvasWidth: Int, canvasHeight: Int, measure: TextMeasure): Point {
    val r = radius(canvasWidth, canvasHeight)
    val (_, h) = measure.measure(text, labelTextSize(canvasWidth, canvasHeight))
    return Point(at.x + r * 1.2f, at.y - h / 2)
}

/** 加尾巴時尾巴先指到的地方：印章右下方。 */
fun Node.Stamp.defaultTail(canvasWidth: Int, canvasHeight: Int): Point {
    val r = radius(canvasWidth, canvasHeight)
    return Point(at.x + r * 2.5f, at.y + r * 2.5f)
}

/** 選取框、命中判斷要用到的畫布資訊。 */
data class Page(val width: Int, val height: Int, val measure: TextMeasure = TextMeasure.APPROX)

/** 一個物件在畫面上佔的範圍（含線的粗細），選取框畫在這外面。 */
fun Node.bounds(page: Page): Bounds = when (this) {
    is Node.Arrow -> arrowOutline(from, to, lineWidth(page.width, page.height)).ifEmpty { listOf(from) }.let(Bounds::around)
    is Node.Shape -> {
        val half = lineWidth(page.width, page.height) / 2
        Bounds.of(from, to).inset(-half)
    }
    is Node.Stroke -> {
        val w = lineWidth(page.width, page.height)
        Bounds.around(points).inset(-w / 2)
    }
    is Node.Text -> {
        val (w, h) = page.measure.measure(text, fontSize(page.width, page.height))
        Bounds(at.x, at.y, at.x + w, at.y + h)
    }
    is Node.Stamp -> {
        val r = radius(page.width, page.height)
        var b = Bounds(at.x - r, at.y - r, at.x + r, at.y + r)
        tail?.let { b = b.union(Bounds.of(it, it)) }
        label?.let { b = b.union(labelBounds(it, page)) }
        b
    }
    is Node.Pixelate -> Bounds.of(from, to)
}

private fun Node.Stamp.labelBounds(text: String, page: Page): Bounds {
    val at = labelAt(text, page.width, page.height, page.measure)
    val (w, h) = page.measure.measure(text, labelTextSize(page.width, page.height))
    return Bounds(at.x, at.y, at.x + w, at.y + h)
}

/**
 * 點 [p] 有沒有點到這個物件。線條類的（箭頭、形狀的框、筆）要點在線上（[reach] 以內），
 * 方框、橢圓的裡面不算（不然畫在大框裡面的東西會點不到）；文字、印章點在範圍裡就算。
 */
fun Node.hit(p: Point, reach: Float, page: Page): Boolean = when (this) {
    is Node.Arrow -> distanceToSegment(p, from, to) <= reach + lineWidth(page.width, page.height) * HEAD_REACH
    is Node.Shape -> {
        val half = lineWidth(page.width, page.height) / 2
        when (kind) {
            ShapeKind.LINE -> distanceToSegment(p, from, to) <= reach + half
            ShapeKind.ELLIPSE -> distanceToEllipse(p, Bounds.of(from, to)) <= reach + half
            ShapeKind.RECT, ShapeKind.ROUND_RECT -> distanceToBorder(p, Bounds.of(from, to)) <= reach + half
        }
    }
    is Node.Stroke -> {
        val w = lineWidth(page.width, page.height)
        if (points.size == 1) {
            distance(p, points[0]) <= reach + w / 2
        } else {
            points.zipWithNext().any { (a, b) -> distanceToSegment(p, a, b) <= reach + w / 2 }
        }
    }
    is Node.Text -> p in bounds(page).inset(-reach)
    is Node.Stamp -> {
        val r = radius(page.width, page.height)
        distance(p, at) <= r + reach ||
            tail?.let { distanceToSegment(p, at, it) <= reach } == true ||
            label?.let { p in labelBounds(it, page).inset(-reach) } == true
    }
    is Node.Pixelate -> p in Bounds.of(from, to).inset(-reach)
}

// 箭頭的頭比線粗：點在頭的兩翼上也要算
private const val HEAD_REACH = 2.3f

/** 點到方框的邊最近的距離（裡面、外面都一樣）。 */
private fun distanceToBorder(p: Point, b: Bounds): Float {
    val dx = max(max(b.left - p.x, 0f), p.x - b.right)
    val dy = max(max(b.top - p.y, 0f), p.y - b.bottom)
    if (dx > 0f || dy > 0f) return hypot(dx, dy)
    // 在裡面：到最近那一邊
    return minOf(p.x - b.left, b.right - p.x, p.y - b.top, b.bottom - p.y)
}

/** 點到橢圓的邊大概的距離：沿著從圓心出發、經過 p 的那條線量。 */
private fun distanceToEllipse(p: Point, b: Bounds): Float {
    val a = b.width / 2
    val c = b.height / 2
    val cx = b.left + a
    val cy = b.top + c
    if (a < 1e-3f || c < 1e-3f) return distanceToSegment(p, Point(b.left, b.top), Point(b.right, b.bottom))
    val dx = p.x - cx
    val dy = p.y - cy
    val d = hypot(dx, dy)
    if (d < 1e-3f) return minOf(a, c)
    // 這個方向上，圓心到橢圓邊的距離
    val k = 1f / kotlin.math.sqrt((dx * dx) / (a * a) + (dy * dy) / (c * c))
    return abs(d - d * k)
}

/** 最上面（最後畫上去）、點得到的那一個。 */
fun List<Node>.topAt(p: Point, reach: Float, page: Page): Node? = lastOrNull { it.hit(p, reach, page) }

/**
 * 控制點，拉了可以改大小／方向：箭頭、直線是兩端；方框、圓角方框、橢圓、筆、馬賽克是四個角（左上、右上、右下、左下）；
 * 印章有尾巴的話是尾巴的端點。文字沒有（大小用粗細改）。
 */
fun Node.handles(): List<Point> = when (this) {
    is Node.Arrow -> listOf(from, to)
    is Node.Shape -> if (kind == ShapeKind.LINE) listOf(from, to) else Bounds.of(from, to).corners()
    is Node.Stroke -> Bounds.around(points).corners()
    is Node.Pixelate -> Bounds.of(from, to).corners()
    is Node.Stamp -> listOfNotNull(tail)
    is Node.Text -> emptyList()
}

private fun Bounds.corners() = listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))

/** 整個搬動 [d]。 */
fun Node.moved(d: Point): Node = when (this) {
    is Node.Arrow -> copy(from = from + d, to = to + d)
    is Node.Shape -> copy(from = from + d, to = to + d)
    is Node.Stroke -> copy(points = points.map { it + d })
    is Node.Text -> copy(at = at + d)
    is Node.Stamp -> copy(at = at + d, tail = tail?.plus(d))
    is Node.Pixelate -> copy(from = from + d, to = to + d)
}

/** 第 [index] 個控制點（見 [handles]）拉到 [p]。四個角的：對角那一點不動。 */
fun Node.withHandle(index: Int, p: Point): Node = when (this) {
    is Node.Arrow -> if (index == 0) copy(from = p) else copy(to = p)
    is Node.Shape -> if (kind == ShapeKind.LINE) {
        if (index == 0) copy(from = p) else copy(to = p)
    } else {
        copy(from = Bounds.of(from, to).opposite(index), to = p)
    }
    is Node.Pixelate -> copy(from = Bounds.of(from, to).opposite(index), to = p)
    is Node.Stroke -> {
        // 以對角為基準把每一點等比例拉伸；那個方向本來沒有寬度（一條直的線）就不拉
        val box = Bounds.around(points)
        val fixed = box.opposite(index)
        val corner = box.corners()[index]
        fun scale(moved: Float, from: Float, base: Float) = if (abs(from - base) < 1e-3f) 1f else (moved - base) / (from - base)
        val sx = scale(p.x, corner.x, fixed.x)
        val sy = scale(p.y, corner.y, fixed.y)
        copy(points = points.map { Point(fixed.x + (it.x - fixed.x) * sx, fixed.y + (it.y - fixed.y) * sy) })
    }
    is Node.Stamp -> copy(tail = p)
    is Node.Text -> this
}

private fun Bounds.opposite(index: Int) = corners()[(index + 2) % 4]

/** 選到之後改顏色：印章、馬賽克沒有顏色，不變。 */
fun Node.recolored(color: MarkupColor): Node = when (this) {
    is Node.Arrow -> copy(color = color)
    is Node.Shape -> copy(color = color)
    is Node.Stroke -> copy(color = color)
    is Node.Text -> copy(color = color)
    is Node.Stamp, is Node.Pixelate -> this
}

/** 選到之後改粗細：印章是改大小；馬賽克沒有粗細，不變。改了就照現在的長邊算（[Node.scale] 回到 1）。 */
fun Node.resized(size: StrokeSize): Node = when (this) {
    is Node.Arrow -> copy(size = size, scale = 1f)
    is Node.Shape -> copy(size = size, scale = 1f)
    is Node.Stroke -> copy(size = size, scale = 1f)
    is Node.Text -> copy(size = size, scale = 1f)
    is Node.Stamp -> copy(size = size, scale = 1f)
    is Node.Pixelate -> this
}

/** 乘上 [factor] 倍的 [Node.scale]（裁切時，維持原本的像素大小）。 */
fun Node.rescaled(factor: Float): Node = when (this) {
    is Node.Arrow -> copy(scale = scale * factor)
    is Node.Shape -> copy(scale = scale * factor)
    is Node.Stroke -> copy(scale = scale * factor)
    is Node.Text -> copy(scale = scale * factor)
    is Node.Stamp -> copy(scale = scale * factor)
    is Node.Pixelate -> copy(scale = scale * factor)
}
