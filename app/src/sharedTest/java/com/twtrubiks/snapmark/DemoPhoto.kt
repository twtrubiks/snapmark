package com.twtrubiks.snapmark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File

/**
 * 測試用的「照片」：淡色的桌面上一張卡片，寫著收件人、電話、地址。
 * DemoRecordingTest 與畫面預覽用它示範馬賽克（把電話號碼打碎），走的是跟挑選器一樣的匯入（[importPhoto]）。
 */
object DemoPhoto {
    const val WIDTH = 1600
    const val HEIGHT = 1200

    /** 存成 JPEG，回傳電話號碼那一段字的範圍（底圖像素）：馬賽克要蓋的地方。 */
    fun write(file: File): Bounds {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val phone = draw(Canvas(bitmap))
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        return phone
    }

    private fun draw(canvas: Canvas): Bounds {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0xFFB9D7EA.toInt(), 0xFFE9F0D6.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null

        val card = RectF(200f, 240f, 1400f, 960f)
        paint.color = Color.WHITE
        paint.setShadowLayer(24f, 0f, 10f, 0x40000000)
        canvas.drawRoundRect(card, 36f, 36f, paint)
        paint.clearShadowLayer()
        paint.color = 0xFFFF4678.toInt()
        canvas.drawRect(card.left, card.top + 60f, card.left + 16f, card.bottom - 60f, paint)

        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = 0xFF333333.toInt()
        paint.textSize = 72f
        canvas.drawText("收件人：王小明", 300f, 430f, paint)
        val label = "電話："
        val number = "0912-345-678"
        canvas.drawText(label + number, 300f, 610f, paint)
        val start = 300f + paint.measureText(label)
        val end = 300f + paint.measureText(label + number)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 56f
        paint.color = 0xFF555555.toInt()
        canvas.drawText("地址：台北市信義區市府路 1 號", 300f, 790f, paint)
        // 字的上下：基線往上一個字高、往下一點
        return Bounds(start - 20f, 610f - 72f - 10f, end + 20f, 610f + 24f)
    }
}
