package com.twtrubiks.snapmark

import android.content.ClipData
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * 成品圖送出 App：存到相簿、交給系統的分享選單（見 CLAUDE.md「存檔與分享」）。都不會動到作品。
 * 在背景執行緒呼叫（寫檔）。
 */

/** 相簿裡放成品圖的資料夾：`Pictures/SnapMark/`。 */
const val GALLERY_FOLDER = "SnapMark"

/**
 * 存到相簿的 `Pictures/SnapMark/`，回傳那張圖在 MediaStore 的網址。
 * Android 10 以上直接寫 MediaStore，不需要權限；Android 9 要先拿到 `WRITE_EXTERNAL_STORAGE`（畫面那邊問），
 * 直接寫檔案再請系統掃描（掃完相簿才看得到）。
 */
suspend fun saveToGallery(context: Context, image: Bitmap, format: OutputFormat, time: LocalDateTime = LocalDateTime.now()): Uri =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveToMediaStore(context, image, format, time)
    } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), GALLERY_FOLDER)
        val file = File(dir, outputName(time, format) { File(dir, it).exists() })
        writeImage(image, file, format)
        scan(context, file, format)
    }

// 先標成「還在寫」（IS_PENDING）：寫好之前相簿、別的 App 看不到半張圖。同一秒的檔名 MediaStore 會自己加編號
@RequiresApi(Build.VERSION_CODES.Q)
private fun saveToMediaStore(context: Context, image: Bitmap, format: OutputFormat, time: LocalDateTime): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, outputName(time, format))
        put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: throw IOException("相簿不讓我們新增圖片")
    try {
        val out = resolver.openOutputStream(uri) ?: throw IOException("打不開相簿裡的檔案：$uri")
        out.use { image.compressTo(it, format) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
    return uri
}

// Android 9：寫好的檔案交給系統掃描，掃完才在相簿裡
private suspend fun scan(context: Context, file: File, format: OutputFormat): Uri = suspendCancellableCoroutine { cont ->
    MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.path), arrayOf(format.mimeType)) { _, uri ->
        if (uri != null) cont.resume(uri) else cont.resumeWithException(IOException("相簿掃不到 $file"))
    }
}

/**
 * 相簿裡還找得到 [uri] 這張嗎（使用者可能已經在相簿 App 刪掉了）。查不到、沒有權限查（Android 9 把權限收回去了）都算不在：
 * 那樣也刪不掉，刪除作品時不列進「一併刪除」。
 */
fun galleryImageExists(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.moveToFirst() } ?: false
} catch (_: Exception) {
    false
}

/**
 * 從相簿刪掉 SnapMark 存的一張圖，刪掉了回 true。
 * Android 10 以上，App 自己存進 MediaStore 的圖不用權限、也不用系統再問一次就刪得掉；
 * Android 9 要 `WRITE_EXTERNAL_STORAGE`（存的時候問過了）。刪不掉（例如權限被收回去）回 false，請使用者到相簿 App 刪。
 */
fun deleteFromGallery(context: Context, uri: Uri): Boolean {
    val resolver = context.contentResolver
    return try {
        // Android 9 存的是一個真的檔案：記下路徑，MediaStore 那一筆刪掉之後檔案還在的話自己刪
        @Suppress("DEPRECATION")
        val path = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            resolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        } else {
            null
        }
        val deleted = resolver.delete(uri, null, null) > 0
        path?.let(::File)?.takeIf { it.exists() }?.delete()
        deleted
    } catch (_: Exception) {
        false
    }
}

/** 要分享的成品圖先放這裡（`res/xml/file_paths.xml` 的 `share`），再用 FileProvider 交給別的 App。 */
fun shareDir(context: Context) = File(context.cacheDir, "share")

/**
 * 寫一張要分享的成品圖，回傳給別的 App 讀的網址（FileProvider）。
 * 一小時以前分享的先刪掉：收到的 App 可能還在讀剛剛那一張（例如還在上傳），不馬上刪。
 */
fun writeShareFile(context: Context, image: Bitmap, format: OutputFormat, time: LocalDateTime = LocalDateTime.now()): Uri {
    val dir = shareDir(context)
    val old = System.currentTimeMillis() - SHARE_KEEP_MILLIS
    dir.listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
    val file = File(dir, outputName(time, format) { File(dir, it).exists() })
    writeImage(image, file, format)
    return FileProvider.getUriForFile(context, context.fileProviderAuthority, file)
}

private const val SHARE_KEEP_MILLIS = 60 * 60 * 1000L

/** 系統的分享選單：把 [uri] 那張圖交給使用者選的 App（只給讀的權限）。選單裡不列 SnapMark 自己。 */
fun shareChooser(context: Context, uri: Uri, format: OutputFormat): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = format.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        // 分享選單上的預覽圖也要讀得到
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, null).apply {
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    }
}
