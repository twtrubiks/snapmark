package com.twtrubiks.snapmark

import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 畫面。首頁是四個分頁，畫布從它往裡走。[key] 用來分開每一頁的 rememberSaveable。 */
private sealed interface Route {
    val key: String

    data object Home : Route {
        override val key = "home"
    }

    /**
     * 標註一張作品。相機、照片、空白都是先開一張作品（[WorkStore.create]）再進來（[isNew]），
     * 從「作品」分頁打開的是舊的。
     */
    data class Canvas(val workId: String, val isNew: Boolean) : Route {
        override val key get() = "canvas-$workId"
    }
}

/** 轉螢幕、切到背景再回來時，走到哪一頁要留著。 */
private val StackSaver = listSaver<List<Route>, String>(
    save = { stack ->
        stack.map {
            when (it) {
                Route.Home -> "home"
                is Route.Canvas -> "canvas|${if (it.isNew) 1 else 0}|${it.workId}"
            }
        }
    },
    restore = { saved ->
        saved.map {
            val parts = it.split("|", limit = 3)
            when (parts[0]) {
                "canvas" -> Route.Canvas(parts[2], isNew = parts[1] == "1")
                else -> Route.Home
            }
        }
    },
)

// 空白畫布的大小：直向 3:4。大小固定（不照螢幕），同一張作品在每支手機上都一樣大
private const val BLANK_WIDTH = 1440
private const val BLANK_HEIGHT = 1920

/**
 * 整個 App。導覽：sealed interface 的 Route ＋ rememberSaveable 的 stack，不另外引入 Navigation 套件。
 * [sharedImage]：別的 App 分享進來的圖（[MainActivity] 收到的），開成一張新的作品，開了就呼叫 [onSharedImageTaken]。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SnapMarkApp(sharedImage: Uri? = null, onSharedImageTaken: () -> Unit = {}) {
    val container = LocalContext.current.container
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Route>(Route.Home)) }
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    // 作品一有變動（畫布上存檔、離開時刪掉沒畫東西的）就重讀；讀的時候畫面上留著舊的，不會閃一下空白。
    // 只在首頁看得到的時候讀：在畫布上每畫一筆就存一次，那時候重讀（每一張的標註都要解開）是白做工，
    // 還會跟存檔排在同一條上。回到首頁時再讀一次
    val revision by container.worksRevision.collectAsState()
    val onHome = stack.last() == Route.Home
    val works by produceState<List<Work>?>(null, revision, onHome) {
        if (onHome) value = withContext(container.worksDispatcher) { container.works.all() }
    }

    // 剛存到相簿、離開畫布的那張作品：首頁換到「作品」分頁，提示「已存到相簿」＋「繼續改」，提示過就清掉
    var savedWork by rememberSaveable { mutableStateOf<String?>(null) }

    fun push(route: Route) {
        stack = stack + route
    }

    fun close(route: Route) {
        stack = (stack - route).ifEmpty { listOf(Route.Home) }
        holder.removeState(route.key)
    }

    // 相機拍的、照片選的、別的 App 分享進來的：讀圖、轉正縮小、存成新的作品，再進畫布。讀的時候蓋一層「正在讀取照片」
    var importing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val open: (ImageDecoder.Source, () -> Unit) -> Unit = { source, cleanUp ->
        importing = true
        scope.launch {
            try {
                val work = container.importPhoto(source)
                push(Route.Canvas(work.id, isNew = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 不是圖、壞掉的檔案、讀取權限沒了
                Log.w("SnapMark", "讀不了這張照片", e)
                Toast.makeText(context, "讀不了這張照片", Toast.LENGTH_SHORT).show()
            } finally {
                importing = false
                cleanUp()
            }
        }
    }
    val photos = rememberPhotoSources(open)
    LaunchedEffect(sharedImage) {
        val uri = sharedImage ?: return@LaunchedEffect
        onSharedImageTaken()
        // 分享進來的讀取權限跟著這個 Activity：馬上讀進 App 自己的空間
        open(ImageDecoder.createSource(context.contentResolver, uri)) {}
    }

    SnapMarkTheme(container.theme.value) {
        // 底色畫到系統列後面，內容再讓開系統列與鍵盤
        Surface(
            Modifier
                .fillMaxSize()
                // 讓 UiAutomator 用 By.res(...) 找得到 testTag
                .semantics { testTagsAsResourceId = true },
        ) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val route = stack.last()
                holder.SaveableStateProvider(route.key) {
                    when (route) {
                        Route.Home -> HomeScreen(
                            works = works,
                            thumbFile = container.works::thumbFile,
                            theme = container.theme.value,
                            onTheme = container.theme::set,
                            onCamera = photos.takePhoto,
                            onPhotos = photos.pickPhoto,
                            onPhotosFromApp = photos.pickFromApp,
                            onBlank = {
                                scope.launch {
                                    val work = withContext(container.worksDispatcher) {
                                        container.works.create(BLANK_WIDTH, BLANK_HEIGHT, hasBase = false)
                                    }
                                    push(Route.Canvas(work.id, isNew = true))
                                }
                            },
                            onOpen = { push(Route.Canvas(it, isNew = false)) },
                            saved = savedWork,
                            onSavedShown = { savedWork = null },
                            galleryCount = { works -> withContext(container.worksDispatcher) { works.sumOf { container.galleryImagesOf(it.id).size } } },
                            // 資料夾（底圖、縮圖）整個刪掉；離開首頁也會刪完
                            onDelete = { works, withGallery ->
                                container.background.launch {
                                    val failed = works.sumOf { container.deleteWork(it.id, withGallery) }
                                    if (failed > 0) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(container.context, "相簿裡有 $failed 張圖刪不掉，請到相簿 App 刪除", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                        )
                        is Route.Canvas -> CanvasScreen(
                            workId = route.workId,
                            isNew = route.isNew,
                            onBack = { close(route) },
                            onSaved = {
                                savedWork = route.workId
                                close(route)
                            },
                        )
                    }
                }
                if (importing) BusyOverlay("正在讀取照片…", "importing")
            }
        }
    }
}

/** 要等一兩秒的事（讀照片、畫成品圖）：蓋住整個畫面，也擋住點擊。[tag] 給測試找。 */
@Composable
fun BusyOverlay(text: String, tag: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(text)
            }
        }
    }
}
