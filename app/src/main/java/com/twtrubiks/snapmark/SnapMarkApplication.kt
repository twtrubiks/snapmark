package com.twtrubiks.snapmark

import android.app.Application
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

class SnapMarkApplication : Application() {
    val container by lazy { AppContainer(this) }
}

val Context.container: AppContainer get() = (applicationContext as SnapMarkApplication).container

/** 整個 App 共用的東西：作品（資料庫＋檔案）、設定（深色模式、上次用的工具）。[context] 是 Application（存到相簿、分享要用）。 */
class AppContainer(val context: Context) {
    val worksDir = File(context.filesDir, "works")

    private val worksChanged = MutableStateFlow(0)

    /** 作品新增、存檔、刪除一次就加一：首頁看著它重讀作品。 */
    val worksRevision: StateFlow<Int> = worksChanged.asStateFlow()

    val works: WorkStore by lazy {
        WorkStore(AndroidSql(context), worksDir, onChange = { worksChanged.update { it + 1 } })
    }

    /**
     * 作品的讀寫一律排在這一條上，一次一件、照順序做：
     * 剛離開畫布還沒存完就馬上再打開同一張時，讀到的一定是存完的。
     */
    val worksDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** 離開畫面之後還要做完的事（最後一次存檔、刪掉沒畫東西的作品），跟著 App 的 process 走。 */
    val background = CoroutineScope(SupervisorJob() + worksDispatcher)

    /** [id] 這張作品存到相簿、而且相簿裡還在的圖。在 [worksDispatcher] 上呼叫。 */
    fun galleryImagesOf(id: String): List<Uri> =
        works.galleryImages(id).map(Uri::parse).filter { galleryImageExists(context, it) }

    /**
     * 刪掉作品；[withGallery] 的話先刪掉它存到相簿的圖。回傳相簿裡刪不掉的張數（要請使用者到相簿 App 刪）。
     * 在 [worksDispatcher] 上呼叫。
     */
    fun deleteWork(id: String, withGallery: Boolean): Int {
        val failed = if (withGallery) galleryImagesOf(id).count { !deleteFromGallery(context, it) } else 0
        works.delete(id)
        return failed
    }

    private val settings by lazy { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    /** 深色模式：跟系統／淺色／深色。首頁右上角改，[SnapMarkTheme] 讀。 */
    val theme by lazy { EnumSetting(settings, "theme", ThemeMode.entries, ThemeMode.SYSTEM) }

    /** 上次用的工具、顏色、粗細：打開畫布時用（第一次是筆），畫布上選了就記下來。 */
    val tools by lazy { ToolMemory(settings) }

    /**
     * 畫布分兩層畫（`DrawingSurface`）：已經畫好的放在快取的圖層，每一格只重畫正在畫的那一筆。
     * 只給 `PerfTest` 改成 false，在同一台模擬器上量改之前的畫法（每一格全部重畫）來比較；打開畫布之前設好。
     */
    var splitCanvasLayers = true
}
