package com.twtrubiks.snapmark

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * 畫標註用到的幾何，純 Kotlin（JVM 測試）。座標都是底圖的像素，除非名字裡有 screen。
 */

operator fun Point.plus(other: Point) = Point(x + other.x, y + other.y)

operator fun Point.minus(other: Point) = Point(x - other.x, y - other.y)

operator fun Point.times(k: Float) = Point(x * k, y * k)

fun distance(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)

/** [p] 到線段 [a]–[b] 最近的距離。 */
fun distanceToSegment(p: Point, a: Point, b: Point): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lengthSq = dx * dx + dy * dy
    if (lengthSq == 0f) return distance(p, a)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSq).coerceIn(0f, 1f)
    return distance(p, Point(a.x + t * dx, a.y + t * dy))
}

/** 一個長方形範圍（底圖像素）。 */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    operator fun contains(p: Point) = p.x in left..right && p.y in top..bottom

    fun inset(d: Float) = Bounds(left + d, top + d, right - d, bottom - d)

    fun union(other: Bounds) = Bounds(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    /** 兩個範圍有沒有重疊（只碰到邊不算）。 */
    fun intersects(other: Bounds) = left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** 重疊的那一塊；沒有重疊回 null。 */
    fun intersect(other: Bounds): Bounds? =
        if (intersects(other)) Bounds(max(left, other.left), max(top, other.top), min(right, other.right), min(bottom, other.bottom)) else null

    companion object {
        /** [a]、[b] 是對角的兩點，順序不拘。 */
        fun of(a: Point, b: Point) = Bounds(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

        fun around(points: List<Point>) = Bounds(
            points.minOf { it.x },
            points.minOf { it.y },
            points.maxOf { it.x },
            points.maxOf { it.y },
        )
    }
}

/**
 * 一種粗細實際是幾個像素：跟著畫布的長邊走（長邊的千分之幾），
 * 所以 1200 萬畫素的照片跟空白畫布上，「中」看起來一樣粗。
 */
fun StrokeSize.width(canvasWidth: Int, canvasHeight: Int): Float {
    val unit = max(canvasWidth, canvasHeight) / 1000f
    return unit * when (this) {
        StrokeSize.XS -> 4f
        StrokeSize.S -> 6f
        StrokeSize.M -> 9f
        StrokeSize.L -> 13f
        StrokeSize.XL -> 18f
    }
}

/** 螢光筆比同一種粗細的筆粗這麼多倍（半透明、比較粗）。 */
const val HIGHLIGHTER_WIDTH = 3f

/** 文字的字級（像素），跟粗細一樣跟著畫布的長邊走。 */
fun StrokeSize.textSize(canvasWidth: Int, canvasHeight: Int): Float {
    val unit = max(canvasWidth, canvasHeight) / 1000f
    return unit * when (this) {
        StrokeSize.XS -> 24f
        StrokeSize.S -> 34f
        StrokeSize.M -> 46f
        StrokeSize.L -> 62f
        StrokeSize.XL -> 84f
    }
}

/** 印章的半徑（像素）：「中」是畫布長邊的 4%。 */
fun stampRadius(size: StrokeSize, canvasWidth: Int, canvasHeight: Int): Float {
    val scale = when (size) {
        StrokeSize.XS -> 0.6f
        StrokeSize.S -> 0.8f
        StrokeSize.M -> 1f
        StrokeSize.L -> 1.3f
        StrokeSize.XL -> 1.7f
    }
    return max(canvasWidth, canvasHeight) * 0.04f * scale
}

/** 箭頭的比例，以粗細 w 為單位。 */
private const val HEAD_LENGTH = 4.2f
private const val HEAD_HALF_WIDTH = 2.3f
private const val NECK_HALF_WIDTH = 0.8f
private const val TAIL_HALF_WIDTH = 0.15f

// 兩翼往後掠：頸部在頭長的 80% 處，比兩翼的根部靠近箭頭尖端
private const val NECK_AT = 0.8f

// 頭最多佔整支箭頭的 60%：短箭頭整支等比縮小，不會只剩一個頭
private const val MAX_HEAD_RATIO = 0.6f

/**
 * 箭頭的外形：尾巴細、頭粗的實心多邊形（不是一條線加三角形），尖端在 [to]。
 * 回傳 7 個點，依序是：尾巴左、頸部左、翼左、尖端、翼右、頸部右、尾巴右。
 * [from] 跟 [to] 重疊時沒有方向，回空的。
 */
fun arrowOutline(from: Point, to: Point, width: Float): List<Point> {
    val length = distance(from, to)
    if (length < 1e-3f) return emptyList()
    // 沿著箭頭方向的單位向量與它的法向量
    val ux = (to.x - from.x) / length
    val uy = (to.y - from.y) / length
    val nx = -uy
    val ny = ux
    val k = min(1f, length * MAX_HEAD_RATIO / (HEAD_LENGTH * width))
    val w = width * k
    val headLength = HEAD_LENGTH * w

    fun at(back: Float, side: Float) = Point(to.x - ux * back + nx * side, to.y - uy * back + ny * side)

    val tail = TAIL_HALF_WIDTH * w
    return listOf(
        Point(from.x + nx * tail, from.y + ny * tail),
        at(headLength * NECK_AT, NECK_HALF_WIDTH * w),
        at(headLength, HEAD_HALF_WIDTH * w),
        to,
        at(headLength, -HEAD_HALF_WIDTH * w),
        at(headLength * NECK_AT, -NECK_HALF_WIDTH * w),
        Point(from.x - nx * tail, from.y - ny * tail),
    )
}

/**
 * 使用者的縮放與平移，跟螢幕大小無關（轉螢幕之後還在同一個位置）：
 * [zoom] 是「整張圖剛好放進畫面」的幾倍（1 = 剛好放進去），[center] 是畫面正中間對到底圖的哪一點（null = 底圖正中間）。
 */
data class Zoom(val zoom: Float = 1f, val center: Point? = null) {
    companion object {
        const val MAX = 8f
    }
}

/** 底圖座標 ↔ 螢幕座標：screen = image × [scale] + offset。 */
data class Viewport(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun toScreen(p: Point) = Point(p.x * scale + offsetX, p.y * scale + offsetY)

    fun toImage(screen: Point) = Point((screen.x - offsetX) / scale, (screen.y - offsetY) / scale)
}

/**
 * 畫布在螢幕上的那一塊：[viewWidth]×[viewHeight] 的區域裡放一張 [imageWidth]×[imageHeight] 的圖，四邊至少留 [padding]。
 * 都是螢幕像素（[padding] 也是）。
 */
data class CanvasFrame(
    val viewWidth: Float,
    val viewHeight: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val padding: Float = 0f,
) {
    private val innerWidth get() = max(1f, viewWidth - 2 * padding)
    private val innerHeight get() = max(1f, viewHeight - 2 * padding)

    /** zoom = 1 時的比例：整張圖剛好放進去。 */
    val fitScale: Float get() = min(innerWidth / imageWidth, innerHeight / imageHeight)

    fun viewport(zoom: Zoom): Viewport {
        val z = clamp(zoom)
        val scale = fitScale * z.zoom
        val center = z.center ?: Point(imageWidth / 2f, imageHeight / 2f)
        return Viewport(scale, viewWidth / 2 - center.x * scale, viewHeight / 2 - center.y * scale)
    }

    /**
     * 兩指縮放平移：以螢幕上的 [focus] 為中心放大 [factor] 倍，再移動 [pan]（螢幕像素）。
     * 手指底下的那一點跟著手指走。
     */
    fun transform(zoom: Zoom, pan: Point, factor: Float, focus: Point): Zoom {
        val before = viewport(zoom)
        val newZoom = (clamp(zoom).zoom * factor).coerceIn(1f, Zoom.MAX)
        val scale = fitScale * newZoom
        // 縮放前 focus 底下的那一點，縮放平移之後要在 focus + pan
        val anchor = before.toImage(focus)
        val target = focus + pan
        val offsetX = target.x - anchor.x * scale
        val offsetY = target.y - anchor.y * scale
        val center = Viewport(scale, offsetX, offsetY).toImage(Point(viewWidth / 2, viewHeight / 2))
        return clamp(Zoom(newZoom, center))
    }

    /**
     * 平移到讓底圖上的 [point] 在畫面裡、離邊緣至少 [margin]（螢幕像素），縮放不變。
     * 已經看得到就不動。用在打字的時候：鍵盤跳出來畫面變矮，文字框不能被擋住。
     */
    fun reveal(zoom: Zoom, point: Point, margin: Float): Zoom {
        val screen = viewport(zoom).toScreen(point)
        fun push(at: Float, size: Float) = when {
            size < 2 * margin -> size / 2 - at
            at < margin -> margin - at
            at > size - margin -> size - margin - at
            else -> 0f
        }
        val dx = push(screen.x, viewWidth)
        val dy = push(screen.y, viewHeight)
        if (dx == 0f && dy == 0f) return zoom
        return transform(zoom, pan = Point(dx, dy), factor = 1f, focus = Point(viewWidth / 2, viewHeight / 2))
    }

    /**
     * 不讓圖被拖出畫面：圖比畫面小的方向固定在正中間；比畫面大的方向，邊緣最多拉到留白的地方。
     */
    fun clamp(zoom: Zoom): Zoom {
        val z = zoom.zoom.coerceIn(1f, Zoom.MAX)
        val center = zoom.center ?: return Zoom(z, null)
        val scale = fitScale * z
        fun axis(c: Float, image: Int, inner: Float): Float {
            val half = inner / 2 / scale
            return if (image <= 2 * half) image / 2f else c.coerceIn(half, image - half)
        }
        return Zoom(z, Point(axis(center.x, imageWidth, innerWidth), axis(center.y, imageHeight, innerHeight)))
    }
}

/** 相機拍的、照片選的底圖：長邊最多這麼多像素（見 CLAUDE.md「底圖」）。 */
const val BASE_MAX_SIDE = 4096

/**
 * [width]×[height] 的圖放進長邊 [maxSide] 以內（等比例縮小，不放大），回傳（寬, 高），每邊至少 1。
 * 底圖縮小、作品分頁的縮圖都用它。
 */
fun fitWithin(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxSide > 0) { "大小不對：$width×$height，上限 $maxSide" }
    val long = max(width, height)
    if (long <= maxSide) return width to height
    val scale = maxSide.toDouble() / long
    fun side(v: Int) = max(1, kotlin.math.round(v * scale).toInt())
    return side(width) to side(height)
}

/**
 * 馬賽克一格是幾個像素：跟著底圖的長邊（長邊的 1/48，至少 4）。
 * 格子對齊整張底圖（從左上角開始切），同一張圖上的馬賽克格子都一樣大、對得齊。
 */
fun mosaicBlock(width: Int, height: Int): Int = max(4, max(width, height) / 48)

/** 整張底圖切成馬賽克格子是幾格寬、幾格高（最右邊、最下面不滿一格的也算一格）。 */
fun mosaicGrid(width: Int, height: Int): Pair<Int, Int> {
    val block = mosaicBlock(width, height)
    return (width + block - 1) / block to (height + block - 1) / block
}
