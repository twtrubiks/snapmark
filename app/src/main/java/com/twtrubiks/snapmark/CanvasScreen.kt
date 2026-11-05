package com.twtrubiks.snapmark

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 標註的畫面。骨架：只把作品讀出來、照比例畫一塊白色的畫布，工具都還沒做。
 * 要做成的樣子寫在 CLAUDE.md 的「畫布」：
 * 右下角工具鈕、左下角顏色與粗細、左上角復原／重做、右上角刪除，上面一排是返回、分享與選單（裁切、清除、旋轉）。
 */
@Composable
fun CanvasScreen(workId: String, onBack: () -> Unit) {
    val container = LocalContext.current.container
    val work by produceState<Work?>(null, workId) {
        value = withContext(Dispatchers.IO) { container.works.find(workId) }
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("canvas_back")) { Text("返回") }
        }
        // 畫布外面之後要畫深色細條紋；先用一個底色代替
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            work?.let {
                Box(
                    Modifier
                        .aspectRatio(it.width.toFloat() / it.height)
                        .background(Color.White)
                        .testTag("canvas"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("標註工具還沒做", color = Color.Gray)
                }
            }
        }
    }
}
