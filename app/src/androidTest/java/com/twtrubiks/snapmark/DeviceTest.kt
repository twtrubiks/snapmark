package com.twtrubiks.snapmark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * 只有模擬器才測得到的事，`record.sh` 在錄影前先跑、不錄影：
 * - [WorkStore] 的 SQL 在 Android 9 內建的 SQLite（3.22）上跑一輪——JVM 測試用的新版 SQLite 不會擋下新語法
 * - 底圖：真的 ImageDecoder 照 EXIF 轉正（用上下兩色的圖核對顏色的位置，只看長寬的話轉錯方向也會過）、
 *   縮小、透明墊白、存出來的 JPEG 沒有 EXIF（GPS）
 * - 馬賽克：畫出來的像素一格一個顏色、看不出底下的細節
 * - 成品圖：跟底圖一樣大、標註畫在對的地方、照片存 JPEG、空白畫布存 PNG；縮圖小張但看起來一樣
 * - 裁切、旋轉：底圖的像素跟著標註一起到對的地方，裁切之後馬賽克的格子還是原本那麼大
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sql = AndroidSql(context, DB)
    private val works = File(context.cacheDir, "device-test-works").apply { mkdirs() }
    private val images = File(context.cacheDir, "device-test-images").apply { mkdirs() }

    @After
    fun cleanUp() {
        sql.close()
        context.deleteDatabase(DB)
        works.deleteRecursively()
        images.deleteRecursively()
    }

    @Test
    fun workStoreOnAndroidSqlite() {
        val store = WorkStore(sql, works)
        val blank = store.create(1440, 1920, hasBase = false)
        val photo = store.create(4000, 3000, hasBase = true)
        store.baseFile(photo.id).writeText("jpeg")

        val arrow = Node.Arrow("a", Point(1f, 2f), Point(3f, 4f), MarkupColor.PINK, StrokeSize.M)
        val saved = store.saveMarkup(blank.id, Markup(listOf(arrow)))

        assertEquals(saved, store.find(blank.id))
        assertEquals(setOf(blank.id, photo.id), store.all().map { it.id }.toSet())

        // 裁切、旋轉之後整份存（base_name 欄位在 Android 9 的 SQLite 上也要能存；舊的表補欄位在 WorkStoreTest 測）
        val sheet = Sheet(Markup(listOf(arrow.copy(scale = 2f))), 2000, 1500, "base-2.jpg")
        assertEquals(sheet, store.saveSheet(photo.id, sheet)?.sheet)
        assertEquals(sheet, store.find(photo.id)?.sheet)
        assertEquals("base-2.jpg", WorkStore(sql, works).find(photo.id)?.baseName)

        store.delete(photo.id)
        assertNull(store.find(photo.id))
        assertFalse(store.dir(photo.id).exists())
    }

    @Test
    fun baseIsTurnedUprightFromExif() {
        // 存的時候是橫的 200×100、上半紅下半藍；EXIF 說要轉多少才是正的
        // 6：順時針轉 90 度（存的第一排是看到的右邊）、3：轉 180 度、8：逆時針轉 90 度（第一排是左邊）
        val cases = listOf(
            ExifInterface.ORIENTATION_NORMAL to Expect(200, 100, red = 100 to 25, blue = 100 to 75),
            ExifInterface.ORIENTATION_ROTATE_90 to Expect(100, 200, red = 75 to 100, blue = 25 to 100),
            ExifInterface.ORIENTATION_ROTATE_180 to Expect(200, 100, red = 100 to 75, blue = 100 to 25),
            ExifInterface.ORIENTATION_ROTATE_270 to Expect(100, 200, red = 25 to 100, blue = 75 to 100),
        )
        for ((orientation, expect) in cases) {
            val bitmap = decodeBase(ImageDecoder.createSource(twoColorJpeg(orientation)))
            expect.check("EXIF 方向 $orientation", bitmap)

            // 存成底圖：像素本身就是正的（不靠 EXIF），原圖的 EXIF（GPS、相機型號、方向）都沒有帶過來
            val base = File(images, "base-$orientation.jpg")
            writeJpeg(bitmap, base)
            expect.check("存出來的底圖（EXIF 方向 $orientation）", BitmapFactory.decodeFile(base.path))
            val exif = ExifInterface(base.path)
            assertNull("GPS 帶過來了", exif.latLong)
            assertNull("相機型號帶過來了", exif.getAttribute(ExifInterface.TAG_MAKE))
            assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED) in NOT_ROTATED)
        }
    }

    @Test
    fun bigPhotosAreShrunkTo4096() {
        val file = File(images, "wide.jpg")
        val wide = Bitmap.createBitmap(5000, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        file.outputStream().use { wide.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        val bitmap = decodeBase(ImageDecoder.createSource(file))
        assertEquals(fitWithin(5000, 300, BASE_MAX_SIDE), bitmap.width to bitmap.height)
        assertEquals(4096, bitmap.width)
    }

    @Test
    fun transparentPartsBecomeWhite() {
        val file = File(images, "sticker.png")
        val sticker = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
        Canvas(sticker).drawRect(20f, 20f, 40f, 40f, Paint().apply { color = Color.RED })
        file.outputStream().use { sticker.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val bitmap = decodeBase(ImageDecoder.createSource(file))
        assertFalse(bitmap.hasAlpha())
        assertEquals(Color.WHITE, bitmap.getPixel(5, 5))
        assertEquals(Color.RED, bitmap.getPixel(30, 30))
    }

    @Test
    fun importPhotoCreatesAWorkWithItsBase() {
        val container = context.container
        val work = runBlocking { container.importPhoto(ImageDecoder.createSource(twoColorJpeg(ExifInterface.ORIENTATION_ROTATE_90))) }
        try {
            assertTrue(work.hasBase)
            assertEquals(100 to 200, work.width to work.height)
            val base = BitmapFactory.decodeFile(container.works.baseFile(work.id).path)
            Expect(100, 200, red = 75 to 100, blue = 25 to 100).check("匯入的底圖", base)
            // 作品分頁的縮圖也寫好了（比 512 小的不放大）
            val thumb = BitmapFactory.decodeFile(container.works.thumbFile(work.id).path)
            Expect(100, 200, red = 75 to 100, blue = 25 to 100).check("匯入時的縮圖", thumb)
        } finally {
            container.works.delete(work.id)
        }
    }

    @Test
    fun mosaicHidesTheDetailsInBlocks() {
        // 一個像素黑白相間的格紋：打碎之後每一格是灰色、同一格裡顏色都一樣
        val size = 485
        val photo = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) for (x in 0 until size) photo.setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val box = Node.Pixelate("m", Point(240f, 0f), Point(size.toFloat(), 240f))
        MarkupPainter(size, size, BaseLayer(photo)).draw(Canvas(out), listOf(box))

        val block = mosaicBlock(size, size)
        assertEquals(10, block)
        // 框裡面：灰的（包括最右邊不滿一格的那一格），同一格（250～259）一樣
        for ((x, y) in listOf(250 to 5, 300 to 100, 400 to 230, size - 1 to 5)) {
            val c = out.getPixel(x, y)
            assertTrue("($x, $y) 不是灰色：${Integer.toHexString(c)}", Color.red(c) in 80..175 && Color.green(c) in 80..175)
        }
        assertEquals(out.getPixel(250, 0), out.getPixel(259, 9))
        // 框外面：還是原本的格紋
        assertNotEquals(out.getPixel(10, 10), out.getPixel(11, 10))
        assertNotEquals(out.getPixel(300, 300), out.getPixel(301, 300))
    }

    @Test
    fun finalImageIsAsBigAsThePhotoWithTheMarkupOnIt() {
        // 1000×750 的照片：左上角一個像素黑白相間的格紋（馬賽克要蓋的），其他是綠色
        val (w, h) = 1000 to 750
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x < 400 && y < 300) (if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE) else GREEN
        }
        val photo = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val work = Work("p", hasBase = true, w, h, Markup(), 0, 0)
        val mosaic = Node.Pixelate("m", Point(0f, 0f), Point(300f, 300f))
        val arrow = Node.Arrow("a", Point(450f, 600f), Point(950f, 600f), MarkupColor.PINK, StrokeSize.XL)

        val out = renderWork(work.width, work.height, BaseLayer(photo), listOf(mosaic, arrow))
        assertEquals(w to h, out.width to out.height)
        assertFalse(out.hasAlpha())
        assertColor("箭頭的中段", MarkupColor.PINK.argb.toInt(), out.getPixel(700, 600))
        assertColor("沒畫到的地方", GREEN, out.getPixel(700, 200))
        // 馬賽克框裡：灰色、同一格一樣；框外的格紋還在
        val cell = mosaicBlock(w, h)
        val c = out.getPixel(5, 5)
        assertTrue("馬賽克不是灰色：${Integer.toHexString(c)}", Color.red(c) in 80..175)
        assertEquals(c, out.getPixel(cell - 1, cell - 1))
        assertNotEquals(out.getPixel(350, 10), out.getPixel(351, 10))

        // 照片存 JPEG：讀回來一樣大、箭頭還在
        val format = OutputFormat.of(work)
        assertEquals(OutputFormat.JPEG, format)
        val file = File(images, "out.jpg")
        writeImage(out, file, format)
        val options = BitmapFactory.Options()
        val saved = BitmapFactory.decodeFile(file.path, options)
        assertEquals("image/jpeg", options.outMimeType)
        assertEquals(w to h, saved.width to saved.height)
        assertColor("存出來的箭頭", MarkupColor.PINK.argb.toInt(), saved.getPixel(700, 600), tolerance = 40)
    }

    @Test
    fun blankCanvasIsSavedAsPng() {
        val work = Work("b", hasBase = false, 1440, 1920, Markup(), 0, 0)
        val arrow = Node.Arrow("a", Point(200f, 1000f), Point(1240f, 1000f), MarkupColor.BLUE, StrokeSize.XL)
        val out = renderWork(work.width, work.height, null, listOf(arrow))
        assertEquals(Color.WHITE, out.getPixel(100, 100))
        assertColor("箭頭的中段", MarkupColor.BLUE.argb.toInt(), out.getPixel(720, 1000))

        val format = OutputFormat.of(work)
        assertEquals(OutputFormat.PNG, format)
        val file = File(images, "blank.png")
        writeImage(out, file, format)
        val options = BitmapFactory.Options()
        val saved = BitmapFactory.decodeFile(file.path, options)
        assertEquals("image/png", options.outMimeType)
        assertEquals(1440 to 1920, saved.width to saved.height)
        // PNG 不失真：每個像素都一樣
        assertTrue("PNG 存出來跟畫的不一樣", saved.sameAs(out))
    }

    @Test
    fun thumbnailIsSmallButLooksTheSame() {
        val photo = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val work = Work("t", hasBase = true, 2000, 1500, Markup(), 0, 0)
        val arrow = Node.Arrow("a", Point(200f, 750f), Point(1800f, 750f), MarkupColor.PINK, StrokeSize.XL)
        val thumbnailer = Thumbnailer(work.width, work.height, BaseLayer(photo))

        val thumb = thumbnailer.render(listOf(arrow))
        assertEquals(512 to 384, thumb.width to thumb.height)
        assertColor("縮圖上的箭頭", MarkupColor.PINK.argb.toInt(), thumb.getPixel(256, 192))
        assertColor("縮圖的底圖", Color.BLUE, thumb.getPixel(20, 20))

        // 寫成 thumb.jpg
        val file = File(images, "thumb.jpg")
        thumbnailer.write(listOf(arrow), file)
        val saved = BitmapFactory.decodeFile(file.path)
        assertEquals(512 to 384, saved.width to saved.height)
    }

    @Test
    fun cropAndRotateMoveThePixelsWithTheMarkup() {
        // 400×200：左半紅、右半藍，左上角 (0..20, 0..20) 是黑的
        val photo = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, 200f, 200f, Paint().apply { color = Color.RED })
            drawRect(0f, 0f, 20f, 20f, Paint().apply { color = Color.BLACK })
        }
        // 裁切：留下 (100, 50)～(300, 150)，左半紅、右半藍，各 100 寬
        val box = IntBox(100, 50, 300, 150)
        val cropped = cropBitmap(photo, box)
        assertEquals(200 to 100, cropped.width to cropped.height)
        assertEquals(Color.RED, cropped.getPixel(50, 50))
        assertEquals(Color.BLUE, cropped.getPixel(150, 50))
        // 原本那張不動（復原時還要用）
        assertEquals(400, photo.width)
        // 整張都留下：一樣的像素，但是另一張（之後 recycle 不會動到原本那張）
        assertTrue(cropBitmap(photo, IntBox.whole(400, 200)).let { it !== photo && it.sameAs(photo) })

        // 向右轉：200×400，黑色的角跑到右上；跟標註同一套座標（Markup.rotated）
        val right = rotateBitmap(photo, clockwise = true)
        assertEquals(200 to 400, right.width to right.height)
        val corner = Markup(listOf(Node.Stamp("s", StampKind.CHECK, Point(10f, 10f)))).rotated(true, Page(400, 200)).nodes.single() as Node.Stamp
        assertEquals(Point(190f, 10f), corner.at)
        assertEquals(Color.BLACK, right.getPixel(190, 10))
        assertEquals(Color.RED, right.getPixel(100, 100))
        assertEquals(Color.BLUE, right.getPixel(100, 300))
        // 向左轉：黑色的角跑到左下
        val left = rotateBitmap(photo, clockwise = false)
        assertEquals(Color.BLACK, left.getPixel(10, 390))
        // 轉 90 度剛好對齊像素：轉回來每個像素都一樣
        assertTrue(rotateBitmap(right, clockwise = false).sameAs(photo))
    }

    @Test
    fun croppedMosaicKeepsItsBigCells() {
        // 960×960 的格紋（一格 1 像素），馬賽克一格 20；裁成 480×480 之後新的長邊一格只有 10，之前畫的還是 20
        val size = 960
        val pixels = IntArray(size * size) { i -> if ((i % size + i / size) % 2 == 0) Color.BLACK else Color.WHITE }
        val photo = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        val mosaic = Node.Pixelate("m", Point(0f, 0f), Point(400f, 400f))
        val box = IntBox(0, 0, 480, 480)
        val after = Markup(listOf(mosaic)).cropped(box, Page(size, size))
        val node = after.nodes.single() as Node.Pixelate
        assertEquals(20, node.cell(480, 480))

        val layer = BaseLayer(cropBitmap(photo, box)).prepare(after)
        val out = renderWork(480, 480, layer, after.nodes)
        // 同一格（0～19）一樣、灰色
        assertEquals(out.getPixel(0, 0), out.getPixel(19, 19))
        assertTrue(Color.red(out.getPixel(5, 5)) in 80..175)
        // 框外還是格紋
        assertNotEquals(out.getPixel(450, 450), out.getPixel(451, 450))
    }

    private fun assertColor(what: String, expected: Int, actual: Int, tolerance: Int = 12) {
        val close = listOf(Color::red, Color::green, Color::blue).all { abs(it(expected) - it(actual)) <= tolerance }
        assertTrue("$what：應該是 ${Integer.toHexString(expected)}，是 ${Integer.toHexString(actual)}", close)
    }

    /** 200×100、上半紅下半藍的 JPEG，EXIF 寫上 [orientation]，還有拍攝地點與相機型號。 */
    private fun twoColorJpeg(orientation: Int): File {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, 200f, 50f, Paint().apply { color = Color.RED })
        }
        val file = File(images, "two-color-$orientation.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setLatLong(25.0330, 121.5654)
            setAttribute(ExifInterface.TAG_MAKE, "SnapMarkTest")
            saveAttributes()
        }
        return file
    }

    /** 轉正之後應該是 [width]×[height]，[red]、[blue] 這兩點分別是紅色、藍色。 */
    private data class Expect(val width: Int, val height: Int, val red: Pair<Int, Int>, val blue: Pair<Int, Int>) {
        fun check(what: String, bitmap: Bitmap) {
            assertEquals("$what：大小", width to height, bitmap.width to bitmap.height)
            val r = bitmap.getPixel(red.first, red.second)
            val b = bitmap.getPixel(blue.first, blue.second)
            assertTrue("$what：$red 應該是紅色，是 ${Integer.toHexString(r)}", Color.red(r) > 180 && Color.blue(r) < 80)
            assertTrue("$what：$blue 應該是藍色，是 ${Integer.toHexString(b)}", Color.blue(b) > 180 && Color.red(b) < 80)
        }
    }

    private companion object {
        const val DB = "device-test.db"
        val GREEN = Color.rgb(40, 170, 60)
        val NOT_ROTATED = setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL)
    }
}
