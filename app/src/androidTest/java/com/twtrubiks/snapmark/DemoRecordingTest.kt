package com.twtrubiks.snapmark

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.FileProvider
import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * CI 錄影的展示流程：`record.sh` 在這個 class 執行期間用 screenrecord 錄整個螢幕。
 * 一個 @Test 跑完整的流程，每個操作之間停一下讓影片看得清楚。`screenrecord` 一次最多錄 180 秒，
 * `record.sh` 會接著錄下一段再接起來，長度不受限；但影片是給人看的，盡量 3 分鐘內。
 * 每做完一個功能就把它加進來；寫法的注意事項在 CLAUDE.md 的「錄影測試」。
 */
@RunWith(AndroidJUnit4::class)
class DemoRecordingTest : UiTest() {
    @Test
    fun demo() {
        clearWorks()
        // 深色模式從「跟系統」開始（模擬器是淺色）
        container.theme.set(ThemeMode.SYSTEM)
        // 從箭頭開始畫（App 第一次打開畫布是筆、之後記住上次用的，這段展示箭頭）
        container.tools.remember(ToolChoice(Tool.ARROW))
        launchApp()

        // 四個分頁切一輪：相機、照片、空白有示意圖與圓形大按鈕
        for (tab in HomeTab.entries) {
            find(By.res(tab.tag)).click()
            pause(if (tab == HomeTab.WORKS) 800 else 1500)
            if (tab != HomeTab.WORKS) screenshot("00-${tab.name.lowercase()}")
        }
        screenshot("01-works-empty")

        // 右上角 → 深色：整個 App 馬上變深色，存起來下次打開還是深色
        find(By.res("theme_button")).click()
        // 選單有展開的動畫：等它出來再截圖
        waitFor(By.res(ThemeMode.DARK.tag))
        pause(1000)
        screenshot("01a-theme-menu")
        find(By.res(ThemeMode.DARK.tag)).click()
        find(By.res(HomeTab.CAMERA.tag)).click()
        pause(1500)
        screenshot("01b-dark")
        assertEquals("DARK", context.getSharedPreferences("settings", 0).getString("theme", null))
        find(By.res("theme_button")).click()
        pause()
        find(By.res(ThemeMode.SYSTEM.tag)).click()
        pause()

        // 空白 → 開一張作品 → 進畫布
        find(By.res(HomeTab.BLANK.tag)).click()
        pause()
        find(By.res("blank_button")).click()
        val canvas = waitFor(By.res("canvas"))
        pause(1200)
        screenshot("02-canvas-blank")
        val work = container.works.all().single()

        // 拉一支箭頭：預設粉紅、中
        drag(canvas, 0.22f, 0.68f, 0.68f, 0.38f)
        val first = waitForNodes(work.id, 1).single() as Node.Arrow
        assertEquals(MarkupColor.PINK, first.color)
        assertEquals(StrokeSize.M, first.size)
        pause(1000)
        screenshot("03-arrow")

        // 換藍色、特大，再拉一支
        find(By.res("color_button")).click()
        pause(1000)
        screenshot("04-color-picker")
        find(By.res(MarkupColor.BLUE.tag)).click()
        pause()
        find(By.res("color_button")).click()
        pause()
        find(By.res(StrokeSize.XL.tag)).click()
        pause()
        drag(waitFor(By.res("canvas")), 0.82f, 0.74f, 0.5f, 0.5f)
        val second = waitForNodes(work.id, 2).last() as Node.Arrow
        assertEquals(MarkupColor.BLUE, second.color)
        assertEquals(StrokeSize.XL, second.size)
        pause(1000)
        screenshot("05-two-arrows")

        // 選取：點第二支箭頭的中間 → 拖著走 → 改成綠色 → 刪掉 → 復原
        tap(waitFor(By.res("canvas")), 0.66f, 0.62f)
        waitFor(By.res("delete"))
        pause(1000)
        screenshot("06-selected")
        drag(waitFor(By.res("canvas")), 0.66f, 0.62f, 0.66f, 0.7f)
        requireNotNull(poll(5_000) { (container.works.find(work.id)?.markup?.nodes?.last() as? Node.Arrow)?.takeIf { it.from.y > second.from.y } }) {
            "第二支箭頭沒有被拖動"
        }
        pause()
        find(By.res("color_button")).click()
        pause()
        find(By.res(MarkupColor.GREEN.tag)).click()
        requireNotNull(poll(5_000) { (container.works.find(work.id)?.markup?.nodes?.last() as? Node.Arrow)?.takeIf { it.color == MarkupColor.GREEN } }) {
            "選到的箭頭沒有變成綠色"
        }
        pause(1000)
        find(By.res("delete")).click()
        waitForNodes(work.id, 1)
        pause()
        // 復原、重做（重做在 CanvasPreviewTest 測，這裡只按復原：影片盡量短）
        find(By.res("undo")).click()
        waitForNodes(work.id, 2)
        pause(1000)

        // 兩指放大，換平移工具拖一下，再兩指縮回來，換回箭頭
        waitFor(By.res("canvas")).pinchOpen(0.6f)
        pause(1000)
        screenshot("07-zoomed")
        find(By.res("tool_button")).click()
        pause(1000)
        screenshot("08-tool-picker")
        find(By.res(Tool.PAN.tag)).click()
        pause()
        drag(waitFor(By.res("canvas")), 0.4f, 0.4f, 0.6f, 0.55f)
        pause(1000)
        waitFor(By.res("canvas")).pinchClose(0.6f)
        // 再縮一次：縮到底就是剛好放進畫面、置中，後面點的座標才對得上
        waitFor(By.res("canvas")).pinchClose(0.8f)
        pause()
        find(By.res("tool_button")).click()
        pause()
        find(By.res(Tool.ARROW.tag)).click()
        pause()
        // 平移、縮放不會多出標註
        waitForNodes(work.id, 2)

        // 形狀：橢圓
        pickTool(Tool.SHAPE, ShapeKind.ELLIPSE.tag)
        drag(waitFor(By.res("canvas")), 0.15f, 0.25f, 0.5f, 0.4f)
        assertEquals(ShapeKind.ELLIPSE, (waitForNodes(work.id, 3).last() as Node.Shape).kind)
        pause()

        // 螢光筆：畫一條彎的
        pickTool(Tool.PEN, penTag(highlighter = true))
        scribble(waitFor(By.res("canvas")), 0.2f, 0.82f, 0.35f, 0.76f, 0.5f, 0.82f, 0.65f, 0.76f, 0.8f, 0.82f)
        assertTrue((waitForNodes(work.id, 4).last() as Node.Stroke).highlighter)
        pause()

        // 文字：點一下、打字、按完成
        pickTool(Tool.TEXT)
        tap(waitFor(By.res("canvas")), 0.2f, 0.62f)
        waitFor(By.res("text_field")).text = "這裡！"
        pause(1000)
        screenshot("09-typing")
        find(By.res("text_done")).click()
        assertEquals("這裡！", (waitForNodes(work.id, 5).last() as Node.Text).text)
        pause()
        // 點一下選到它，泡泡切成純色字
        tap(waitFor(By.res("canvas")), 0.24f, 0.62f)
        waitFor(By.res("bubble_plain")).click()
        requireNotNull(poll(5_000) { (container.works.find(work.id)?.markup?.nodes?.last() as? Node.Text)?.takeIf { !it.outlined } }) {
            "文字沒有切成純色字"
        }
        pause(1000)

        // 印章：愛心
        pickTool(Tool.STAMP, StampKind.HEART.tag)
        tap(waitFor(By.res("canvas")), 0.78f, 0.3f)
        assertEquals(StampKind.HEART, (waitForNodes(work.id, 6).last() as Node.Stamp).kind)
        pause()
        // 點一下選到它，加尾巴
        tap(waitFor(By.res("canvas")), 0.78f, 0.3f)
        waitFor(By.res("bubble_tail")).click()
        requireNotNull(poll(5_000) { (container.works.find(work.id)?.markup?.nodes?.last() as? Node.Stamp)?.tail }) {
            "印章沒有加上尾巴"
        }
        pause(1200)
        screenshot("10-all-tools")

        // ⋮ → 清除標註：全部不見；按復原又回來
        find(By.res("more_button")).click()
        pause(800)
        screenshot("10b-more-menu")
        find(By.res("menu_clear")).click()
        waitForNodes(work.id, 0)
        pause(1000)
        find(By.res("undo")).click()
        waitForNodes(work.id, 6)
        pause(1000)

        // 返回 → 作品分頁有這一張的縮圖
        find(By.res("canvas_back")).click()
        find(By.res(HomeTab.WORKS.tag)).click()
        waitFor(By.descContains("6 個標註"))
        pause(1200)
        screenshot("11-works-one")

        // 照片：測試自己做一張「照片」（卡片上有電話號碼），像別的 App（相簿）一樣「分享」給 SnapMark：
        // 直接開成一張新的作品進畫布（跟相機、選照片同一段匯入）。系統的挑選器、相機在 SystemUiTest 測（不錄影）
        val photoFile = File(shareDir(context), "demo-photo.jpg")
        val phone = DemoPhoto.write(photoFile)
        context.startActivity(
            Intent(Intent.ACTION_SEND)
                .setClass(context, MainActivity::class.java)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, context.fileProviderAuthority, photoFile))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val photoCanvas = waitFor(By.res("canvas"))
        val photo = requireNotNull(poll(10_000) { container.works.all().firstOrNull { it.hasBase } }) { "分享進來的照片沒有開成作品" }
        assertEquals(DemoPhoto.WIDTH to DemoPhoto.HEIGHT, photo.width to photo.height)
        photoFile.delete()
        pause(1200)
        screenshot("12-photo")

        // 馬賽克蓋住電話號碼（照片上的位置換算成螢幕座標）
        pickTool(Tool.PIXELATE)
        val from = imageToScreen(photoCanvas, photo, Point(phone.left, phone.top))
        val to = imageToScreen(photoCanvas, photo, Point(phone.right, phone.bottom))
        device.swipe(from.x, from.y, to.x, to.y, 60)
        assertTrue(waitForNodes(photo.id, 1).single() is Node.Pixelate)
        pause(1000)
        // 箭頭從下面指著它
        pickTool(Tool.ARROW)
        val tip = imageToScreen(photoCanvas, photo, Point((phone.left + phone.right) / 2, phone.bottom + 30f))
        device.swipe(tip.x - 150, tip.y + 330, tip.x, tip.y, 60)
        waitForNodes(photo.id, 2)
        pause(1200)
        screenshot("13-mosaic")

        // ⋮ → 向右轉：照片和標註一起轉（字還是正的）；再向左轉回來
        find(By.res("more_button")).click()
        pause(800)
        find(By.res("menu_rotate_right")).click()
        waitUntilGone(By.res("transforming"))
        requireNotNull(poll(5_000) { container.works.find(photo.id)?.takeIf { it.width == photo.height } }) { "向右轉之後應該是直的" }
        pause(1500)
        screenshot("13b-rotated")
        find(By.res("more_button")).click()
        pause(800)
        find(By.res("menu_rotate_left")).click()
        waitUntilGone(By.res("transforming"))
        requireNotNull(poll(5_000) { container.works.find(photo.id)?.takeIf { it.width == photo.width } }) { "向左轉之後應該轉回來" }
        pause(1000)

        // ⋮ → 裁切：拉左上角、右下角，只留卡片那一塊 → 完成
        find(By.res("more_button")).click()
        pause(800)
        find(By.res("menu_crop")).click()
        val cropCanvas = waitFor(By.res("canvas"))
        pause(1000)
        val corner1 = imageToScreen(cropCanvas, photo, Point(0f, 0f))
        val target1 = imageToScreen(cropCanvas, photo, Point(140f, 180f))
        device.swipe(corner1.x, corner1.y, target1.x, target1.y, 40)
        pause(600)
        val corner2 = imageToScreen(cropCanvas, photo, Point(photo.width.toFloat(), photo.height.toFloat()))
        val target2 = imageToScreen(cropCanvas, photo, Point(1460f, 1020f))
        device.swipe(corner2.x, corner2.y, target2.x, target2.y, 40)
        pause(1000)
        screenshot("13c-crop")
        find(By.res("crop_done")).click()
        waitUntilGone(By.res("transforming"))
        val cropped = requireNotNull(poll(5_000) { container.works.find(photo.id)?.takeIf { it.width < photo.width } }) { "裁切之後應該變小" }
        assertTrue("裁切的寬度不對：${cropped.width}", cropped.width in 1300..1340)
        // 馬賽克、箭頭都在卡片裡：留著
        assertEquals(2, cropped.markup.nodes.size)
        pause(1500)
        screenshot("13d-cropped")
        // 復原：照片回到整張；重做：再裁一次
        find(By.res("undo")).click()
        waitUntilGone(By.res("transforming"))
        requireNotNull(poll(5_000) { container.works.find(photo.id)?.takeIf { it.width == photo.width } }) { "復原之後應該是整張" }
        pause(1200)
        find(By.res("redo")).click()
        waitUntilGone(By.res("transforming"))
        requireNotNull(poll(5_000) { container.works.find(photo.id)?.takeIf { it.width == cropped.width } }) { "重做之後應該又裁好" }
        pause(1200)

        // 右上角「存到相簿」（Android 9 會先問寫入的權限）：存好了離開畫布、回到作品分頁，
        // 剛存的照片在最前面，下面提示「已存到相簿」與「繼續改」
        find(By.res("save_button")).click()
        allowStorageIfAsked()
        requireNotNull(poll(10_000) { savedInGallery().values.firstOrNull { "/Pictures/SnapMark/" in it } }) { "相簿裡找不到存的圖" }
        waitFor(By.res("saved_snackbar"))
        waitFor(By.descContains("2 個標註"))
        pause(1500)
        screenshot("14-saved")

        // 「繼續改」：打開剛剛那張，再返回作品分頁（兩張縮圖，照片的在前面）
        find(By.text("繼續改")).click()
        waitFor(By.res("canvas"))
        pause(1500)
        find(By.res("canvas_back")).click()
        waitFor(By.descContains("2 個標註"))
        assertEquals(2, container.works.all().size)
        pause(1500)
        screenshot("15-works-grid")

        // 長按空白畫布那一張 → 確認刪除 → 只剩照片那一張
        waitFor(By.descContains("6 個標註")).longClick()
        waitFor(By.res("delete_confirm"))
        pause(1200)
        screenshot("16-delete")
        find(By.res("delete_confirm")).click()
        requireNotNull(poll(5_000) { container.works.all().singleOrNull()?.takeIf { it.id == photo.id } }) {
            "刪掉空白畫布之後應該只剩照片：${container.works.all().map { it.id }}"
        }
        pause(1500)
    }

    /** 打開工具選單選 [tool]；有子選項的再點 [option]（testTag）。停頓比較短：影片盡量 3 分鐘內。 */
    private fun pickTool(tool: Tool, option: String? = null) {
        find(By.res("tool_button")).click()
        pause(500)
        find(By.res(tool.tag)).click()
        pause(500)
        if (option != null) {
            find(By.res(option)).click()
            pause(500)
        }
    }
}
