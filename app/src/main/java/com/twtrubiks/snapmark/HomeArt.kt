package com.twtrubiks.snapmark

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * 首頁相機、照片、空白三頁中間的示意圖。背景（照片、紙）用 Compose 畫，上面的標註是真的 [Node]，
 * 交給畫布用的同一個 [MarkupPainter] 畫：示意圖上的箭頭、卡通字、印章就是 App 畫出來的樣子。
 * 座標是 1000×800 的假想底圖（標註的粗細照這個大小算），左右兩邊空的各切掉 [ART_LEFT]，
 * 剩下 [ART_WIDTH]×[ART_HEIGHT] 整個縮放到放得進去。
 */

private const val PAGE_WIDTH = 1000
private const val ART_LEFT = 70f
private const val ART_WIDTH = 860
private const val ART_HEIGHT = 800

/** 示意圖：放進 [modifier] 給的範圍、維持比例、置中。純裝飾，TalkBack 不唸。 */
@Composable
fun StartArt(tab: HomeTab, modifier: Modifier = Modifier) {
    val painter = remember { MarkupPainter(PAGE_WIDTH, ART_HEIGHT) }
    val scheme = MaterialTheme.colorScheme
    val colors = ArtColors(
        blob = scheme.primaryContainer.copy(alpha = 0.55f),
        frame = scheme.onSurfaceVariant,
        outline = scheme.outlineVariant,
        card = scheme.surfaceContainerHighest,
        cardBehind = scheme.surfaceContainerHigh,
    )
    Canvas(modifier) {
        val s = min(size.width / ART_WIDTH, size.height / ART_HEIGHT)
        withTransform({
            translate((size.width - ART_WIDTH * s) / 2, (size.height - ART_HEIGHT * s) / 2)
            scale(s, s, pivot = Offset.Zero)
            translate(-ART_LEFT, 0f)
        }) {
            drawCircle(colors.blob, radius = 370f, center = Offset(500f, 400f))
            when (tab) {
                HomeTab.CAMERA -> {
                    cameraScene(colors)
                    drawIntoCanvas { painter.drawNodes(it.nativeCanvas, HomeArt.camera) }
                }
                HomeTab.PHOTOS -> {
                    photosScene(colors)
                    drawIntoCanvas { painter.drawNodes(it.nativeCanvas, HomeArt.photos) }
                }
                HomeTab.BLANK -> rotate(-4f, pivot = Offset(500f, 390f)) {
                    paper(colors)
                    drawIntoCanvas { painter.drawNodes(it.nativeCanvas, HomeArt.blank) }
                }
                HomeTab.WORKS -> Unit
            }
        }
    }
}

/** 跟著深淺色變的顏色（照片、紙本身不變：照片就是照片，空白畫布一定是白的）。 */
private class ArtColors(val blob: Color, val frame: Color, val outline: Color, val card: Color, val cardBehind: Color)

/** 三張示意圖上的標註。用的是畫布上真的那幾種，[Node.scale] 放大一點，縮得很小的時候還看得清楚。 */
internal object HomeArt {
    val camera: List<Node> = listOf(
        Node.Arrow("a", Point(430f, 548f), Point(598f, 352f), MarkupColor.PINK, StrokeSize.XL, scale = 1.5f),
        Node.Text("t", Point(180f, 555f), "看這裡", MarkupColor.PINK, StrokeSize.L, scale = 1.3f),
    )

    val photos: List<Node> = listOf(
        Node.Shape("e", ShapeKind.ELLIPSE, Point(522f, 384f), Point(628f, 476f), MarkupColor.RED, StrokeSize.L, scale = 1.4f),
        Node.Stamp("s", StampKind.CHECK, Point(712f, 590f), size = StrokeSize.L, scale = 1.2f),
    )

    val blank: List<Node> = buildList {
        // 一顆燈泡：圓的部分從左下繞過頂端到右下，再收成燈頭
        val bulb = (125..415 step 10).map { onCircle(500f, 290f, 112f, it) } +
            listOf(Point(554f, 450f), Point(446f, 450f), onCircle(500f, 290f, 112f, 125))
        add(Node.Stroke("b", bulb, MarkupColor.BLUE, StrokeSize.L, scale = 1.5f))
        add(Node.Stroke("b1", listOf(Point(454f, 488f), Point(546f, 488f)), MarkupColor.BLUE, StrokeSize.L, scale = 1.5f))
        add(Node.Stroke("b2", listOf(Point(470f, 524f), Point(530f, 524f)), MarkupColor.BLUE, StrokeSize.L, scale = 1.5f))
        // 燈絲
        val filament = listOf(Point(468f, 400f), Point(480f, 346f), Point(500f, 374f), Point(520f, 346f), Point(532f, 400f))
        add(Node.Stroke("f", filament, MarkupColor.ORANGE, StrokeSize.M, scale = 1.5f))
        // 發光的線
        listOf(195, 232, 270, 308, 345).forEach { angle ->
            add(Node.Stroke("r$angle", listOf(onCircle(500f, 290f, 148f, angle), onCircle(500f, 290f, 190f, angle)), MarkupColor.ORANGE, StrokeSize.L, scale = 1.5f))
        }
        add(Node.Text("t", Point(282f, 574f), "好點子！", MarkupColor.PINK, StrokeSize.L, scale = 1.3f))
        add(Node.Stamp("h", StampKind.HEART, Point(682f, 628f), size = StrokeSize.M, scale = 1.4f))
    }

    // 角度是螢幕的方向（y 往下）：270 是正上方
    private fun onCircle(cx: Float, cy: Float, r: Float, degrees: Int): Point {
        val rad = Math.toRadians(degrees.toDouble())
        return Point(cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat())
    }
}

// 示意圖裡「照片」的顏色
private val SKY_TOP = Color(0xFFB9DDFB)
private val SKY_BOTTOM = Color(0xFFE6F3FE)
private val SUN = Color(0xFFFFCE4F)
private val HILL_BACK = Color(0xFF93CF9B)
private val HILL_FRONT = Color(0xFF5FAE72)
private val GRASS = Color(0xFF9CD18A)
private val WALL = Color(0xFFF5B36D)
private val ROOF = Color(0xFFD5564A)
private val WOOD = Color(0xFF8B5A3C)
private val WINDOW = Color(0xFFFFF1BF)
private val LEAVES = Color(0xFF68BA67)

/** 相機：觀景窗的四個角框著一張風景照（太陽、兩座山）。 */
private fun DrawScope.cameraScene(colors: ArtColors) {
    val l = 160f
    val t = 130f
    val r = 840f
    val b = 670f
    roundedPhoto(l, t, r, b, 30f) {
        drawRect(Brush.verticalGradient(listOf(SKY_TOP, SKY_BOTTOM), startY = t, endY = b), Offset(l, t), Size(r - l, b - t))
        drawCircle(SUN, radius = 62f, center = Offset(668f, 282f))
        drawPath(polygon(l - 20, b, l - 20, 560f, 330f, 360f, 470f, 470f, 610f, 384f, r + 20, 600f, r + 20, b), HILL_BACK)
        drawPath(
            Path().apply {
                moveTo(l, 610f)
                quadraticTo(430f, 500f, r, 600f)
                lineTo(r, b)
                lineTo(l, b)
                close()
            },
            HILL_FRONT,
        )
    }
    // 觀景窗的四個角
    val gap = 34f
    val arm = 86f
    val corner = Stroke(width = 14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    listOf(
        Triple(l - gap, t - gap, Offset(1f, 1f)),
        Triple(r + gap, t - gap, Offset(-1f, 1f)),
        Triple(l - gap, b + gap, Offset(1f, -1f)),
        Triple(r + gap, b + gap, Offset(-1f, -1f)),
    ).forEach { (x, y, dir) ->
        drawPath(
            Path().apply {
                moveTo(x, y + arm * dir.y)
                lineTo(x, y)
                lineTo(x + arm * dir.x, y)
            },
            colors.frame,
            style = corner,
        )
    }
}

/** 照片：後面兩張歪歪的，最上面一張是有白邊的房子照片。 */
private fun DrawScope.photosScene(colors: ArtColors) {
    val pivot = Offset(500f, 400f)
    rotate(-9f, pivot) { card(colors.cardBehind, colors.outline) }
    rotate(6f, pivot) { card(colors.card, colors.outline) }
    // 照片的白邊
    drawRoundRect(Color.White, Offset(200f, 150f), Size(600f, 500f), CornerRadius(26f))
    drawRoundRect(colors.outline, Offset(200f, 150f), Size(600f, 500f), CornerRadius(26f), style = Stroke(3f))
    val l = 220f
    val t = 170f
    val r = 780f
    val b = 630f
    roundedPhoto(l, t, r, b, 12f) {
        drawRect(Brush.verticalGradient(listOf(SKY_TOP, SKY_BOTTOM), startY = t, endY = b), Offset(l, t), Size(r - l, b - t))
        drawRect(GRASS, Offset(l, 548f), Size(r - l, b - 548f))
        // 雲
        listOf(Triple(676f, 238f, 30f), Triple(712f, 222f, 38f), Triple(750f, 240f, 28f)).forEach { (x, y, radius) ->
            drawCircle(Color.White, radius, Offset(x, y))
        }
        // 樹
        drawRect(WOOD, Offset(294f, 470f), Size(18f, 92f))
        drawCircle(LEAVES, radius = 50f, center = Offset(303f, 446f))
        // 房子
        drawRect(WALL, Offset(400f, 384f), Size(220f, 178f))
        drawPath(polygon(374f, 396f, 510f, 272f, 646f, 396f), ROOF)
        drawRect(WOOD, Offset(482f, 476f), Size(56f, 86f))
        drawRect(WINDOW, Offset(420f, 412f), Size(48f, 42f))
        drawRect(WINDOW, Offset(552f, 412f), Size(48f, 42f))
    }
}

/** 空白：一張白紙（有一點影子）。 */
private fun DrawScope.paper(colors: ArtColors) {
    val topLeft = Offset(250f, 70f)
    val size = Size(500f, 640f)
    drawRoundRect(Color.Black.copy(alpha = 0.10f), topLeft + Offset(0f, 14f), size, CornerRadius(20f))
    drawRoundRect(Color.White, topLeft, size, CornerRadius(20f))
    drawRoundRect(colors.outline, topLeft, size, CornerRadius(20f), style = Stroke(3f))
}

private fun DrawScope.card(fill: Color, outline: Color) {
    drawRoundRect(fill, Offset(215f, 165f), Size(570f, 470f), CornerRadius(26f))
    drawRoundRect(outline, Offset(215f, 165f), Size(570f, 470f), CornerRadius(26f), style = Stroke(3f))
}

/** 圓角的照片：[draw] 畫的東西都剪在圓角裡面。 */
private inline fun DrawScope.roundedPhoto(l: Float, t: Float, r: Float, b: Float, corner: Float, draw: DrawScope.() -> Unit) {
    val clip = Path().apply { addRoundRect(RoundRect(l, t, r, b, CornerRadius(corner))) }
    clipPath(clip) { draw() }
}

/** 照順序連起來的多邊形：x0, y0, x1, y1… */
private fun polygon(vararg xy: Float) = Path().apply {
    moveTo(xy[0], xy[1])
    for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1])
    close()
}
