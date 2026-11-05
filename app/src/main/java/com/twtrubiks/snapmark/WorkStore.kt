package com.twtrubiks.snapmark

import java.io.File
import java.util.UUID

/**
 * 一張作品：底圖（相機拍的、相簿選的；空白畫布沒有底圖）＋上面的標註。可以重新打開繼續改。
 * [width]、[height] 是底圖（或空白畫布）的像素，標註的座標就是照這個大小。時間是 epoch 毫秒。
 */
data class Work(
    val id: String,
    val hasBase: Boolean,
    val width: Int,
    val height: Int,
    val markup: Markup,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 作品的規則與 SQL，純 Kotlin：手機上接 [AndroidSql]，JVM 測試接 sqlite-jdbc。
 * 檔案放在 [worksDir]`/<id>/`：`base.jpg`（底圖）、`thumb.jpg`（作品分頁用的縮圖），刪掉作品時整個資料夾一起刪。
 * 底圖怎麼轉正、縮小是 Android 那一層的事（還沒做），這裡只管檔案放哪裡。
 */
class WorkStore(
    private val sql: Sql,
    private val worksDir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init {
        sql.execute(
            """
            CREATE TABLE IF NOT EXISTS works (
                id TEXT PRIMARY KEY,
                has_base INTEGER NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                markup TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    fun dir(id: String) = File(worksDir, id)

    fun baseFile(id: String) = File(dir(id), "base.jpg")

    fun thumbFile(id: String) = File(dir(id), "thumb.jpg")

    /** 最近改過的在上面；同一毫秒改的，後建立的在上面。 */
    fun all(): List<Work> = sql.query("SELECT * FROM works ORDER BY updated_at DESC, rowid DESC").map(::toWork)

    fun find(id: String): Work? = sql.query("SELECT * FROM works WHERE id = ?", id).firstOrNull()?.let(::toWork)

    /** 開一張新的作品，還沒有任何標註。有底圖的話，呼叫的人接著把圖寫到 [baseFile]。 */
    fun create(width: Int, height: Int, hasBase: Boolean): Work {
        require(width > 0 && height > 0) { "畫布大小不對：$width×$height" }
        val time = now()
        val work = Work(UUID.randomUUID().toString(), hasBase, width, height, Markup(), time, time)
        sql.execute(
            "INSERT INTO works (id, has_base, width, height, markup, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            work.id,
            if (hasBase) 1 else 0,
            width,
            height,
            work.markup.toJson(),
            time,
            time,
        )
        dir(work.id).mkdirs()
        return work
    }

    /** 存標註。那張作品已經不在了回 null。 */
    fun saveMarkup(id: String, markup: Markup): Work? {
        val old = find(id) ?: return null
        val time = now()
        sql.execute("UPDATE works SET markup = ?, updated_at = ? WHERE id = ?", markup.toJson(), time, id)
        return old.copy(markup = markup, updatedAt = time)
    }

    fun delete(id: String) {
        sql.execute("DELETE FROM works WHERE id = ?", id)
        dir(id).deleteRecursively()
    }

    private fun toWork(row: Row) = Work(
        id = row.string("id"),
        hasBase = row.long("has_base") != 0L,
        width = row.long("width").toInt(),
        height = row.long("height").toInt(),
        markup = Markup.fromJson(row.string("markup")),
        createdAt = row.long("created_at"),
        updatedAt = row.long("updated_at"),
    )
}
