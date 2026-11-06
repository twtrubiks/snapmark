package com.twtrubiks.snapmark

import java.io.File
import java.util.UUID

/**
 * 一張作品：底圖（相機拍的、相簿選的；空白畫布沒有底圖）＋上面的標註。可以重新打開繼續改。
 * [width]、[height] 是底圖（或空白畫布）的像素，標註的座標就是照這個大小。時間是 epoch 毫秒。
 * [baseName]：底圖是資料夾裡的哪一個檔（裁切、旋轉會寫一張新的，見 [WorkStore.saveSheet]）。
 */
data class Work(
    val id: String,
    val hasBase: Boolean,
    val width: Int,
    val height: Int,
    val markup: Markup,
    val createdAt: Long,
    val updatedAt: Long,
    val baseName: String = WorkStore.DEFAULT_BASE,
) {
    /** 編輯用的樣子：空白畫布的 base 是 null。 */
    val sheet get() = Sheet(markup, width, height, if (hasBase) baseName else null)
}

/**
 * 作品的規則與 SQL，純 Kotlin：手機上接 [AndroidSql]，JVM 測試接 sqlite-jdbc。
 * 檔案放在 [worksDir]`/<id>/`：底圖（匯入時是 `base.jpg`，裁切、旋轉之後是 `base-<uuid>.jpg`，用哪一張記在 `base_name`）、
 * `thumb.jpg`（作品分頁用的縮圖），刪掉作品時整個資料夾一起刪。
 * 存到相簿的成品圖不在這裡（在相簿，刪作品也不會動到）：`gallery_images` 只記下網址，刪作品時讓使用者選要不要一起刪。
 * 底圖怎麼轉正、縮小、裁切是 Android 那一層的事（BaseImage.kt），這裡只管檔案放哪裡。
 *
 * @param onChange 新增、存檔、刪除之後呼叫（在做這件事的那個執行緒），給首頁知道要重讀
 */
class WorkStore(
    private val sql: Sql,
    private val worksDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val onChange: () -> Unit = {},
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
                updated_at INTEGER NOT NULL,
                base_name TEXT NOT NULL DEFAULT 'base.jpg'
            )
            """.trimIndent(),
        )
        // 裁切、旋轉才加的欄位：之前建的表補上，已經有的作品都是 base.jpg
        if (sql.query("PRAGMA table_info(works)").none { it.string("name") == "base_name" }) {
            sql.execute("ALTER TABLE works ADD COLUMN base_name TEXT NOT NULL DEFAULT '$DEFAULT_BASE'")
        }
        // 存到相簿的成品圖（MediaStore 的網址），一張作品可以存好幾次。之前沒有這張表的資料庫一樣建得起來
        sql.execute(
            """
            CREATE TABLE IF NOT EXISTS gallery_images (
                work_id TEXT NOT NULL,
                uri TEXT NOT NULL,
                saved_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        sql.execute("CREATE INDEX IF NOT EXISTS gallery_images_work ON gallery_images (work_id)")
    }

    fun dir(id: String) = File(worksDir, id)

    /** 底圖檔。匯入時寫 [DEFAULT_BASE]。 */
    fun baseFile(id: String, name: String = DEFAULT_BASE) = File(dir(id), name)

    fun baseFile(work: Work) = baseFile(work.id, work.baseName)

    /** 裁切、旋轉時新的底圖用的檔名（舊的那張留著給復原，離開畫布時 [removeUnusedBases]）。 */
    fun newBaseName() = "base-${UUID.randomUUID()}.jpg"

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
        onChange()
        return work
    }

    /** 存標註。那張作品已經不在了回 null。 */
    fun saveMarkup(id: String, markup: Markup): Work? {
        val old = find(id) ?: return null
        val time = now()
        sql.execute("UPDATE works SET markup = ?, updated_at = ? WHERE id = ?", markup.toJson(), time, id)
        onChange()
        return old.copy(markup = markup, updatedAt = time)
    }

    /**
     * 整份存：標註、大小、底圖是哪一張（裁切、旋轉、復原時）。底圖檔要先寫好：這一句改了 base_name 之後，
     * 下次打開讀的就是那一張。那張作品已經不在了回 null。
     */
    fun saveSheet(id: String, sheet: Sheet): Work? {
        val old = find(id) ?: return null
        val time = now()
        val baseName = sheet.base ?: old.baseName
        sql.execute(
            "UPDATE works SET markup = ?, width = ?, height = ?, base_name = ?, updated_at = ? WHERE id = ?",
            sheet.markup.toJson(),
            sheet.width,
            sheet.height,
            baseName,
            time,
            id,
        )
        onChange()
        return old.copy(markup = sheet.markup, width = sheet.width, height = sheet.height, baseName = baseName, updatedAt = time)
    }

    /** 刪掉資料夾裡沒在用的底圖（復原用的舊底圖、寫到一半的暫存檔），只留 [keep]。離開畫布時呼叫。 */
    fun removeUnusedBases(id: String, keep: String) {
        dir(id).listFiles()
            ?.filter { it.name != keep && it.name.startsWith("base") && (it.name.endsWith(".jpg") || it.name.endsWith(".tmp")) }
            ?.forEach { it.delete() }
    }

    /** 記下 [id] 這張作品存到相簿的一張圖（[uri] 是 MediaStore 的網址）。不算改了作品：不呼叫 onChange。 */
    fun addGalleryImage(id: String, uri: String) {
        sql.execute("INSERT INTO gallery_images (work_id, uri, saved_at) VALUES (?, ?, ?)", id, uri, now())
    }

    /** [id] 存到相簿過的圖，先存的在前面。相簿裡可能已經被使用者刪掉了，要用的人自己確認還在不在。 */
    fun galleryImages(id: String): List<String> =
        sql.query("SELECT uri FROM gallery_images WHERE work_id = ? ORDER BY saved_at, rowid", id).map { it.string("uri") }

    /** 刪掉作品：資料、資料夾、存到相簿的紀錄（相簿裡的圖要不要刪，是呼叫的人先決定、先刪的）。 */
    fun delete(id: String) {
        sql.transaction {
            sql.execute("DELETE FROM works WHERE id = ?", id)
            sql.execute("DELETE FROM gallery_images WHERE work_id = ?", id)
        }
        dir(id).deleteRecursively()
        onChange()
    }

    private fun toWork(row: Row) = Work(
        id = row.string("id"),
        hasBase = row.long("has_base") != 0L,
        width = row.long("width").toInt(),
        height = row.long("height").toInt(),
        markup = Markup.fromJson(row.string("markup")),
        createdAt = row.long("created_at"),
        updatedAt = row.long("updated_at"),
        baseName = row.string("base_name"),
    )

    companion object {
        /** 匯入時底圖的檔名。 */
        const val DEFAULT_BASE = "base.jpg"
    }
}
