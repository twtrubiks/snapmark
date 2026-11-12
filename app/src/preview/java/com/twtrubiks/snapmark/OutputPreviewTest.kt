package com.twtrubiks.snapmark

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer

/** 輸出的畫面：作品分頁的縮圖格線、長按多選刪除、畫布右上角的存到相簿、分享。截圖說明見 [PreviewTest]。 */
@RunWith(RobolectricTestRunner::class)
class OutputPreviewTest : PreviewTest() {
    @Test
    fun worksGridShowsThumbnailsAndDeletes() {
        // 一張空白畫布畫一支箭頭
        openBlank()
        drag(0.25f, 0.65f, 0.7f, 0.35f)
        val blank = works.all().single()
        waitForNodes(1)
        compose.onNodeWithTag("canvas_back").performClick()
        // 縮圖跟著存檔寫：畫上箭頭之後的樣子
        compose.waitUntil(5_000) { works.thumbFile(blank.id).exists() }
        val thumb = BitmapFactory.decodeFile(works.thumbFile(blank.id).path)
        assertEquals(384 to 512, thumb.width to thumb.height)

        // 再匯入一張照片（Robolectric 的 ImageDecoder 只讀得了 ByteBuffer）
        val file = File(compose.activity.cacheDir, "demo.jpg")
        DemoPhoto.write(file)
        val photo = runBlocking { compose.activity.container.importPhoto(ImageDecoder.createSource(ByteBuffer.wrap(file.readBytes()))) }
        assertTrue(works.thumbFile(photo.id).exists())

        compose.onNodeWithTag("tab_works").performClick()
        waitTag("work_${photo.id}")
        settle()
        shot("01-grid")

        // 長按空白畫布那一張開始多選 → 全選 → 點照片那一張取消選它
        compose.onNodeWithTag("work_${blank.id}").performTouchInput { longClick() }
        waitTag("select_all")
        compose.onNodeWithTag("select_all").performClick()
        settle()
        shot("01b-select-all")
        compose.onNodeWithTag("work_${photo.id}").performClick()
        settle()
        shot("01c-select-one")
        // 刪除 → 確認 → 刪掉（資料夾一起）
        compose.onNodeWithTag("select_delete").performClick()
        waitTag("delete_confirm")
        compose.onNodeWithTag("delete_confirm").performClick()
        // 資料庫那一筆刪掉之後才刪資料夾：兩個都等
        compose.waitUntil(5_000) { works.all().map { it.id } == listOf(photo.id) && !works.dir(blank.id).exists() }
        settle(500)
        shot("02-after-delete")
    }

    @Test
    fun canvasHasSaveAndShareButtons() {
        openBlank()
        drag(0.25f, 0.65f, 0.7f, 0.35f)
        waitForNodes(1)
        // 右上角各一顆，不用先打開選單
        compose.onNodeWithTag("save_button").assertExists()
        compose.onNodeWithTag("share_button").assertExists()
        settle()
        shot("03-top-bar")
    }
}
