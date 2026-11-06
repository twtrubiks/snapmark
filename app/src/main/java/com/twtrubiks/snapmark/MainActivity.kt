package com.twtrubiks.snapmark

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    // 別的 App 分享進來的圖（ACTION_SEND），SnapMarkApp 開成一張新的作品之後清掉
    private val shared = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 系統列透明；圖示的深淺之後由 SnapMarkTheme 跟著 App 的深色模式設定
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 轉螢幕、被系統收掉再回來時 intent 還是原本那一個：已經開過了，不要再開一次
        if (savedInstanceState == null) receive(intent)
        setContent {
            val image by shared.collectAsState()
            SnapMarkApp(sharedImage = image, onSharedImageTaken = { shared.value = null })
        }
    }

    // singleTask：App 開著的時候別的 App 分享進來，交給同一個 Activity
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    /** 別的 App「分享」一張圖進來（相簿、瀏覽器、截圖）：當成選了一張照片（見 CLAUDE.md「別的 App 分享圖片進來」）。 */
    private fun receive(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        // 從「最近使用」重開時 intent 還是上次分享進來的那一個：已經開過了，不要再開一次
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri != null) shared.value = uri
    }
}
