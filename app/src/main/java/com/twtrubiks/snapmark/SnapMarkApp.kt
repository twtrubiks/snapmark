package com.twtrubiks.snapmark

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 畫面。首頁是四個分頁，畫布從它往裡走。[key] 用來分開每一頁的 rememberSaveable。 */
private sealed interface Route {
    val key: String

    data object Home : Route {
        override val key = "home"
    }

    /** 標註一張作品。相機、照片、空白都是先開一張作品（[WorkStore.create]）再進來。 */
    data class Canvas(val workId: String) : Route {
        override val key get() = "canvas-$workId"
    }
}

/** 轉螢幕、切到背景再回來時，走到哪一頁要留著。 */
private val StackSaver = listSaver<List<Route>, String>(
    save = { stack ->
        stack.map {
            when (it) {
                Route.Home -> "home"
                is Route.Canvas -> "canvas|${it.workId}"
            }
        }
    },
    restore = { saved ->
        saved.map {
            val parts = it.split("|", limit = 2)
            when (parts[0]) {
                "canvas" -> Route.Canvas(parts[1])
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
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SnapMarkApp() {
    val container = LocalContext.current.container
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Route>(Route.Home)) }
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    // 每換一頁（標註完回來）重讀一次作品；讀的時候畫面上留著舊的，不會閃一下空白
    val works by produceState<List<Work>?>(null, stack) {
        value = withContext(Dispatchers.IO) { container.works.all() }
    }

    fun push(route: Route) {
        stack = stack + route
    }

    fun close(route: Route) {
        stack = (stack - route).ifEmpty { listOf(Route.Home) }
        holder.removeState(route.key)
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
                            onBlank = {
                                scope.launch {
                                    val work = withContext(Dispatchers.IO) {
                                        container.works.create(BLANK_WIDTH, BLANK_HEIGHT, hasBase = false)
                                    }
                                    push(Route.Canvas(work.id))
                                }
                            },
                            onOpen = { push(Route.Canvas(it.id)) },
                        )
                        is Route.Canvas -> CanvasScreen(workId = route.workId, onBack = { close(route) })
                    }
                }
            }
        }
    }
}
