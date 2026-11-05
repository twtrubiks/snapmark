package com.twtrubiks.snapmark

import android.app.Application
import android.content.Context
import java.io.File

class SnapMarkApplication : Application() {
    val container by lazy { AppContainer(this) }
}

val Context.container: AppContainer get() = (applicationContext as SnapMarkApplication).container

/** 整個 App 共用的東西：作品（資料庫＋檔案）、設定。 */
class AppContainer(private val context: Context) {
    val worksDir = File(context.filesDir, "works")

    val works: WorkStore by lazy { WorkStore(AndroidSql(context), worksDir) }

    private val settings by lazy { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    /** 深色模式：跟系統／淺色／深色。切換的按鈕還沒做，先放著給 [SnapMarkTheme] 讀。 */
    val theme by lazy { EnumSetting(settings, "theme", ThemeMode.entries, ThemeMode.SYSTEM) }
}
