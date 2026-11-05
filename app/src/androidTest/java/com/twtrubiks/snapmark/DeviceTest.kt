package com.twtrubiks.snapmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 只有模擬器才測得到的事，`record.sh` 在錄影前先跑、不錄影：
 * [WorkStore] 的 SQL 在 Android 9 內建的 SQLite（3.22）上跑一輪——JVM 測試用的新版 SQLite 不會擋下新語法。
 * 之後要加：底圖用真的 Bitmap 與 ExifInterface 縮圖轉正、把標註畫成成品圖（見 CLAUDE.md）。
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sql = AndroidSql(context, DB)
    private val works = File(context.cacheDir, "device-test-works").apply { mkdirs() }

    @After
    fun cleanUp() {
        sql.close()
        context.deleteDatabase(DB)
        works.deleteRecursively()
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

        store.delete(photo.id)
        assertNull(store.find(photo.id))
        assertFalse(store.dir(photo.id).exists())
    }

    private companion object {
        const val DB = "device-test.db"
    }
}
