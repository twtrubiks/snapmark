package com.twtrubiks.snapmark

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.produceState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 首頁的分頁：左右滑換頁，分頁列在最下面。相機、照片、空白是三個入口，「作品」是存在手機裡、標註過的圖。
 */
enum class HomeTab(val title: String, val header: String, val subtext: String, val tag: String) {
    CAMERA("相機", "拍一張來標註", "用手機的相機拍照，拍完直接畫", "tab_camera"),
    PHOTOS("照片", "選一張照片來標註", "從手機裡的照片挑一張", "tab_photos"),
    BLANK("空白", "畫出你的想法", "不用找紙，從一張白紙開始", "tab_blank"),
    WORKS("作品", "作品", "標註過的圖都會在這裡，可以打開繼續改", "tab_works"),
}

/**
 * 四個分頁：相機、照片、空白都會開一張新的作品進畫布，「作品」是縮圖的格線（點一下打開、長按刪除）。
 * 最上面一排：App 的名字、右上角的深色模式（[theme]，選了呼叫 [onTheme]）。[works] 是 null 表示還在讀。
 * [thumbFile]：作品的縮圖檔（`WorkStore.thumbFile`）。[galleryCount]：那張作品存到相簿、還在相簿裡的有幾張（刪除時問要不要一起刪）；
 * [onDelete] 的第二個參數是使用者勾了「一併刪除存到相簿的圖」。[onOpen] 打開作品（id）。
 * [saved]：剛存到相簿、離開畫布的那張作品的 id：換到「作品」分頁、捲到最上面（那張排在最前面），
 * 下面提示「已存到相簿」，按「繼續改」打開它；開始提示時呼叫 [onSavedShown]（清掉，回到首頁時不會再提示一次）。
 */
@Composable
fun HomeScreen(
    works: List<Work>?,
    thumbFile: (String) -> File,
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onPhotosFromApp: () -> Unit,
    onBlank: () -> Unit,
    onOpen: (String) -> Unit,
    galleryCount: suspend (Work) -> Int,
    onDelete: (Work, withGallery: Boolean) -> Unit,
    saved: String? = null,
    onSavedShown: () -> Unit = {},
) {
    val pager = rememberPagerState { HomeTab.entries.size }
    val scope = rememberCoroutineScope()
    val worksGrid = rememberLazyStaggeredGridState()
    val snackbar = remember { SnackbarHostState() }
    val latestWorks by rememberUpdatedState(works)
    LaunchedEffect(saved) {
        val id = saved ?: return@LaunchedEffect
        // 提示放在 scope 裡：清掉 saved 時這個 LaunchedEffect 會被取消，提示不能跟著不見
        scope.launch {
            pager.scrollToPage(HomeTab.WORKS.ordinal)
            launch {
                // 重讀之後剛存的那張排到最前面，再捲到最上面（格線會留在原本看到的那一張，不會自己捲上去）
                withTimeoutOrNull(5_000) { snapshotFlow { latestWorks?.firstOrNull()?.id }.first { it == id } }
                worksGrid.scrollToItem(0)
            }
            val result = snackbar.showSnackbar("已存到相簿", actionLabel = "繼續改", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) onOpen(id)
        }
        onSavedShown()
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "SnapMark",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            ThemeMenu(theme, onTheme)
        }
        Box(Modifier.weight(1f)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                when (val tab = HomeTab.entries[page]) {
                    HomeTab.CAMERA -> StartPage(tab, R.drawable.ic_camera, "開相機", "camera_button", onCamera)
                    HomeTab.PHOTOS -> StartPage(tab, R.drawable.ic_photo_library, "選照片", "photos_button", onPhotos) {
                        TextButton(onClick = onPhotosFromApp, modifier = Modifier.testTag("photos_other_app")) {
                            Text("用其他 App 選")
                        }
                    }
                    HomeTab.BLANK -> StartPage(tab, R.drawable.ic_edit, "開一張空白畫布", "blank_button", onBlank)
                    HomeTab.WORKS -> WorksPage(tab, works, worksGrid, thumbFile, onOpen, galleryCount, onDelete)
                }
            }
            // 「已存到相簿」：蓋在分頁上、分頁列的上面（「繼續改」用 By.text 找）
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { data ->
                Snackbar(data, modifier = Modifier.testTag("saved_snackbar"))
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

/** 右上角的深色模式：圖示是目前的設定，點了選跟系統／淺色／深色（testTag `theme_button`、`theme_<設定>`）。 */
@Composable
private fun ThemeMenu(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("theme_button")) {
            Icon(painterResource(mode.icon), contentDescription = "深色模式：${mode.label}")
        }
        // 選單是另一個視窗：App 根部開的 testTagsAsResourceId 管不到，這裡再開一次
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
        ) {
            ThemeMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { Icon(painterResource(option.icon), contentDescription = null) },
                    trailingIcon = if (option == mode) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                    modifier = Modifier.testTag(option.tag).semantics { selected = option == mode },
                )
            }
        }
    }
}

private val ThemeMode.icon
    get() = when (this) {
        ThemeMode.SYSTEM -> R.drawable.ic_theme_system
        ThemeMode.LIGHT -> R.drawable.ic_light_mode
        ThemeMode.DARK -> R.drawable.ic_dark_mode
    }

/**
 * 入口分頁的樣子：上面一句大標、一句說明，中間一張示意圖（[StartArt]），下面一顆大的圓形按鈕，
 * 圓的下面還有一格放 [extra]（照片的「用其他 App 選」）。那一格每頁都留著：左右滑的時候圓形按鈕不會上下跳。
 * 橫的時候放不下：示意圖在左邊，字和按鈕在右邊。
 */
@Composable
private fun StartPage(
    tab: HomeTab,
    icon: Int,
    button: String,
    buttonTag: String,
    onClick: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                StartArt(tab, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    StartTitle(tab)
                    Spacer(Modifier.height(24.dp))
                    BigRoundButton(icon, button, buttonTag, onClick)
                    ExtraSlot(extra)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                StartTitle(tab)
                StartArt(tab, Modifier.weight(1f).widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 16.dp))
                BigRoundButton(icon, button, buttonTag, onClick)
                ExtraSlot(extra)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// 圓形按鈕下面那一格：一顆文字按鈕的高度，沒有東西也留著
@Composable
private fun ExtraSlot(content: @Composable () -> Unit) {
    Box(Modifier.padding(top = 4.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun StartTitle(tab: HomeTab) {
    Text(tab.header, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        tab.subtext,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * 大的圓形按鈕，字在下面。testTag 與 TalkBack 唸的都在圓上；字點了也算（跟圓共用按下去的效果），
 * 但 TalkBack 不另外唸它。
 */
@Composable
private fun BigRoundButton(icon: Int, label: String, tag: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 6.dp,
            interactionSource = interaction,
            modifier = Modifier.size(88.dp).testTag(tag).semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .clearAndSetSemantics {},
        )
    }
}

@Composable
private fun WorksPage(
    tab: HomeTab,
    works: List<Work>?,
    grid: LazyStaggeredGridState,
    thumbFile: (String) -> File,
    onOpen: (String) -> Unit,
    galleryCount: suspend (Work) -> Int,
    onDelete: (Work, withGallery: Boolean) -> Unit,
) {
    // 長按的那一張：問過才刪。轉螢幕時對話框留著
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
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
        // 每張照原本的比例（太細長的裁掉一點），最近改過的在前面
        else -> LazyVerticalStaggeredGrid(
            state = grid,
            columns = StaggeredGridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(16.dp),
            verticalItemSpacing = 12.dp,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().testTag("work_list"),
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Column(Modifier.padding(bottom = 4.dp)) {
                    Text(tab.header, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "點一下繼續改，長按可以刪除",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(works, key = { it.id }) { work ->
                WorkThumb(
                    work,
                    thumbFile(work.id),
                    onOpen = { onOpen(work.id) },
                    onLongPress = { deletingId = work.id },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
    val deleting = deletingId?.let { id -> works?.find { it.id == id } }
    if (deleting != null) {
        // 先查好相簿裡還有幾張（一下子）才出對話框：勾選那一格不會晚一點才冒出來
        val count by produceState<Int?>(null, deleting.id) { value = galleryCount(deleting) }
        count?.let { galleryImages ->
            DeleteDialog(
                galleryImages,
                onConfirm = { withGallery ->
                    deletingId = null
                    onDelete(deleting, withGallery)
                },
                onDismiss = { deletingId = null },
            )
        }
    }
}

/**
 * 一張作品的縮圖（`thumb.jpg`，畫上標註之後的樣子）。testTag 是 `work_<id>`；
 * 說明文字（TalkBack 唸的、測試用 `By.descContains` 找的）是「照片／空白畫布，N 個標註」。
 */
@Composable
private fun WorkThumb(work: Work, thumb: File, onOpen: () -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    AsyncImage(
        // 每存一次 updatedAt 就變：換一個 key，不會拿到記憶體裡舊的那張
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(thumb)
            .memoryCacheKey("thumb:${work.id}:${work.updatedAt}")
            .build(),
        contentDescription = "${if (work.hasBase) "照片" else "空白畫布"}，${work.markup.nodes.size} 個標註",
        contentScale = ContentScale.Crop,
        // 縮圖還沒寫好（剛離開畫布）是正常的，下次重讀就有；檔案在卻讀不出來才是問題（release 冒煙測試看這一行）
        onError = { if (thumb.exists()) Log.w("SnapMark", "縮圖讀不到：${work.id}", it.result.throwable) },
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio((work.width.toFloat() / work.height).coerceIn(0.5f, 2f))
            .clip(shape)
            // 縮圖讀到之前：空白畫布就是白的，照片先用灰色
            .background(if (work.hasBase) MaterialTheme.colorScheme.surfaceVariant else Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .combinedClickable(onClickLabel = "打開", onLongClickLabel = "刪除", onLongClick = onLongPress, onClick = onOpen)
            .testTag("work_${work.id}"),
    )
}

/**
 * 刪除前確認：資料夾整個刪掉，找不回來。對話框是另一個視窗，testTag 要再開一次。
 * 這張作品有存到相簿、相簿裡還在（[galleryImages] 張）的話，多一格「一併刪除存到相簿的 N 張圖」，預設不勾
 * （2026-11-05 使用者要的）。轉螢幕時勾的留著。
 */
@Composable
private fun DeleteDialog(galleryImages: Int, onConfirm: (withGallery: Boolean) -> Unit, onDismiss: () -> Unit) {
    var withGallery by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("刪除這張作品？") },
        text = {
            Column {
                Text(
                    when {
                        galleryImages == 0 -> "刪掉就找不回來了。"
                        withGallery -> "刪掉就找不回來了，存到相簿的圖也會一起刪掉。"
                        else -> "刪掉就找不回來了。存到相簿的圖會留著。"
                    },
                )
                if (galleryImages > 0) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = withGallery, role = Role.Checkbox, onValueChange = { withGallery = it })
                            .semantics { testTagsAsResourceId = true }
                            .testTag("delete_gallery"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = withGallery, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("一併刪除存到相簿的 $galleryImages 張圖")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(withGallery) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("delete_confirm"),
            ) {
                Text("刪除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("delete_cancel")) {
                Text("取消")
            }
        },
    )
}
