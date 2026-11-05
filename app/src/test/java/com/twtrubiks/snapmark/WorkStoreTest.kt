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
    private val store = WorkStore(sql, dir) { clock }

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
}
