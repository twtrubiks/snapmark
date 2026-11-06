package com.twtrubiks.snapmark

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WorkStoreTest {
    private val sql = JdbcSql()
    private val dir = Files.createTempDirectory("works").toFile()
    private var clock = 1_000L
    private var changes = 0
    private val store = WorkStore(sql, dir, now = { clock }, onChange = { changes++ })

    @After
    fun cleanUp() {
        sql.close()
        dir.deleteRecursively()
    }

    @Test
    fun createThenFind() {
        val work = store.create(1440, 1920, hasBase = false)

        assertEquals(work, store.find(work.id))
        assertFalse(work.hasBase)
        assertEquals(Markup(), work.markup)
        assertTrue(store.dir(work.id).isDirectory)
    }

    @Test
    fun recentlyChangedFirst() {
        val first = store.create(100, 100, hasBase = false)
        clock = 2_000
        val second = store.create(100, 100, hasBase = true)
        assertEquals(listOf(second.id, first.id), store.all().map { it.id })

        clock = 3_000
        val arrow = Node.Arrow("a", Point(1f, 2f), Point(3f, 4f), MarkupColor.PINK, StrokeSize.M)
        val saved = store.saveMarkup(first.id, Markup(listOf(arrow)))

        assertEquals(listOf(first.id, second.id), store.all().map { it.id })
        assertEquals(saved, store.find(first.id))
        assertEquals(listOf<Node>(arrow), saved?.markup?.nodes)
        assertEquals(1_000L, saved?.createdAt)
        assertEquals(3_000L, saved?.updatedAt)
    }

    @Test
    fun deleteRemovesFiles() {
        val work = store.create(100, 100, hasBase = true)
        store.baseFile(work.id).writeText("jpeg")

        store.delete(work.id)

        assertNull(store.find(work.id))
        assertFalse(store.dir(work.id).exists())
        assertNull(store.saveMarkup(work.id, Markup()))
    }

    @Test
    fun galleryImagesAreRememberedPerWorkUntilItIsDeleted() {
        val a = store.create(100, 100, hasBase = true)
        val b = store.create(100, 100, hasBase = false)
        val before = changes

        store.addGalleryImage(a.id, "content://media/external/images/media/12")
        clock = 2_000
        store.addGalleryImage(b.id, "content://media/external/images/media/13")
        // 同一毫秒存兩次：照存的順序
        store.addGalleryImage(a.id, "content://media/external/images/media/14")
        store.addGalleryImage(a.id, "content://media/external/images/media/15")

        assertEquals(
            listOf("content://media/external/images/media/12", "content://media/external/images/media/14", "content://media/external/images/media/15"),
            store.galleryImages(a.id),
        )
        assertEquals(listOf("content://media/external/images/media/13"), store.galleryImages(b.id))
        // 存到相簿不算改了作品：首頁不用重讀
        assertEquals(before, changes)

        store.delete(a.id)
        assertEquals(emptyList<String>(), store.galleryImages(a.id))
        assertEquals(1, store.galleryImages(b.id).size)
    }

    @Test
    fun everyWriteIsReported() {
        val work = store.create(100, 100, hasBase = false)
        assertEquals(1, changes)

        store.saveMarkup(work.id, Markup())
        assertEquals(2, changes)

        // 讀不算
        store.all()
        store.find(work.id)
        assertEquals(2, changes)

        store.delete(work.id)
        assertEquals(3, changes)
    }

    @Test
    fun saveSheetChangesTheSizeAndTheBase() {
        val work = store.create(4000, 3000, hasBase = true)
        assertEquals(WorkStore.DEFAULT_BASE, work.baseName)
        assertEquals("base.jpg", work.sheet.base)

        clock = 2_000
        val arrow = Node.Arrow("a", Point(1f, 2f), Point(3f, 4f), MarkupColor.PINK, StrokeSize.M, scale = 2f)
        val saved = store.saveSheet(work.id, Sheet(Markup(listOf(arrow)), 2000, 1500, "base-2.jpg"))

        assertEquals(saved, store.find(work.id))
        assertEquals(2000 to 1500, saved?.width to saved?.height)
        assertEquals("base-2.jpg", saved?.baseName)
        assertEquals(store.baseFile(work.id, "base-2.jpg"), saved?.let(store::baseFile))
        assertEquals(2_000L, saved?.updatedAt)
        // 空白畫布沒有底圖：base 是 null，存了也不會有
        val blank = store.create(1440, 1920, hasBase = false)
        assertEquals(null, blank.sheet.base)
        assertEquals(null, store.saveSheet(blank.id, Sheet(Markup(), 1920, 1440))?.sheet?.base)
    }

    @Test
    fun oldTablesGetTheBaseNameColumn() {
        // 加 base_name 之前建的表
        val old = JdbcSql()
        old.execute(
            "CREATE TABLE works (id TEXT PRIMARY KEY, has_base INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, " +
                "markup TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        )
        old.execute("INSERT INTO works VALUES ('w', 1, 4000, 3000, '{\"nodes\":[]}', 1, 2)")
        try {
            val upgraded = WorkStore(old, dir)
            assertEquals(WorkStore.DEFAULT_BASE, upgraded.find("w")?.baseName)
            // 存到相簿的紀錄是後來才有的表：舊的資料庫一樣建得起來
            upgraded.addGalleryImage("w", "content://media/external/images/media/1")
            assertEquals(listOf("content://media/external/images/media/1"), upgraded.galleryImages("w"))
            // 再開一次不會重複加欄位
            assertEquals(1, WorkStore(old, dir).all().size)
        } finally {
            old.close()
        }
    }

    @Test
    fun removeUnusedBasesKeepsOnlyTheCurrentOne() {
        val work = store.create(100, 100, hasBase = true)
        listOf("base.jpg", "base-1.jpg", "base-2.jpg", "base-2.jpg.tmp", "thumb.jpg").forEach { store.baseFile(work.id, it).writeText("x") }

        store.removeUnusedBases(work.id, keep = "base-1.jpg")

        assertEquals(setOf("base-1.jpg", "thumb.jpg"), store.dir(work.id).list()?.toSet())
    }
}
