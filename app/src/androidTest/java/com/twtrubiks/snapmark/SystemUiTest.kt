package com.twtrubiks.snapmark

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * 不是 App 畫的畫面（系統的照片挑選器、相機 App）：每個 Android 版本長得不一樣，只能真的點一次才知道接不接得上。
 * `record.sh` 在錄影之後先 `pm clear` 再跑，不錄影，輸出在 `system-ui-test.txt`，截圖是 `screenshots/systemui-*.png`。
 *
 * - Android 9、10 沒有照片挑選器，是系統的檔案畫面（找得到檔名）；Android 13 以後是照片挑選器（只有縮圖，挑第一張）。
 *   「用其他 App 選」先出系統的「選擇 App」，模擬器上點系統的檔案畫面那一項
 *   照片先放進相簿：Android 10 以後 App 直接寫 MediaStore；Android 9 要權限，交給 shell 複製再廣播掃描
 * - 相機：`record.yml` 的模擬器開了 `-camera-back emulated`（假的鏡頭畫面）。AOSP 的相機 App 先用 shell 給權限，
 *   第一次打開的提示、快門、確認的按鈕每個版本不一樣，看得到哪個點哪個
 * - 存到相簿：Android 9 會跳出系統問權限的對話框（按允許）；存好之後用 shell 查 MediaStore，找得到圖、跟底圖一樣大
 * - 分享：系統的分享選單打得開（只看選單出來，不真的分享出去）
 * - 刪作品時勾「一併刪除存到相簿的圖」：相簿裡那一張真的刪掉了（MediaStore 查不到、檔案也不在）
 */
@RunWith(AndroidJUnit4::class)
class SystemUiTest : UiTest() {
    // 這次測試存進相簿的（MediaStore 的 _id），測完刪掉
    private val savedIds = mutableListOf<Long>()

    @Before
    fun start() {
        clearWorks()
        cameraDir(context).deleteRecursively()
        shareDir(context).deleteRecursively()
        launchApp()
        waitFor(By.res(HomeTab.CAMERA.tag))
    }

    @After
    fun removeTestPhotos() {
        // shell 不經過 sh：SQL 裡不能有空白
        shell("content delete --uri content://media/external/images/media --where _display_name='$PHOTO_NAME'")
        shell("rm -f /sdcard/Pictures/$PHOTO_NAME")
        for (id in savedIds) shell("content delete --uri content://media/external/images/media --where _id=$id")
    }

    @Test
    fun pickedPhotoOpensTheCanvasUpright() {
        putTestPhotoInGallery()
        find(By.res(HomeTab.PHOTOS.tag)).click()
        pause(600)
        find(By.res("photos_button")).click()
        pickTestPhoto()

        waitFor(By.res("canvas"), timeout = 20_000)
        pause(1000)
        screenshot("systemui-1-picked")
        val work = container.works.all().single()
        assertTrue(work.hasBase)
        // 存的是橫的 1200×800（上紅下藍）＋ EXIF 順時針轉 90 度：轉正之後是直的，紅色在右邊
        assertEquals(800 to 1200, work.width to work.height)
        val base = BitmapFactory.decodeFile(container.works.baseFile(work.id).path)
        assertTrue("右邊應該是紅色", Color.red(base.getPixel(600, 600)) > 180)
        assertTrue("左邊應該是藍色", Color.blue(base.getPixel(200, 600)) > 180)
        assertTrue("底圖帶著 GPS", ExifInterface(container.works.baseFile(work.id).path).latLong == null)
    }

    /** 「用其他 App 選」：系統的「選擇 App」→ 系統的檔案畫面（模擬器上只有它會選圖）→ 一樣轉正進畫布。 */
    @Test
    fun otherAppPickerOpensTheCanvas() {
        putTestPhotoInGallery()
        find(By.res(HomeTab.PHOTOS.tag)).click()
        pause(600)
        find(By.res("photos_other_app")).click()
        requireNotNull(poll(15_000) { device.currentPackageName.takeIf { it != context.packageName } }) { "選 App 的畫面沒有出來" }
        pause(1500)
        screenshot("systemui-10-choose-app")
        // 只有一個 App 會選圖時，系統可能直接打開它（不是「選擇 App」）
        if ("documentsui" !in device.currentPackageName) {
            requireNotNull(poll(6_000) { device.findObject(CHOOSER_FILES) }) { "「選擇 App」裡找不到系統的檔案畫面" }.click()
        }
        pickTestPhoto("systemui-11-other-app")

        waitFor(By.res("canvas"), timeout = 20_000)
        val work = container.works.all().single()
        assertEquals(800 to 1200, work.width to work.height)
        val base = BitmapFactory.decodeFile(container.works.baseFile(work.id).path)
        assertTrue("右邊應該是紅色", Color.red(base.getPixel(600, 600)) > 180)
    }

    @Test
    fun cancelledPickerLeavesNothing() {
        find(By.res(HomeTab.PHOTOS.tag)).click()
        pause(600)
        find(By.res("photos_button")).click()
        leaveSystemUi("systemui-2-picker")
        assertTrue(container.works.all().isEmpty())
    }

    @Test
    fun cameraPhotoOpensTheCanvas() {
        grantCameraAppPermissions()
        var shot = takePhotoWithCameraApp()
        // 模擬器的假鏡頭偶爾連不上（相機 App 自己跳「Can't connect to the camera」，跟 SnapMark 無關，前幾次都正常）：
        // 退回首頁再拍一次。第二次還是連不上就算失敗
        if (shot.cameraError) {
            screenshot("systemui-3-camera-error")
            leaveCameraError()
            assertTrue(container.works.all().isEmpty())
            shot = takePhotoWithCameraApp()
        }
        val canvas = shot.canvas
        if (canvas == null) screenshot("systemui-3-camera-stuck")
        requireNotNull(canvas) {
            "拍完照沒有回到畫布（按了 ${shot.shots} 次快門${if (shot.cameraError) "，相機連不上" else ""}），現在在 ${device.currentPackageName}"
        }
        pause(1000)
        screenshot("systemui-4-camera-canvas")
        val work = container.works.all().single()
        assertTrue(work.hasBase)
        assertTrue("拍的照片沒有清掉", cameraDir(context).listFiles().isNullOrEmpty())
    }

    @Test
    fun cancelledCameraLeavesNothing() {
        grantCameraAppPermissions()
        find(By.res(HomeTab.CAMERA.tag)).click()
        pause(600)
        find(By.res("camera_button")).click()
        leaveSystemUi("systemui-5-camera")
        assertTrue(container.works.all().isEmpty())
        assertTrue("沒拍的暫存檔沒有刪掉", cameraDir(context).listFiles().isNullOrEmpty())
    }

    @Test
    fun savedToGalleryIsAsBigAsThePhoto() {
        val photo = importDemoPhoto()
        openTab(HomeTab.WORKS, By.res("work_${photo.id}")).click()
        drag(waitFor(By.res("canvas")), 0.3f, 0.7f, 0.6f, 0.5f)
        waitForNodes(photo.id, 1)
        val before = savedInGallery().keys

        screenshot("systemui-6-before-save")
        find(By.res("save_button")).click()
        // Android 9：系統問寫入的權限
        allowStorageIfAsked("systemui-7-storage-permission")
        val saved = requireNotNull(poll(15_000) { (savedInGallery() - before).entries.singleOrNull() }) {
            "相簿裡找不到剛存的圖：${savedInGallery()}"
        }
        savedIds += saved.key
        // 存好了回到作品分頁，提示「已存到相簿」
        waitFor(By.res("saved_snackbar"))
        waitFor(By.text("已存到相簿"))
        waitFor(By.res("work_${photo.id}"))
        pause(600)
        screenshot("systemui-8-saved")

        // 放在 Pictures/SnapMark/，檔名 SnapMark-<日期>-<時間>.jpg，跟底圖一樣大
        val path = saved.value
        assertTrue(path, Regex(""".*/Pictures/SnapMark/SnapMark-\d{8}-\d{6}(-\d+)?\.jpg""").matches(path))
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, saved.key)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        assertEquals(photo.width to photo.height, bounds.outWidth to bounds.outHeight)
        // 作品沒有被動到；「繼續改」打開同一張
        assertEquals(1, container.works.find(photo.id)?.markup?.nodes?.size)
        find(By.text("繼續改")).click()
        waitFor(By.res("canvas"))
        assertEquals(1, container.works.all().size)
    }

    /**
     * 刪作品時勾「一併刪除存到相簿的圖」：相簿裡那一張（MediaStore 那一筆和檔案）跟作品一起刪掉。
     * 那一格預設不勾；沒存過相簿的作品沒有那一格（DemoRecordingTest 刪的空白畫布）。
     */
    @Test
    fun deletingTheWorkCanAlsoDeleteItsGalleryImage() {
        val photo = importDemoPhoto()
        openTab(HomeTab.WORKS, By.res("work_${photo.id}")).click()
        drag(waitFor(By.res("canvas")), 0.3f, 0.7f, 0.6f, 0.5f)
        waitForNodes(photo.id, 1)
        val before = savedInGallery().keys
        find(By.res("save_button")).click()
        allowStorageIfAsked()
        val saved = requireNotNull(poll(15_000) { (savedInGallery() - before).entries.singleOrNull() }) { "相簿裡找不到剛存的圖" }
        savedIds += saved.key
        requireNotNull(poll(5_000) { container.works.galleryImages(photo.id).singleOrNull() }) { "沒有記下存到相簿的那一張" }

        // 存好了自己回到作品分頁
        waitFor(By.res("saved_snackbar"))
        waitFor(By.res("work_${photo.id}")).longClick()
        val box = waitFor(By.res("delete_gallery"))
        assertTrue("一併刪除應該預設不勾", !box.isChecked)
        waitFor(By.text("一併刪除存到相簿的 1 張圖"))
        box.click()
        pause(800)
        screenshot("systemui-12-delete-with-gallery")
        assertTrue(find(By.res("delete_gallery")).isChecked)
        find(By.res("delete_confirm")).click()

        requireNotNull(poll(10_000) { if (container.works.find(photo.id) == null) true else null }) { "作品沒有刪掉" }
        requireNotNull(poll(10_000) { if (saved.key !in savedInGallery()) true else null }) { "相簿裡那一張沒有刪掉：${saved.value}" }
        val file = File(saved.value)
        assertTrue("檔案還在：${saved.value}", file.name !in shell("ls ${file.parent}"))
        assertTrue(container.works.galleryImages(photo.id).isEmpty())
    }

    @Test
    fun shareOpensTheSystemSheet() {
        find(By.res(HomeTab.BLANK.tag)).click()
        pause(600)
        find(By.res("blank_button")).click()
        drag(waitFor(By.res("canvas")), 0.3f, 0.6f, 0.7f, 0.4f)
        val work = container.works.all().single()
        waitForNodes(work.id, 1)

        find(By.res("share_button")).click()
        requireNotNull(poll(15_000) { device.currentPackageName.takeIf { it != context.packageName } }) { "分享選單沒有出來" }
        pause(1500)
        screenshot("systemui-9-share-sheet")
        // 交出去的是空白畫布的成品圖：PNG、1440×1920
        val shared = shareDir(context).listFiles().orEmpty().single()
        assertTrue(shared.name, shared.name.matches(Regex("""SnapMark-\d{8}-\d{6}\.png""")))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(shared.path, bounds)
        assertEquals(work.width to work.height, bounds.outWidth to bounds.outHeight)

        // 不分享，按返回回到畫布
        device.pressBack()
        waitFor(By.res("canvas"))
        assertEquals(1, container.works.find(work.id)?.markup?.nodes?.size)
    }

    /** 測試自己做一張「照片」（DemoPhoto），走跟相機、照片一樣的匯入，等首頁的作品分頁看得到它。 */
    private fun importDemoPhoto(): Work {
        val file = File(context.cacheDir, "demo-photo.jpg")
        DemoPhoto.write(file)
        val work = runBlocking { container.importPhoto(ImageDecoder.createSource(file)) }
        file.delete()
        return work
    }

    private class CameraShot(val canvas: UiObject2?, val shots: Int, val cameraError: Boolean)

    /** 首頁 → 相機 → 提示點掉 → 快門 → 確認，直到回到 App 的畫布；相機 App 說連不上鏡頭就停下來。 */
    private fun takePhotoWithCameraApp(): CameraShot {
        find(By.res(HomeTab.CAMERA.tag)).click()
        pause(600)
        find(By.res("camera_button")).click()
        var shots = 0
        val result = poll(60_000) {
            device.findObject(By.res("canvas"))?.let { return@poll CameraShot(it, shots, cameraError = false) }
            if (device.findObject(CAMERA_ERROR) != null) return@poll CameraShot(null, shots, cameraError = true)
            val button = device.findObject(CAMERA_CONFIRM) ?: device.findObject(PERMISSION_ALLOW) ?: device.findObject(CAMERA_PROMPT)
            when {
                button != null -> button.click()
                shots < 3 -> device.findObject(CAMERA_SHUTTER)?.let {
                    if (shots == 0) screenshot("systemui-3-camera")
                    it.click()
                    shots++
                }
            }
            SystemClock.sleep(800)
            null
        }
        return result ?: CameraShot(null, shots, cameraError = false)
    }

    /** 相機 App 的錯誤對話框關掉、退回 SnapMark 的首頁（沒拍到，暫存檔會被刪掉）。 */
    private fun leaveCameraError() {
        device.findObject(By.text(Pattern.compile("(?i)dismiss|關閉|確定")))?.click()
        SystemClock.sleep(1500)
        repeat(3) {
            if (device.findObject(By.res(HomeTab.CAMERA.tag)) == null) {
                device.pressBack()
                SystemClock.sleep(1500)
            }
        }
        waitFor(By.res(HomeTab.CAMERA.tag))
        // 相機 App 重新開之前讓它把鏡頭放掉
        pause(2000)
    }

    /** 等系統的畫面出來、截圖，按返回鍵回到 App 的首頁（沒有選、沒有拍）。 */
    private fun leaveSystemUi(shot: String) {
        requireNotNull(poll(15_000) { device.currentPackageName.takeIf { it != context.packageName } }) { "系統的畫面沒有出來" }
        pause(2000)
        screenshot(shot)
        // 第一次打開時可能先有提示，按一次返回只關掉提示
        repeat(3) {
            if (device.findObject(By.res(HomeTab.CAMERA.tag)) == null) {
                device.pressBack()
                SystemClock.sleep(1500)
            }
        }
        waitFor(By.res(HomeTab.CAMERA.tag))
        // 讀取中的那一層沒有出來
        pause(500)
        assertTrue(device.findObject(By.res("importing")) == null)
    }

    /** 選照片的畫面：Android 9、10 是系統的檔案畫面（找檔名，找不到就從側邊欄進「圖片」），之後的照片挑選器挑第一張。 */
    private fun pickTestPhoto(shot: String = "systemui-0-picker") {
        requireNotNull(poll(15_000) { device.currentPackageName.takeIf { it != context.packageName } }) { "選照片的畫面沒有出來" }
        pause(1500)
        screenshot(shot)
        val byName = By.textStartsWith(PHOTO_NAME.substringBefore('.'))
        var item = poll(6_000) { device.findObject(byName) ?: device.findObject(PICKER_THUMBNAIL) }
        if (item == null) {
            // 檔案畫面打開在別的地方（例如「最近」是空的）：側邊欄 → 圖片 → Pictures
            device.findObject(By.desc(Pattern.compile("(?i)show roots|顯示根目錄")))?.click()
            SystemClock.sleep(1000)
            device.findObject(By.text(Pattern.compile("(?i)images|圖片")))?.click()
            SystemClock.sleep(1500)
            device.findObject(By.text("Pictures"))?.click()
            item = poll(6_000) { device.findObject(byName) }
        }
        if (item == null) screenshot("systemui-0-picker-not-found")
        requireNotNull(item) { "選照片的畫面找不到測試的照片 $PHOTO_NAME" }.click()
    }

    /** 1200×800、上紅下藍、EXIF 順時針轉 90 度並帶著 GPS 的 JPEG，放進相簿的 Pictures。 */
    // ACTION_MEDIA_SCANNER_SCAN_FILE 從 Android 10 起過時，只在 Android 9 的分支用（那時還沒有別的做法）
    @Suppress("DEPRECATION")
    private fun putTestPhotoInGallery() {
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, 1200f, 400f, Paint().apply { color = Color.RED })
        }
        // 先寫在 App 自己的外部資料夾（shell 讀得到），寫好 EXIF 再搬進相簿
        val staged = File(context.getExternalFilesDir(null), PHOTO_NAME)
        staged.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        ExifInterface(staged.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(25.0330, 121.5654)
            saveAttributes()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, PHOTO_NAME)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/")
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            context.contentResolver.openOutputStream(uri).use { out -> staged.inputStream().use { it.copyTo(requireNotNull(out)) } }
        } else {
            shell("cp ${staged.path} /sdcard/Pictures/$PHOTO_NAME")
            shell("am broadcast -a ${Intent.ACTION_MEDIA_SCANNER_SCAN_FILE} -d file:///sdcard/Pictures/$PHOTO_NAME")
            requireNotNull(
                poll(15_000) {
                    shell("content query --uri content://media/external/images/media --projection _display_name")
                        .takeIf { it.contains(PHOTO_NAME) }
                },
            ) { "相簿裡掃不到 $PHOTO_NAME" }
        }
        staged.delete()
    }

    /** 相機 App 第一次打開會一個一個問權限：先用 shell 給好。 */
    private fun grantCameraAppPermissions() {
        val camera = context.packageManager.resolveActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE), 0)?.activityInfo?.packageName
            ?: return
        for (permission in listOf("CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE")) {
            shell("pm grant $camera android.permission.$permission")
        }
    }

    private companion object {
        const val PHOTO_NAME = "snapmark-test.jpg"

        // 系統的「選擇 App」裡，系統的檔案畫面那一項（Android 9 叫 Files）
        val CHOOSER_FILES: BySelector = By.text(Pattern.compile("(?i)files|documents|檔案|文件"))

        // 照片挑選器（Android 13 以後）的縮圖
        val PICKER_THUMBNAIL: BySelector = By.res(Pattern.compile(".*:id/icon_thumbnail"))

        // 相機 App：快門、拍完的確認（打勾）
        val CAMERA_SHUTTER: BySelector = By.res(Pattern.compile(".*:id/(shutter_button|shutter|camera_shutter)"))
        val CAMERA_CONFIRM: BySelector = By.res(Pattern.compile(".*:id/(done_button|btn_done|confirm_button)"))

        // 相機 App 連不上鏡頭（模擬器的假鏡頭偶爾會這樣）
        val CAMERA_ERROR: BySelector = By.text(Pattern.compile("(?i).*(can.?t|cannot|unable to) connect to the camera.*|.*無法連線至相機.*"))

        // 相機 App 第一次打開的提示（系統的權限對話框是 UiTest.kt 的 PERMISSION_ALLOW）
        val CAMERA_PROMPT: BySelector = By.text(Pattern.compile("(?i)(next|got it|ok|allow|no thanks|skip|下一步|知道了|確定|允許)"))
    }
}
