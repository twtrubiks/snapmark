package com.twtrubiks.snapmark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/*
 * 底圖（相機拍的、照片選的）：讀進來、轉正、縮小，存成作品的 base.jpg（見 CLAUDE.md「底圖」）。
 * 尺寸的規則在純 Kotlin 的 [fitWithin]、[mosaicGrid]，這裡是 Bitmap 那一層，`DeviceTest` 在模擬器上測。
 */

/** 底圖的 JPEG 品質。 */
const val BASE_JPEG_QUALITY = 92

/**
 * 讀一張圖當底圖：照 EXIF 的方向轉正、長邊縮到 [maxSide] 以內、轉成 sRGB、有透明的地方墊白色。
 *
 * 用 ImageDecoder（Android 9 起）：它會照 EXIF 的方向轉正（`DeviceTest` 用上下兩色的圖核對）、讀得了 HEIC，
 * 而且一邊讀一邊縮（setTargetSize），上億畫素的照片也不會整張先放進記憶體。
 * 讀不了（不是圖、壞掉的檔案、讀取權限沒了）丟 IOException 或 SecurityException。
 */
fun decodeBase(source: ImageDecoder.Source, maxSide: Int = BASE_MAX_SIDE): Bitmap {
    val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val (w, h) = fitWithin(info.size.width, info.size.height, maxSide)
        decoder.setTargetSize(w, h)
        // 要讀像素（馬賽克）、要壓成 JPEG：不要 GPU 上的 HARDWARE bitmap
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        // 廣色域（Display P3）的照片也轉成 sRGB：標註的顏色是 sRGB，存出來的 JPEG 也不帶色彩描述檔
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }
    if (!decoded.hasAlpha()) return decoded
    // 透明的 PNG（貼圖、去背的圖）：存成 JPEG 透明的地方會變黑，先墊白色（跟空白畫布一樣）
    val opaque = createBitmap(decoded.width, decoded.height)
    Canvas(opaque).apply {
        drawColor(Color.WHITE)
        drawBitmap(decoded, 0f, 0f, null)
    }
    // 新開的 ARGB_8888 預設標成「有透明」：墊白之後明講沒有（畫的時候也比較快）
    opaque.setHasAlpha(false)
    decoded.recycle()
    return opaque
}

/**
 * 存成 JPEG（底圖）。先寫到旁邊的暫存檔再改名：寫到一半 App 被關掉，不會留下壞掉的圖。
 * Bitmap.compress 寫出來的 JPEG 沒有 EXIF：原圖的拍攝地點（GPS）、相機型號都不會帶過來。
 */
fun writeJpeg(bitmap: Bitmap, file: File, quality: Int = BASE_JPEG_QUALITY) = writeImage(bitmap, file, OutputFormat.JPEG, quality)

/** 存成 [format]（PNG 不管 [quality]）。跟 [writeJpeg] 一樣先寫暫存檔再改名。 */
fun writeImage(bitmap: Bitmap, file: File, format: OutputFormat, quality: Int = OUTPUT_JPEG_QUALITY) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    try {
        tmp.outputStream().use { bitmap.compressTo(it, format, quality) }
        if (!tmp.renameTo(file)) throw IOException("寫不進去：$file")
    } finally {
        tmp.delete()
    }
}

/** 壓成 [format] 寫進 [out]。 */
fun Bitmap.compressTo(out: OutputStream, format: OutputFormat, quality: Int = OUTPUT_JPEG_QUALITY) {
    val ok = when (format) {
        OutputFormat.JPEG -> compress(Bitmap.CompressFormat.JPEG, quality, out)
        OutputFormat.PNG -> compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    if (!ok) throw IOException("壓縮成 $format 失敗")
}

/**
 * 從相機、照片開一張新的作品：讀圖（在 IO）→ 建作品、寫底圖與縮圖（排在 [AppContainer.worksDispatcher]，跟存檔、刪除同一條）。
 * 底圖寫不進去的話作品刪掉，不會留下一張沒有底圖的作品。開始寫之後就不能取消（轉螢幕也會寫完）。
 */
suspend fun AppContainer.importPhoto(source: ImageDecoder.Source): Work {
    val bitmap = withContext(Dispatchers.IO) { decodeBase(source) }
    try {
        return withContext(worksDispatcher + NonCancellable) {
            val work = works.create(bitmap.width, bitmap.height, hasBase = true)
            try {
                writeJpeg(bitmap, works.baseFile(work.id))
                // 作品分頁的縮圖：還沒有標註，就是縮小的照片
                Thumbnailer(work.width, work.height, BaseLayer(bitmap)).write(emptyList(), works.thumbFile(work.id))
            } catch (e: Exception) {
                works.delete(work.id)
                throw e
            }
            work
        }
    } finally {
        bitmap.recycle()
    }
}

/**
 * 畫布上的底圖，與整張先打碎好的馬賽克（畫馬賽克時只是把對應的那一塊貼上去）。
 * 在背景執行緒建（讀檔、縮圖都花時間），之後畫面、存縮圖、畫成品圖的執行緒都會讀。
 *
 * @param mosaic 照格子大小打碎整張底圖，第一次用到那個大小才算（[load]、[prepare] 先在背景算好要用的，
 *   不會在畫面的執行緒上算）。縮圖用的小張底圖借原本那張的：格子是照作品（原圖）的像素切的
 */
class BaseLayer(val photo: Bitmap, private val mosaic: (cell: Int) -> Bitmap = { mosaicOf(photo, it) }) {
    private val mosaics = HashMap<Int, Bitmap>()

    /** 一格 [cell] 像素打碎：第 (i, j) 個像素是底圖第 (i, j) 格的平均顏色，格子從左上角開始切。 */
    @Synchronized
    fun mosaic(cell: Int): Bitmap = mosaics.getOrPut(cell) { mosaic.invoke(cell) }

    /** 先算好 [markup] 裡的馬賽克（與新畫的馬賽克）要用的格子。在背景執行緒呼叫。 */
    fun prepare(markup: Markup): BaseLayer {
        mosaic(mosaicBlock(photo.width, photo.height))
        markup.nodes.filterIsInstance<Node.Pixelate>().forEach { mosaic(it.cell(photo.width, photo.height)) }
        return this
    }

    companion object {
        /** 讀作品的底圖，先算好 [markup] 要用的馬賽克。讀不到回 null（畫布上就是白的）。 */
        fun load(file: File, markup: Markup = Markup()): BaseLayer? {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val photo = BitmapFactory.decodeFile(file.path, options) ?: return null
            // 縮小顯示（還沒放大的時候）用 mipmap 比較不會閃
            photo.setHasMipMap(true)
            return BaseLayer(photo).prepare(markup)
        }
    }
}

/**
 * 把 [photo] 縮成一格（[cell] 像素）一個像素。每次縮一半（剛好 0.5 倍的雙線性就是 2×2 取平均，細的字、格紋也會平均掉），
 * 縮到不能再縮一半時，最後一次照格子的大小對齊：第 i 格對到底圖的 [i × 格寬, (i+1) × 格寬)。
 * 最右邊、最下面不滿一格的那一格用邊上的顏色補滿（CLAMP），不會變成透明、露出原圖。
 */
private fun mosaicOf(photo: Bitmap, cell: Int): Bitmap {
    val gridW = (photo.width + cell - 1) / cell
    val gridH = (photo.height + cell - 1) / cell
    val block = cell.toFloat()
    val small = halveDownTo(photo, gridW, gridH)
    val out = createBitmap(gridW, gridH)
    val shader = BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        // small 的一個像素是底圖的 photo.width / small.width 個像素，再除以格寬就是格子
        setLocalMatrix(Matrix().apply { setScale(photo.width / small.width.toFloat() / block, photo.height / small.height.toFloat() / block) })
    }
    Canvas(out).drawRect(0f, 0f, gridW.toFloat(), gridH.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
    if (small !== photo) small.recycle()
    return out
}

/**
 * 裁切底圖：留下 [box] 那一塊（底圖像素），回傳新的一張（原本那張不動，復原時還要用）。
 */
fun cropBitmap(photo: Bitmap, box: IntBox): Bitmap {
    val out = Bitmap.createBitmap(photo, box.left, box.top, box.width, box.height)
    // 整張都留下來的話 createBitmap 會回傳同一個物件
    return if (out === photo) photo.copy(Bitmap.Config.ARGB_8888, false) else out
}

/** 轉 90 度（[clockwise]：向右轉），回傳新的一張。轉 90 度剛好對齊像素，不會模糊。 */
fun rotateBitmap(photo: Bitmap, clockwise: Boolean): Bitmap {
    val matrix = Matrix().apply { setRotate(if (clockwise) 90f else -90f) }
    return Bitmap.createBitmap(photo, 0, 0, photo.width, photo.height, matrix, false)
}

/**
 * 每次縮一半，縮到再縮一半就比 [minWidth]×[minHeight] 小為止。不用縮的話回傳 [photo] 本身（不是複本）。
 * 一下子縮很多倍的話雙線性只取到附近 4 個像素，細的線、字會斷掉；每次縮一半就是 2×2 取平均。
 */
fun halveDownTo(photo: Bitmap, minWidth: Int, minHeight: Int): Bitmap {
    var small = photo
    while (small.width / 2 >= minWidth && small.height / 2 >= minHeight) {
        val half = halve(small)
        if (small !== photo) small.recycle()
        small = half
    }
    return small
}

// 剛好縮成 0.5 倍（寬高是奇數的話最後一排不要）：createScaledBitmap 縮到 (w / 2) 時倍數不是剛好 0.5，
// 取樣的位置會慢慢偏掉，細的格紋會變成一條一條的深淺
private fun halve(src: Bitmap): Bitmap {
    val out = createBitmap(src.width / 2, src.height / 2)
    Canvas(out).drawBitmap(src, Matrix().apply { setScale(0.5f, 0.5f) }, Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}
