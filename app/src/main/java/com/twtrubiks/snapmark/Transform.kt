package com.twtrubiks.snapmark

import kotlin.math.max

/*
 * 裁切、旋轉整張作品時，標註跟著怎麼變，純 Kotlin（JVM 測試 TransformTest）。
 * 2026-11-05 使用者確認的做法：直接改底圖與每個物件的座標（不另外記檢視範圍），裁掉的拿不回來——
 * 只有這一次打開畫布的期間可以按復原（Editor 的 History 記著整份 Sheet，舊的底圖檔留到離開畫布）。
 * 底圖本身的裁切、旋轉在 Android 那一層（BaseImage.kt 的 cropBitmap、rotateBitmap）。
 */

/** 裁切的範圍（底圖像素，整數）：左上角 ([left], [top]) 到右下角 ([right], [bottom])，不含右邊、下面那一排。 */
data class IntBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right > left && bottom > top) { "裁切的範圍不對：$this" }
    }

    val width get() = right - left
    val height get() = bottom - top

    fun toBounds() = Bounds(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    companion object {
        fun whole(width: Int, height: Int) = IntBox(0, 0, width, height)
    }
}

/**
 * 裁切成 [box] 那一塊之後的標註（[page] 是裁切前的畫布）：
 * - 每一點往左上移 (left, top)
 * - 整個在框外面的物件拿掉；一部分在框裡的留著（超出去的部分畫的時候切掉，跟畫在邊上一樣）
 * - 馬賽克的框切成框裡那一塊（框拉不出底圖）
 * - 長邊變短了：每個物件的 [Node.scale] 乘上「舊的長邊 / 新的長邊」，畫出來還是原本的像素大小
 *   （字不會變小、馬賽克的格子不會變細到看得出底下的字）
 */
fun Markup.cropped(box: IntBox, page: Page): Markup {
    val keep = box.toBounds()
    val factor = max(page.width, page.height).toFloat() / max(box.width, box.height)
    val d = Point(-box.left.toFloat(), -box.top.toFloat())
    return Markup(
        nodes.mapNotNull { node ->
            if (!node.bounds(page).intersects(keep)) return@mapNotNull null
            val inside = when (node) {
                is Node.Pixelate -> {
                    val b = Bounds.of(node.from, node.to).intersect(keep) ?: return@mapNotNull null
                    node.copy(from = Point(b.left, b.top), to = Point(b.right, b.bottom))
                }
                else -> node
            }
            inside.moved(d).rescaled(factor)
        },
    )
}

/**
 * 整張作品向右轉（[clockwise]）或向左轉 90 度之後的標註：[page] 是轉之前的畫布，轉完是 height × width。
 * 文字保持正的（不會變成直的）：繞著自己的中心轉到新的位置。長邊沒變，大小都不用動。
 */
fun Markup.rotated(clockwise: Boolean, page: Page): Markup {
    val w = page.width.toFloat()
    val h = page.height.toFloat()
    // 向右轉：原本的左邊變成上面，(x, y) → (h - y, x)；向左轉：(x, y) → (y, w - x)
    fun Point.turn() = if (clockwise) Point(h - y, x) else Point(y, w - x)
    return Markup(
        nodes.map { node ->
            when (node) {
                is Node.Arrow -> node.copy(from = node.from.turn(), to = node.to.turn())
                is Node.Shape -> node.copy(from = node.from.turn(), to = node.to.turn())
                is Node.Stroke -> node.copy(points = node.points.map { it.turn() })
                is Node.Pixelate -> node.copy(from = node.from.turn(), to = node.to.turn())
                is Node.Stamp -> node.copy(at = node.at.turn(), tail = node.tail?.turn())
                is Node.Text -> {
                    val b = node.bounds(page)
                    val center = Point((b.left + b.right) / 2, (b.top + b.bottom) / 2).turn()
                    node.copy(at = Point(center.x - b.width / 2, center.y - b.height / 2))
                }
            }
        },
    )
}
