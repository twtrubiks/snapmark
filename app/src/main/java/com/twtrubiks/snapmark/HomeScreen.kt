package com.twtrubiks.snapmark

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 首頁的分頁：左右滑換頁，分頁列在最下面。相機、照片、空白是三個入口，「作品」是存在手機裡、標註過的圖。
 */
enum class HomeTab(val title: String, val header: String, val subtext: String, val tag: String) {
    CAMERA("相機", "拍一張來標註", "用手機的相機拍照，拍完直接畫", "tab_camera"),
    PHOTOS("照片", "選一張照片來標註", "從手機裡的照片挑一張", "tab_photos"),
    BLANK("空白", "畫出你的想法", "不用找紙，從一張白紙開始", "tab_blank"),
    WORKS("作品", "作品", "標註過的圖都在這裡，可以打開繼續改", "tab_works"),
}

/**
 * 骨架：四個分頁切得動，「空白」真的會開一張作品進畫布，「作品」列得出來。
 * 相機、照片還沒接（按鈕先停用），每一頁的樣子之後照 CLAUDE.md 的規格做。
 * [works] 是 null 表示還在讀。
 */
@Composable
fun HomeScreen(works: List<Work>?, onBlank: () -> Unit, onOpen: (Work) -> Unit) {
    val pager = rememberPagerState { HomeTab.entries.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            when (val tab = HomeTab.entries[page]) {
                HomeTab.CAMERA -> StartPage(tab, button = "開相機", buttonTag = "camera_button", onClick = null)
                HomeTab.PHOTOS -> StartPage(tab, button = "選照片", buttonTag = "photos_button", onClick = null)
                HomeTab.BLANK -> StartPage(tab, button = "開一張空白畫布", buttonTag = "blank_button", onClick = onBlank)
                HomeTab.WORKS -> WorksPage(tab, works, onOpen)
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth()) {
            HomeTab.entries.forEachIndexed { index, tab ->
                TextButton(
                    onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    modifier = Modifier.weight(1f).testTag(tab.tag),
                ) {
                    Text(
                        tab.title,
                        color = if (pager.currentPage == index) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** 入口分頁的樣子：上面一句大標、一句說明，下面一顆大按鈕。[onClick] 是 null 表示還沒做。 */
@Composable
private fun StartPage(tab: HomeTab, button: String, buttonTag: String, onClick: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tab.header, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            tab.subtext,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onClick ?: {}, enabled = onClick != null, modifier = Modifier.testTag(buttonTag)) {
            Text(if (onClick != null) button else "$button（還沒做）")
        }
    }
}

@Composable
private fun WorksPage(tab: HomeTab, works: List<Work>?, onOpen: (Work) -> Unit) {
    when {
        works == null -> Unit
        works.isEmpty() -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("還沒有作品", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                tab.subtext,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // 之後換成縮圖的格線
        else -> LazyColumn(Modifier.fillMaxSize().testTag("work_list")) {
            items(works, key = { it.id }) { work ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(work) }.padding(16.dp)) {
                    Text(if (work.hasBase) "照片" else "空白畫布")
                    Text(
                        "${work.width}×${work.height}・${work.markup.nodes.size} 個標註",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
