package com.twtrubiks.snapmark

import kotlin.math.min
import kotlin.math.roundToInt

/** 這次手勢抓著裁切框的哪裡。[before] 是按下之前的框：拉得太小時退回去。 */
sealed interface CropGrab {
    val before: Bounds

    /** 拉一個角，對角 [fixed] 不動。 */
    data class Corner(val fixed: Point, override val before: Bounds) : CropGrab

    /** 按在框裡面：整個框拖著走。 */
    data class Move(val start: Point, override val before: Bounds) : CropGrab

    /** 按在框外面：從 [start] 重新拉一個框。 */
    data class New(val start: Point, override val before: Bounds) : CropGrab
}

/**
 * 裁切畫面上的框，純 Kotlin（JVM 測試 CropTest）。座標是底圖的像素（[width]×[height]）。
 * 一開始框住整張；拉四個角改大小、按在框裡拖著走、按在框外面重新拉一個。框不會超出底圖，
 * 放開時比 [MIN_SIDE] 小的話退回按下之前的樣子（點一下不會變成一個點）。
 * 按「完成」用 [rect]（對齊整數像素）裁切；[isWhole] 的話什麼都不用做。
 */
data class Crop(val width: Int, val height: Int, val box: Bounds = Bounds(0f, 0f, width.toFloat(), height.toFloat()), val grab: CropGrab? = null) {
    private fun Point.inPage() = Point(x.coerceIn(0f, width.toFloat()), y.coerceIn(0f, height.toFloat()))

    private val corners get() = listOf(Point(box.left, box.top), Point(box.right, box.top), Point(box.right, box.bottom), Point(box.left, box.bottom))

    /** 四個角，畫面上畫控制點用。 */
    val handles: List<Point> get() = corners

    /** 一指按下。[reach]：手指點得到的距離（底圖像素）。 */
    fun press(at: Point, reach: Float): Crop {
        val corner = corners.withIndex().filter { distance(it.value, at) <= reach }.minByOrNull { distance(it.value, at) }
        val grab = when {
            corner != null -> CropGrab.Corner(corners[(corner.index + 2) % 4], box)
            at in box -> CropGrab.Move(at, box)
            else -> CropGrab.New(at.inPage(), box)
        }
        return copy(grab = grab)
    }

    fun drag(to: Point): Crop = when (val g = grab) {
        null -> this
        is CropGrab.Corner -> copy(box = Bounds.of(g.fixed, to.inPage()))
        is CropGrab.New -> copy(box = Bounds.of(g.start, to.inPage()))
        is CropGrab.Move -> {
            // 整個框搬動，碰到底圖的邊就停住
            val b = g.before
            val dx = (to.x - g.start.x).coerceIn(-b.left, width - b.right)
            val dy = (to.y - g.start.y).coerceIn(-b.top, height - b.bottom)
            copy(box = Bounds(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy))
        }
    }

    fun release(): Crop {
        val g = grab ?: return this
        val min = min(MIN_SIDE, min(width, height)).toFloat()
        val ok = box.width >= min && box.height >= min
        return copy(box = if (ok) box else g.before, grab = null)
    }

    /** 拉到一半放棄（第二指按下來改成縮放平移）：回到按下之前的框。 */
    fun cancel(): Crop = copy(box = grab?.before ?: box, grab = null)

    /** 對齊整數像素的裁切範圍（至少 1 像素）。 */
    val rect: IntBox
        get() {
            val left = box.left.roundToInt().coerceIn(0, width - 1)
            val top = box.top.roundToInt().coerceIn(0, height - 1)
            val right = box.right.roundToInt().coerceIn(left + 1, width)
            val bottom = box.bottom.roundToInt().coerceIn(top + 1, height)
            return IntBox(left, top, right, bottom)
        }

    val isWhole get() = rect == IntBox.whole(width, height)

    companion object {
        /** 裁切框最小幾個像素（底圖本身比這個小的話就是整張）。 */
        const val MIN_SIDE = 16
    }
}
