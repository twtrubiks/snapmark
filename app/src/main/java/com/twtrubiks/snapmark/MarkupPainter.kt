package com.twtrubiks.snapmark

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 把一張作品畫到 [android.graphics.Canvas] 上，座標是底圖的像素（[imageWidth]×[imageHeight]）。
 *
 * 畫面上（[CanvasScreen] 用 `drawIntoCanvas` 轉接，先套上 [Viewport] 的縮放平移）跟輸出的成品圖、縮圖（畫到 Bitmap 上）
 * 都用這一份，看到的跟存出來的才會一樣。形狀的計算在純 Kotlin 的 [arrowOutline] 那些函式，這裡只負責畫。
 * 裡面的 Paint 會重複使用：一個 MarkupPainter 只在一條執行緒上用。
 */
class MarkupPainter(private val imageWidth: Int, private val imageHeight: Int, private val base: BaseLayer? = null) {
    // 不叫 width、height：在 canvas.withClip { } 這種以 Canvas 為 receiver 的 lambda 裡，
    // width 會變成 Canvas.getWidth()（畫面的大小），不是底圖的
    private val pageRight = imageWidth.toFloat()
    private val pageBottom = imageHeight.toFloat()
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // 馬賽克一格一個像素放大：不要內插，格子才是方的（Android 9 起 new Paint() 預設會內插，要明講）
    private val mosaicPaint = Paint().apply { isFilterBitmap = false }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = SHADOW_STEP }
    private val textPaint = textPaint()
    private val measure = AndroidTextMeasure()
    private val path = Path()
    private val rect = RectF()
    private val stamps = StampArt()

    // 文字排版很花時間，畫面每一格都要畫：同一段字（同樣的內容、大小）重複用
    private val layouts = object : LinkedHashMap<Node.Text, StaticLayout>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Node.Text, StaticLayout>) = size > 64
    }

    /** 整張作品：底圖（空白畫布、還沒讀到的照片是白色），再把標註照順序疊上去。超出底圖的部分不畫（成品圖也沒有）。 */
    fun draw(canvas: Canvas, nodes: List<Node>) {
        canvas.withClip(0f, 0f, pageRight, pageBottom) {
            if (base != null) {
                rect.set(0f, 0f, pageRight, pageBottom)
                drawBitmap(base.photo, null, rect, photoPaint)
            } else {
                drawRect(0f, 0f, pageRight, pageBottom, pagePaint)
            }
            nodes.forEach { drawNode(this, it) }
        }
    }

    /** 只畫標註、不畫底圖，超出底圖的部分一樣不畫：畫布上每一格都重畫的那一層（正在畫的那一筆），疊在 [draw] 畫好的上面。 */
    fun drawOnPage(canvas: Canvas, nodes: List<Node>) {
        canvas.withClip(0f, 0f, pageRight, pageBottom) { nodes.forEach { drawNode(this, it) } }
    }

    /** 只畫標註，不畫底圖：首頁的示意圖（[StartArt]）自己畫背景，再把真的標註疊上去。 */
    fun drawNodes(canvas: Canvas, nodes: List<Node>) = nodes.forEach { drawNode(canvas, it) }

    private fun drawNode(canvas: Canvas, node: Node) {
        when (node) {
            is Node.Arrow -> drawArrow(canvas, node)
            is Node.Shape -> drawShape(canvas, node)
            is Node.Stroke -> drawStroke(canvas, node)
            is Node.Text -> drawText(canvas, node)
            is Node.Stamp -> drawStamp(canvas, node)
            is Node.Pixelate -> drawPixelate(canvas, node)
        }
    }

    private fun drawArrow(canvas: Canvas, arrow: Node.Arrow) {
        val w = arrow.lineWidth(imageWidth, imageHeight)
        val outline = arrowOutline(arrow.from, arrow.to, w)
        if (outline.isEmpty()) return
        path.rewind()
        path.moveTo(outline[0].x, outline[0].y)
        for (i in 1 until outline.size) path.lineTo(outline[i].x, outline[i].y)
        path.close()
        fill.color = arrow.color.argb.toInt()
        shadow(fill, w)
        canvas.drawPath(path, fill)
    }

    /**
     * 馬賽克：在框裡面貼上整張打碎好的底圖（[BaseLayer.mosaic]，一格一個像素放大回去，格子對齊整張底圖）。
     * 格子的大小看這一塊（[Node.Pixelate.cell]：裁切之前畫的維持原本的格子）。
     * 打碎的是底圖本身：蓋在之前畫的標註上面時，標註也被蓋掉（跟其他標註一樣，後畫的在上面）。沒有底圖就不畫。
     */
    private fun drawPixelate(canvas: Canvas, node: Node.Pixelate) {
        val layer = base ?: return
        val cell = node.cell(imageWidth, imageHeight)
        val mosaic = layer.mosaic(cell)
        val b = Bounds.of(node.from, node.to)
        canvas.withClip(b.left, b.top, b.right, b.bottom) {
            rect.set(0f, 0f, mosaic.width * cell.toFloat(), mosaic.height * cell.toFloat())
            drawBitmap(mosaic, null, rect, mosaicPaint)
        }
    }

    /** 方框、圓角方框、橢圓只有框線、不填色；直線就是一條線。 */
    private fun drawShape(canvas: Canvas, shape: Node.Shape) {
        val w = shape.lineWidth(imageWidth, imageHeight)
        line.color = shape.color.argb.toInt()
        line.strokeWidth = w
        line.strokeCap = Paint.Cap.ROUND
        line.strokeJoin = if (shape.kind == ShapeKind.RECT) Paint.Join.MITER else Paint.Join.ROUND
        rect.set(
            min(shape.from.x, shape.to.x),
            min(shape.from.y, shape.to.y),
            max(shape.from.x, shape.to.x),
            max(shape.from.y, shape.to.y),
        )
        val short = min(rect.width(), rect.height())
        val radius = min(max(w * 2.5f, short * 0.15f), short / 2)
        drawWithShadow(canvas, w) { c, paint ->
            when (shape.kind) {
                ShapeKind.RECT -> c.drawRect(rect, paint)
                ShapeKind.ROUND_RECT -> c.drawRoundRect(rect, radius, radius, paint)
                ShapeKind.ELLIPSE -> c.drawOval(rect, paint)
                ShapeKind.LINE -> c.drawLine(shape.from.x, shape.from.y, shape.to.x, shape.to.y, paint)
            }
        }
    }

    /** 筆：照手指的軌跡，用中點做二次曲線讓線條順一點。螢光筆半透明、比較粗，整條一次畫（交疊的地方不會比較深）。 */
    private fun drawStroke(canvas: Canvas, stroke: Node.Stroke) {
        val points = stroke.points
        if (points.isEmpty()) return
        val w = stroke.lineWidth(imageWidth, imageHeight)
        path.rewind()
        path.moveTo(points[0].x, points[0].y)
        if (points.size == 1) {
            path.lineTo(points[0].x + 0.01f, points[0].y)
        } else {
            for (i in 1 until points.size) {
                val p = points[i - 1]
                val q = points[i]
                path.quadTo(p.x, p.y, (p.x + q.x) / 2, (p.y + q.y) / 2)
            }
            path.lineTo(points.last().x, points.last().y)
        }
        line.strokeWidth = w
        line.strokeCap = Paint.Cap.ROUND
        line.strokeJoin = Paint.Join.ROUND
        val argb = stroke.color.argb.toInt()
        if (stroke.highlighter) {
            line.color = (argb and 0x00FFFFFF) or (HIGHLIGHTER_ALPHA shl 24)
            canvas.drawPath(path, line)
        } else {
            line.color = argb
            drawWithShadow(canvas, w) { c, paint -> c.drawPath(path, paint) }
        }
    }

    /** 文字：左上角在 [Node.Text.at]，只在 `\n` 換行。「卡通」字先畫一圈粗的邊（白色；白字用深色的邊），再畫字。 */
    private fun drawText(canvas: Canvas, text: Node.Text) = drawText(canvas, text.text, text.at, text.fontSize(imageWidth, imageHeight), text.color, text.outlined, text)

    private fun drawText(canvas: Canvas, value: String, at: Point, size: Float, color: MarkupColor, outlined: Boolean, key: Node.Text) {
        textPaint.textSize = size
        textPaint.style = Paint.Style.FILL
        val layout = layouts.getOrPut(key) { textLayout(value, textPaint) }
        canvas.withTranslation(at.x, at.y) {
            if (outlined) {
                textPaint.style = Paint.Style.STROKE
                textPaint.strokeWidth = size * 0.22f
                textPaint.strokeJoin = Paint.Join.ROUND
                textPaint.color = if (color == MarkupColor.WHITE) MarkupColor.BLACK.argb.toInt() else Color.WHITE
                layout.draw(this)
            }
            textPaint.style = Paint.Style.FILL
            textPaint.color = color.argb.toInt()
            layout.draw(this)
        }
    }

    /** 印章：尾巴（壓在印章下面）、印章、右邊的字（深色的卡通字）。 */
    private fun drawStamp(canvas: Canvas, stamp: Node.Stamp) {
        val r = stamp.radius(imageWidth, imageHeight)
        stamp.tail?.let { tail ->
            val w = r * 0.22f
            line.strokeCap = Paint.Cap.ROUND
            // 白邊再畫顏色，在深色的圖上也看得到
            line.color = Color.WHITE
            line.strokeWidth = w + r * 0.16f
            canvas.drawLine(stamp.at.x, stamp.at.y, tail.x, tail.y, line)
            fill.clearShadowLayer()
            fill.color = Color.WHITE
            canvas.drawCircle(tail.x, tail.y, r * 0.26f, fill)
            line.color = stamps.color(stamp.kind)
            line.strokeWidth = w
            canvas.drawLine(stamp.at.x, stamp.at.y, tail.x, tail.y, line)
            fill.color = stamps.color(stamp.kind)
            canvas.drawCircle(tail.x, tail.y, r * 0.18f, fill)
        }
        stamps.draw(canvas, stamp.kind, stamp.at.x, stamp.at.y, r)
        stamp.label?.let { label ->
            val at = stamp.labelAt(label, imageWidth, imageHeight, measure)
            // 用一個假的 Node.Text 當排版快取的 key
            val key = Node.Text(stamp.id, at, label, MarkupColor.BLACK, stamp.size, scale = stamp.scale)
            drawText(canvas, label, at, stamp.labelTextSize(imageWidth, imageHeight), MarkupColor.BLACK, outlined = true, key = key)
        }
    }

    /** 實心的形狀（箭頭）的影子：模糊、往下錯開一點。 */
    private fun shadow(paint: Paint, w: Float) = paint.setShadowLayer(w * 0.6f, 0f, w * 0.15f, SHADOW)

    /**
     * 框線、筆畫（用 [line] 的顏色、粗細 [w]、端點與接角）連影子一起畫：[draw] 會先用影子的筆畫幾次，最後用 [line] 畫一次。
     * 影子是往下錯開一點、幾圈越來越粗的半透明黑線疊起來（靠近線的地方深、外面淡），看起來跟 [shadow] 的模糊差不多。
     * 不用 setShadowLayer：Android 9 模擬器的硬體繪圖替空心的框線（橢圓）做模糊時，框裡面會冒出一塊塊的灰影。
     */
    private inline fun drawWithShadow(canvas: Canvas, w: Float, draw: (Canvas, Paint) -> Unit) {
        shade.strokeCap = line.strokeCap
        shade.strokeJoin = line.strokeJoin
        canvas.withTranslation(0f, w * 0.15f) {
            for (k in 1..SHADOW_STEPS) {
                shade.strokeWidth = w + w * 1.2f * k / SHADOW_STEPS
                draw(this, shade)
            }
        }
        draw(canvas, line)
    }

    private companion object {
        val SHADOW = Color.argb(0x60, 0, 0, 0)
        const val SHADOW_STEPS = 4
        // 四層疊起來最深的地方大約 30%
        val SHADOW_STEP = Color.argb(0x16, 0, 0, 0)
        const val HIGHLIGHTER_ALPHA = 0x70
    }
}

/** 文字用的筆：粗體。畫（[MarkupPainter]）跟量（[AndroidTextMeasure]）用同一種。 */
fun textPaint() = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

/** 一段字的排版：寬度取最長的那一行，只在 `\n` 換行，不會自動折行。[paint] 的字級要先設好。 */
fun textLayout(text: String, paint: TextPaint): StaticLayout {
    val widest = text.split('\n').maxOf { paint.measureText(it) }
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, ceil(widest).toInt() + 1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .build()
}

/** 手機上量文字的大小，跟畫出來的一樣（[textLayout]）。只在一條執行緒上用（主執行緒）。 */
class AndroidTextMeasure : TextMeasure {
    private val paint = textPaint()

    override fun measure(text: String, textSize: Float): Pair<Float, Float> {
        paint.textSize = textSize
        val layout = textLayout(text, paint)
        return layout.width.toFloat() to layout.height.toFloat()
    }
}

/**
 * 印章的樣子：打勾、問號、驚嘆號、叉叉是有白框的圓形徽章，愛心是一顆有白邊的心。跟顏色、粗細無關。
 * 畫面上的標註（[MarkupPainter]）跟工具選單的圖示都用它。
 */
class StampArt {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val path = Path()

    /** 以 ([cx], [cy]) 為中心、半徑 [r] 畫一個印章。[shadow]：圖示很小，不畫影子。 */
    fun draw(canvas: Canvas, kind: StampKind, cx: Float, cy: Float, r: Float, shadow: Boolean = true) {
        if (shadow) fill.setShadowLayer(r * 0.15f, 0f, r * 0.05f, SHADOW) else fill.clearShadowLayer()
        if (kind == StampKind.HEART) {
            heart(cx, cy, r)
            fill.color = color(kind)
            canvas.drawPath(path, fill)
            line.strokeWidth = r * 0.12f
            canvas.drawPath(path, line)
            return
        }
        // 圓形徽章：底色、白框，中間一個白色的記號
        fill.color = color(kind)
        canvas.drawCircle(cx, cy, r, fill)
        line.strokeWidth = r * 0.12f
        canvas.drawCircle(cx, cy, r * 0.94f, line)
        line.strokeWidth = r * 0.2f
        when (kind) {
            StampKind.CHECK -> {
                path.rewind()
                path.moveTo(cx - r * 0.42f, cy + r * 0.02f)
                path.lineTo(cx - r * 0.12f, cy + r * 0.32f)
                path.lineTo(cx + r * 0.44f, cy - r * 0.3f)
                canvas.drawPath(path, line)
            }
            StampKind.CROSS -> {
                val d = r * 0.32f
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, line)
                canvas.drawLine(cx + d, cy - d, cx - d, cy + d, line)
            }
            StampKind.QUESTION, StampKind.NOTICE -> {
                glyph.textSize = r * 1.3f
                val text = if (kind == StampKind.QUESTION) "?" else "!"
                val m = glyph.fontMetrics
                canvas.drawText(text, cx, cy - (m.ascent + m.descent) / 2, glyph)
            }
            StampKind.HEART -> Unit
        }
    }

    /** 印章的底色（尾巴也用這個顏色）。 */
    fun color(kind: StampKind) = when (kind) {
        StampKind.CHECK -> CHECK
        StampKind.QUESTION -> QUESTION
        StampKind.NOTICE -> NOTICE
        StampKind.HEART -> HEART
        StampKind.CROSS -> CROSS
    }

    /** 一顆心，寬高大約是 2r，中心在 (cx, cy)。 */
    private fun heart(cx: Float, cy: Float, r: Float) {
        val s = r * 0.95f
        fun x(v: Float) = cx + v * s
        fun y(v: Float) = cy + v * s
        path.rewind()
        path.moveTo(x(0f), y(0.9f))
        path.cubicTo(x(-0.25f), y(0.65f), x(-1f), y(0.25f), x(-1f), y(-0.25f))
        path.cubicTo(x(-1f), y(-0.75f), x(-0.35f), y(-1f), x(0f), y(-0.55f))
        path.cubicTo(x(0.35f), y(-1f), x(1f), y(-0.75f), x(1f), y(-0.25f))
        path.cubicTo(x(1f), y(0.25f), x(0.25f), y(0.65f), x(0f), y(0.9f))
        path.close()
    }

    private companion object {
        val SHADOW = Color.argb(0x60, 0, 0, 0)
        val CHECK = Color.rgb(0x3F, 0xB0, 0x2A)
        val QUESTION = MarkupColor.BLUE.argb.toInt()
        val NOTICE = MarkupColor.ORANGE.argb.toInt()
        val CROSS = MarkupColor.RED.argb.toInt()
        val HEART = MarkupColor.PINK.argb.toInt()
    }
}
