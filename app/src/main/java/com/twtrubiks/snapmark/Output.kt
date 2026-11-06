package com.twtrubiks.snapmark

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * 成品圖（存到相簿、分享）與作品縮圖的規則，純 Kotlin（JVM 測試 OutputTest）。
 * 畫成 Bitmap、寫進相簿在 Android 那一層（Render.kt、Export.kt）。
 */

/** 成品圖的格式：有底圖（相機、照片）的存 JPEG、空白畫布存 PNG（見 CLAUDE.md「存檔與分享」）。 */
enum class OutputFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    ;

    companion object {
        fun of(work: Work) = if (work.hasBase) JPEG else PNG
    }
}

/** 成品圖的 JPEG 品質。 */
const val OUTPUT_JPEG_QUALITY = 92

/** 作品分頁的縮圖（`thumb.jpg`）：長邊最多這麼多像素。 */
const val THUMB_MAX_SIDE = 512

/** 縮圖的 JPEG 品質：只在作品分頁看，小一點讀得快。 */
const val THUMB_JPEG_QUALITY = 85

private val OUTPUT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * 存到相簿、分享的檔名：`SnapMark-<YYYYMMDD-HHmmss>.<jpg|png>`（[time] 是存的時候，手機的時區）。
 * 同一秒存了兩張（[taken] 說這個名字已經有了）就在後面加 `-2`、`-3`…
 */
fun outputName(time: LocalDateTime, format: OutputFormat, taken: (String) -> Boolean = { false }): String {
    val stem = "SnapMark-${time.format(OUTPUT_TIME)}"
    var name = "$stem.${format.extension}"
    var n = 2
    while (taken(name)) name = "$stem-${n++}.${format.extension}"
    return name
}
