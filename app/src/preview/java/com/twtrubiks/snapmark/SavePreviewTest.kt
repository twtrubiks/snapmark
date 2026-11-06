package com.twtrubiks.snapmark

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 存到相簿之後：離開畫布、回到「作品」分頁，下面提示「已存到相簿」，按「繼續改」打開同一張。
 * Robolectric 沒有相簿：換成 [FakeGallery]（收下寫進來的圖）。截圖說明見 [PreviewTest]。
 */
@RunWith(RobolectricTestRunner::class)
class SavePreviewTest : PreviewTest() {
    @Before
    fun fakeGallery() {
        Robolectric.setupContentProvider(FakeGallery::class.java, MediaStore.AUTHORITY)
    }

    @Test
    fun savingGoesBackToTheWorksTabAndCanContinue() {
        openBlank()
        drag(0.25f, 0.65f, 0.7f, 0.35f)
        waitForNodes(1)
        val work = works.all().single()

        compose.onNodeWithTag("save_button").performClick()
        waitTag("saved_snackbar")
        waitTag("work_${work.id}")
        settle()
        shot("01-back-to-works")
        assertEquals(1, works.galleryImages(work.id).size)
        compose.onNodeWithText("已存到相簿").assertExists()

        // 「繼續改」：打開同一張，標註都在
        compose.onNodeWithText("繼續改").performClick()
        waitTag("canvas")
        settle(500)
        shot("02-continue")
        assertEquals(1, works.find(work.id)?.markup?.nodes?.size)

        // 返回首頁不會再提示一次
        compose.onNodeWithTag("canvas_back").performClick()
        waitTag("work_${work.id}")
        settle(500)
        assertTrue(compose.onAllNodesWithTagCount("saved_snackbar") == 0)
    }

    /** 從作品分頁下面打開一張舊的、存完回來：它排到最前面，格線捲到最上面看得到它。 */
    @Test
    fun theSavedWorkIsShownAtTheTop() {
        val ids = List(10) { i ->
            val work = works.create(1440, 1920, hasBase = false)
            works.saveMarkup(work.id, Markup(listOf(Node.Stroke("s$i", listOf(Point(100f, 100f), Point(900f, 900f)), MarkupColor.BLUE, StrokeSize.M))))
            Thread.sleep(5)
            work.id
        }
        val oldest = ids.first()
        compose.onNodeWithTag("tab_works").performClick()
        waitTag("work_list")
        compose.onNodeWithTag("work_list").performScrollToNode(hasTestTag("work_$oldest"))
        compose.onNodeWithTag("work_$oldest").performClick()
        waitTag("canvas")
        drag(0.25f, 0.65f, 0.7f, 0.35f)
        compose.waitUntil(5_000) { works.find(oldest)?.markup?.nodes?.size == 2 }

        compose.onNodeWithTag("save_button").performClick()
        waitTag("saved_snackbar")
        compose.waitUntil(5_000) { works.all().first().id == oldest }
        settle()
        shot("03-saved-old-one")
        compose.onNodeWithTag("work_$oldest").assertIsDisplayed()
    }

    @Test
    fun savingAJustOpenedWorkWithoutDrawingKeepsIt() {
        openBlank()
        val work = works.all().single()
        compose.onNodeWithTag("save_button").performClick()
        waitTag("saved_snackbar")
        // 沒畫東西，但存到相簿了：作品留著（「繼續改」打得開）
        settle()
        assertEquals(listOf(work.id), works.all().map { it.id })
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
}

/** 假的相簿（MediaStore）：新增一筆就給一個網址，寫進來的圖放在暫存檔。 */
class FakeGallery : ContentProvider() {
    private val files = mutableMapOf<Long, File>()

    override fun onCreate() = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = files.size + 1L
        files[id] = File.createTempFile("gallery", ".img")
        return ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(files.getValue(ContentUris.parseId(uri)), ParcelFileDescriptor.parseMode(mode))

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 1

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null
}
