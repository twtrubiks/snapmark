package com.twtrubiks.snapmark

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Before
import java.io.File
import java.util.regex.Pattern

/** 系統問權限的對話框上的「允許」（Android 9 是 packageinstaller，之後是 permissioncontroller）。 */
val PERMISSION_ALLOW: BySelector = By.res(Pattern.compile(".*:id/permission_allow(_foreground_only)?_button"))

/**
 * 用 UiAutomator 操作整個 App 的測試共用的東西（[DemoRecordingTest]、[SystemUiTest]、[PerfTest]）。
 *
 * 不用 ActivityScenario（會等主執行緒閒下來，畫面有無限動畫時永遠等不到），
 * UiAutomator 也不等畫面「靜止」，找元件靠 [waitFor] 自己每 0.1 秒看一次。
 */
abstract class UiTest {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val device: UiDevice = UiDevice.getInstance(instrumentation)
    protected val context = instrumentation.targetContext
    protected val container = context.container

    // record.sh 會把這個資料夾 pull 回來，放進 media branch 的 screenshots/
    private val screenshotDir = File(context.getExternalFilesDir(null), "screenshots")

    @Before
    fun prepareUi() {
        // UiAutomator 每找一次元件都先等畫面「靜止」，預設最多等 10 秒
        Configurator.getInstance().waitForIdleTimeout = 0
        screenshotDir.mkdirs()
    }

    /** 直接開 App，畫面交給 UiAutomator 等（不用 ActivityScenario，見上面）。 */
    protected fun launchApp() {
        dismissAnrDialog()
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    /** 作品清空（檔案一起刪），要用到的資料都在測試裡建。 */
    protected fun clearWorks() {
        container.works.all().forEach { container.works.delete(it.id) }
    }

    protected fun find(selector: BySelector): UiObject2 = waitFor(selector)

    // device.wait(Until...) 一秒才看一次，每一步都多等快一秒；自己每 0.1 秒看一次
    protected fun waitFor(selector: BySelector, timeout: Long = 10_000): UiObject2 {
        poll(timeout) { device.findObject(selector) }?.let { return it }
        // 找不到時可能被系統 ANR 對話框擋住，關掉再找一次
        dismissAnrDialog()
        return requireNotNull(poll(timeout) { device.findObject(selector) }) { "畫面上找不到 $selector" }
    }

    /**
     * 首頁換到 [tab]，等頁面上的 [selector] 停下來（連續 0.3 秒在同一個位置）再回傳。
     * 按分頁之後頁面要滑一下，還在滑的時候點下去只會停住滑動、點不到東西
     * （2026-11-05 CI 上按「作品」0.4 秒後點縮圖，打不開作品）。
     */
    protected fun openTab(tab: HomeTab, selector: BySelector): UiObject2 {
        find(By.res(tab.tag)).click()
        var last: android.graphics.Rect? = null
        var steady = 0
        requireNotNull(poll(10_000) {
            val bounds = device.findObject(selector)?.visibleBounds
            steady = if (bounds != null && bounds == last) steady + 1 else 0
            last = bounds
            bounds.takeIf { steady >= 3 }
        }) { "換到 ${tab.tag} 之後 $selector 沒有停下來（最後在 $last）" }
        return waitFor(selector)
    }

    protected fun <T : Any> poll(timeout: Long, block: () -> T?): T? {
        val end = SystemClock.uptimeMillis() + timeout
        while (true) {
            block()?.let { return it }
            if (SystemClock.uptimeMillis() > end) return null
            SystemClock.sleep(100)
        }
    }

    // 系統程式 ANR 時按「Close app」讓它重開；按 Wait 的話 5 秒後會再跳出來
    protected fun dismissAnrDialog() {
        device.findObject(By.res("android", "aerr_close"))?.click()
    }

    /**
     * 在 [target] 上一指從 (fromX, fromY) 拉到 (toX, toY)：座標是元件大小的比例（0～1），不寫死像素。
     * UiAutomator 的 swipe 每一步約 5 毫秒。
     */
    protected fun drag(target: UiObject2, fromX: Float, fromY: Float, toX: Float, toY: Float, steps: Int = 60) {
        val b = target.visibleBounds
        device.swipe(
            (b.left + b.width() * fromX).toInt(),
            (b.top + b.height() * fromY).toInt(),
            (b.left + b.width() * toX).toInt(),
            (b.top + b.height() * toY).toInt(),
            steps,
        )
    }

    /** 在 [target] 上點一下，座標是元件大小的比例。 */
    protected fun tap(target: UiObject2, x: Float, y: Float) {
        val b = target.visibleBounds
        device.click((b.left + b.width() * x).toInt(), (b.top + b.height() * y).toInt())
    }

    /** 在 [target] 上一指照著 [points]（元件大小的比例，x、y 交錯）畫過去，例如畫一條彎的線。 */
    protected fun scribble(target: UiObject2, vararg points: Float, segmentSteps: Int = 12) {
        val b = target.visibleBounds
        val path = points.toList().chunked(2).map { (x, y) ->
            android.graphics.Point((b.left + b.width() * x).toInt(), (b.top + b.height() * y).toInt())
        }
        device.swipe(path.toTypedArray(), segmentSteps)
    }

    /** 等作品存檔存到剛好 [count] 個標註（自動存檔在背景，畫完不會馬上寫進資料庫）。 */
    protected fun waitForNodes(workId: String, count: Int, timeout: Long = 5_000): List<Node> =
        requireNotNull(poll(timeout) { container.works.find(workId)?.markup?.nodes?.takeIf { it.size == count } }) {
            "作品 $workId 等不到 $count 個標註，現在是 ${container.works.find(workId)?.markup?.nodes}"
        }

    /**
     * 作品（[work]，剛打開、還沒縮放平移）上的一點在螢幕上的哪裡：跟畫布用同一套 [CanvasFrame]，
     * 要蓋在照片的某個地方（例如電話號碼）時用，不用猜比例。
     */
    protected fun imageToScreen(canvas: UiObject2, work: Work, p: Point): android.graphics.Point {
        val b = canvas.visibleBounds
        val padding = CANVAS_PADDING.value * context.resources.displayMetrics.density
        val viewport = CanvasFrame(b.width().toFloat(), b.height().toFloat(), work.width, work.height, padding).viewport(Zoom())
        val s = viewport.toScreen(p)
        return android.graphics.Point(b.left + s.x.toInt(), b.top + s.y.toInt())
    }

    /**
     * Android 9 按「存到相簿」時系統會問寫入的權限：還沒給過的話等對話框出來按允許。
     * Android 10 以上不需要權限，什麼都不做。
     */
    protected fun allowStorageIfAsked(shot: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return
        val allow = waitFor(PERMISSION_ALLOW)
        pause(800)
        shot?.let(::screenshot)
        allow.click()
    }

    /** 等 [selector] 從畫面上消失（例如「正在存到相簿」那一層）。 */
    protected fun waitUntilGone(selector: BySelector, timeout: Long = 15_000) {
        requireNotNull(poll(timeout) { if (device.findObject(selector) == null) true else null }) { "$selector 一直沒有消失" }
    }

    /**
     * 相簿裡 SnapMark 存的圖（shell 查 MediaStore：Android 9 的 App 沒給權限前查不到）。
     * 回傳 _id → 檔案路徑。
     */
    protected fun savedInGallery(): Map<Long, String> {
        val rows = shell("content query --uri content://media/external/images/media --projection _id:_display_name:_data")
        return Regex("""_id=(\d+), _display_name=(SnapMark-[^,]*), _data=(\S+)""").findAll(rows)
            .associate { it.groupValues[1].toLong() to it.groupValues[3] }
    }

    /**
     * 用 shell 的身分跑一個指令（可以讀寫 /sdcard、pm grant），回傳輸出。
     * 不經過 sh：照空白切開，引號會原封不動傳給指令。參數裡不要有空白（SQL 寫成 `_id=12`、`_display_name='a.jpg'`）
     */
    protected fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }

    protected fun screenshot(name: String) {
        device.takeScreenshot(File(screenshotDir, "$name.png"), 0.5f, 90)
    }

    /** 存一個文字檔，跟截圖放在一起（`record.sh` 一起拉回來，在 media 的 `screenshots/` 裡）。 */
    protected fun saveText(name: String, text: String) {
        File(screenshotDir, name).writeText(text)
    }

    // 只是讓影片看得清楚的停頓
    protected fun pause(ms: Long = 600) {
        SystemClock.sleep(ms)
    }
}
