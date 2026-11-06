package com.twtrubiks.snapmark

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import java.io.File

/*
 * 把作品畫成圖：成品圖（存到相簿、分享）與作品分頁的縮圖。都用畫面上的那個 [MarkupPainter]，
 * 看到的跟存出來的才一樣（見 CLAUDE.md「存檔與分享」）。`DeviceTest` 在模擬器上核對像素。
 */

/**
 * 成品圖：跟底圖（空白畫布，[width]×[height]）一樣大，底圖上疊 [nodes]。在背景執行緒畫（4096 的照片要零點幾秒）；
 * [base] 可以是畫布正在用的那一份（只讀它的像素）。用完記得 recycle（一張 4096×3072 就是 48 MB）。
 */
fun renderWork(width: Int, height: Int, base: BaseLayer?, nodes: List<Node>): Bitmap {
    val out = createBitmap(width, height)
    MarkupPainter(width, height, base).draw(Canvas(out), nodes)
    // 底下是照片或白色，沒有透明的地方：PNG 存成 RGB 比較小
    out.setHasAlpha(false)
    return out
}

/**
 * 作品分頁的縮圖（`thumb.jpg`，長邊 [THUMB_MAX_SIDE]）：畫上標註之後的樣子，每次存標註都重畫一次。
 * 底圖先縮到比縮圖大一點點（[halveDownTo]），畫的時候再縮最後一次：一下子從 4096 縮到 512，細的地方會有鋸齒。
 * 裡面有 [MarkupPainter]：一個 Thumbnailer 只在一條執行緒上用。
 */
class Thumbnailer(private val width: Int, private val height: Int, base: BaseLayer?) {
    private val size = fitWithin(width, height, THUMB_MAX_SIDE)
    private val painter = MarkupPainter(
        width,
        height,
        base?.let { layer -> BaseLayer(halveDownTo(layer.photo, size.first, size.second)) { cell -> layer.mosaic(cell) } },
    )

    fun render(nodes: List<Node>): Bitmap {
        val (w, h) = size
        val out = createBitmap(w, h)
        val canvas = Canvas(out)
        canvas.scale(w / width.toFloat(), h / height.toFloat())
        painter.draw(canvas, nodes)
        out.setHasAlpha(false)
        return out
    }

    fun write(nodes: List<Node>, file: File) {
        val thumb = render(nodes)
        try {
            writeImage(thumb, file, OutputFormat.JPEG, THUMB_JPEG_QUALITY)
        } finally {
            thumb.recycle()
        }
    }
}
