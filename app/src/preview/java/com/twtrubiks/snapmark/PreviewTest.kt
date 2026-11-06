package com.twtrubiks.snapmark

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.imageDecoderEnabled
import org.junit.Before
import org.junit.Rule
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 在 JVM 上（Robolectric）跑整個 App 的畫面，把關鍵畫面存成 PNG 給 Claude 用 Read 看：
 * 雲端 VM 沒有模擬器，CI 錄影又要等，這是改完馬上看得到畫面的方法。
 * 截圖在 `app/build/previews/<class>-<名字>.png`。螢幕是 Pixel 7 Pro 的大小（411×891 dp，xxhdpi）。
 *
 * 畫得出來不代表手機上一樣好用（觸控的手感、效能、系統畫面都看不到），最後還是要看 CI 的錄影。
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
abstract class PreviewTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val dir = File(System.getProperty("snapmark.previews") ?: "build/previews")

    protected val works get() = compose.activity.container.works

    @Before
    fun clearWorks() {
        works.all().forEach { works.delete(it.id) }
    }

    // 預覽的流程從箭頭開始畫（App 第一次打開畫布是筆，記住上次用的：`ToolMemoryPreviewTest`）
    @Before
    fun startWithTheArrow() {
        compose.runOnUiThread { compose.activity.container.tools.remember(ToolChoice(Tool.ARROW)) }
    }

    // Coil 在 API 28 以上用 ImageDecoder 讀圖，Robolectric 的 ImageDecoder 讀不了檔案（只在真的 Android 上）：
    // 預覽裡改用 BitmapFactory，作品分頁的縮圖才看得到。手機上照預設
    @Before
    fun decodeWithBitmapFactory() {
        SingletonImageLoader.setUnsafe { context -> ImageLoader.Builder(context).imageDecoderEnabled(false).build() }
    }

    /** 存一張截圖。對話框、下拉選單是另一個視窗，截不到（看 CI 的截圖）。 */
    protected fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        dir.mkdirs()
        File(dir, "${javaClass.simpleName}-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    protected fun waitTag(tag: String, timeout: Long = 10_000) =
        compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** 首頁 → 空白 → 畫布。 */
    protected fun openBlank() {
        compose.onNodeWithTag("tab_blank").performClick()
        compose.onNodeWithTag("blank_button").performClick()
        waitTag("canvas")
    }

    /** 在畫布上一指拉一下，座標是畫布大小的比例。 */
    protected fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        compose.onNodeWithTag("canvas").performTouchInput {
            swipe(Offset(width * fromX, height * fromY), Offset(width * toX, height * toY), 300)
        }
    }

    /** 在畫布上點一下，座標是畫布大小的比例。 */
    protected fun tap(x: Float, y: Float) {
        compose.onNodeWithTag("canvas").performTouchInput { click(Offset(width * x, height * y)) }
    }

    /** 打開工具選單選 [tool]；有子選項的再點 [option]（testTag）。 */
    protected fun pickTool(tool: Tool, option: String? = null) {
        compose.onNodeWithTag("tool_button").performClick()
        compose.onNodeWithTag(tool.tag).performClick()
        if (option != null) compose.onNodeWithTag(option).performClick()
    }

    /** 等背景的事（Coil 讀縮圖、存檔）做完一點：Robolectric 的主執行緒要自己推。 */
    protected fun settle(millis: Long = 1_500) {
        repeat((millis / 100).toInt()) {
            Thread.sleep(100)
            compose.waitForIdle()
        }
    }

    /** 存檔在背景：等到唯一的那張作品剛好有 [count] 個標註。 */
    protected fun waitForNodes(count: Int): List<Node> {
        compose.waitUntil(5_000) { works.all().singleOrNull()?.markup?.nodes?.size == count }
        return works.all().single().markup.nodes
    }
}
