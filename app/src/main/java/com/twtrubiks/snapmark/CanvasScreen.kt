package com.twtrubiks.snapmark

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.content.ContextCompat
import androidx.core.graphics.withTranslation
import kotlin.math.roundToInt

/**
 * 標註的畫面（CLAUDE.md 的「畫布」）：圖放在中間，外面是深色的細條紋底，四個角落是浮在圖上面的控制項：
 * 左上復原／重做、左下顏色與粗細、右下工具。上面一排是返回、存到相簿、分享、⋮（裁切、旋轉、清除標註），
 * 打字的時候換成「完成」，裁切的時候換成「取消」「完成」（四個角落的控制項先藏起來）。
 *
 * 一指照目前的工具畫（平移工具是拖動整張圖），兩指縮放平移（任何工具都可以）。
 * 存到相簿成功就離開畫布（[onSaved]，回到作品分頁）；分享、存失敗留在畫布。
 */
@Composable
fun CanvasScreen(workId: String, isNew: Boolean, onBack: () -> Unit, onSaved: () -> Unit) {
    val container = LocalContext.current.container
    // 轉螢幕時留著，離開畫布就丟掉（見 CanvasSession）
    val session = retain(workId) { CanvasSession(workId, isNew, container) }
    val leave = {
        session.finish()
        onBack()
    }
    // 手指移動時 editor 每一格都換一個新的：畫面上的按鈕只看 CanvasUi 那幾樣，沒變就不重組（畫的部分在 DrawingSurface 畫的階段才讀）
    val ui by remember(session) { derivedStateOf { CanvasUi.of(session.editor) } }
    val typing = ui.text != null
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    // 畫布那一塊的大小（螢幕像素），跟縮放平移一起決定作品畫在哪裡
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // 作品現在的大小（裁切、旋轉會變）看 editor
    val canvasFrame = { size: Size ->
        CanvasFrame(size.width, size.height, session.editor.width, session.editor.height, padding = with(density) { CANVAS_PADDING.toPx() })
    }
    val cropping = session.crop != null
    val finishText = {
        keyboard?.hide()
        session.finishText()
    }
    BackHandler(onBack = leave)
    // 打字的時候按返回鍵：當成按了「完成」，不離開畫布（後宣告的優先）
    BackHandler(enabled = typing, onBack = finishText)
    // 裁切的時候按返回鍵：取消裁切
    BackHandler(enabled = cropping, onBack = session::cancelCrop)
    // 正在畫成品圖、裁切旋轉的時候按返回鍵：不離開（離開會中斷到一半：相簿多一張圖、作品卻沒記到）
    BackHandler(enabled = session.busy) {}
    LaunchedEffect(session.missing) {
        if (session.missing) onBack()
    }
    val saveToGallery = rememberSaveToGallery { session.export(Export.SAVE) }
    ExportResults(session) {
        session.finish()
        onSaved()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (cropping) {
                        TextButton(onClick = session::cancelCrop, modifier = Modifier.testTag("crop_cancel")) {
                            Text("取消", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("拉四個角裁切", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = session::finishCrop, modifier = Modifier.testTag("crop_done")) {
                            Text("完成", style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        IconButton(onClick = leave, modifier = Modifier.testTag("canvas_back")) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
                        }
                        Spacer(Modifier.weight(1f))
                        if (typing) {
                            TextButton(onClick = finishText, modifier = Modifier.testTag("text_done")) {
                                Text("完成", style = MaterialTheme.typography.titleMedium)
                            }
                        } else if (session.work != null) {
                            ExportButton("存到相簿", R.drawable.ic_download, "save_button", onClick = saveToGallery)
                            ExportButton("分享", R.drawable.ic_share, "share_button") { session.export(Export.SHARE) }
                            MoreMenu(
                                canClear = ui.canClear,
                                onCrop = session::startCrop,
                                onRotate = session::rotate,
                                onClear = session::clear,
                            )
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().stripes()) {
                session.work?.let {
                    DrawingSurface(
                        session,
                        ui.width,
                        ui.height,
                        selectionColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it },
                    )
                    // 輸入框跟畫布是兄弟、不是子元件：點輸入框（移動游標）才不會被畫布的手勢當成點了別的地方
                    ui.text?.let { edit ->
                        if (viewSize != IntSize.Zero) {
                            TextEditBox(edit, canvasFrame(viewSize.toSize()).viewport(session.zoom), ui.width, ui.height, session::editText)
                        }
                        // 打字的時候鍵盤跳出來（畫面變矮）：把文字框平移到看得到的地方
                        // （這裡在 ui.text?.let 裡面：只有打字的時候才有）
                        LaunchedEffect(viewSize, edit.id) {
                            if (viewSize == IntSize.Zero) return@LaunchedEffect
                            val frame = canvasFrame(viewSize.toSize())
                            val margin = with(density) { 24.dp.toPx() }
                            val lineHeight = edit.fontSize(session.editor.width, session.editor.height) * 1.3f
                            val top = frame.reveal(session.zoom, edit.at, margin)
                            session.zoom = frame.reveal(top, edit.at + Point(0f, lineHeight), margin)
                        }
                    }
                }

                // 選到文字、印章時，上面的小泡泡（拖動的時候先不出現）
                val bubble = ui.bubble
                if (bubble != null && session.work != null && viewSize != IntSize.Zero && !cropping) {
                    SelectionBubble(bubble, ui.page, canvasFrame(viewSize.toSize()).viewport(session.zoom), session)
                }

                // 裁切的時候只有裁切框：復原、刪除、顏色、工具都先藏起來
                if (!cropping) {
                    // 選單展開時點畫布任何地方只是把它收起來，不會畫到東西
                    if (session.picker != null) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.15f))
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown().consume()
                                        session.picker = null
                                    }
                                },
                        )
                    }

                    // 打字的時候不出現：那時候的復原是丟掉正在打的字，容易誤會
                    if (!typing) {
                        Row(
                            Modifier.align(Alignment.TopStart).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (ui.canUndo) IconRoundButton(R.drawable.ic_undo, "復原", "undo", session::undo)
                            if (ui.canRedo) IconRoundButton(R.drawable.ic_redo, "重做", "redo", session::redo)
                        }
                    }

                    if (ui.selected != null && !typing) {
                        IconRoundButton(
                            R.drawable.ic_delete,
                            "刪除",
                            "delete",
                            session::deleteSelected,
                            Modifier.align(Alignment.TopEnd).padding(12.dp),
                        )
                    }

                    ColorPicker(
                        color = ui.tools.color,
                        size = ui.tools.size,
                        expanded = session.picker == Picker.COLOR,
                        onToggle = { session.togglePicker(Picker.COLOR) },
                        onPickColor = session::selectColor,
                        onPickSize = session::selectSize,
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    )
                    // 顏色選單往右展開的粗細在窄的手機上會碰到工具鈕：選顏色的時候先藏起來
                    if (session.picker != Picker.COLOR) {
                        ToolPicker(
                            choice = ui.tools,
                            tools = canvasTools(session.work?.hasBase == true),
                            expanded = session.picker == Picker.TOOL,
                            onToggle = { session.togglePicker(Picker.TOOL) },
                            onTool = session::selectTool,
                            onShape = session::selectShape,
                            onPen = session::selectPen,
                            onStamp = session::selectStamp,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        )
                    }
                }
            }
        }
        when (session.exporting) {
            Export.SAVE -> BusyOverlay("正在存到相簿…", "exporting")
            Export.SHARE -> BusyOverlay("正在準備圖片…", "exporting")
            null -> if (session.transforming) BusyOverlay("正在處理圖片…", "transforming")
        }
    }
}

/**
 * 上面一排的「存到相簿」、「分享」：各一顆，圖示加字，一眼就看得到
 * （2026-11-05 使用者要的：之前「存到相簿」收在「分享」的選單裡，找不到）。
 */
@Composable
private fun ExportButton(label: String, icon: Int, tag: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
        modifier = Modifier.testTag(tag),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label)
    }
}

/** 上面一排的 ⋮：裁切、向右轉、向左轉、清除標註（沒有標註時不能按）。 */
@Composable
private fun MoreMenu(canClear: Boolean, onCrop: () -> Unit, onRotate: (clockwise: Boolean) -> Unit, onClear: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("more_button")) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "更多")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
        ) {
            @Composable
            fun item(label: String, icon: Int, tag: String, enabled: Boolean = true, action: () -> Unit) = DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
                enabled = enabled,
                onClick = {
                    expanded = false
                    action()
                },
                modifier = Modifier.testTag(tag),
            )
            item("裁切", R.drawable.ic_crop, "menu_crop", action = onCrop)
            item("向右轉", R.drawable.ic_rotate_right, "menu_rotate_right") { onRotate(true) }
            item("向左轉", R.drawable.ic_rotate_left, "menu_rotate_left") { onRotate(false) }
            item("清除標註", R.drawable.ic_clear_all, "menu_clear", enabled = canClear, action = onClear)
        }
    }
}

/**
 * 「存到相簿」：Android 9 要先有寫入的權限，沒有就問（問完允許了才存）；Android 10 以上直接存。
 */
@Composable
private fun rememberSaveToGallery(save: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            save()
        } else {
            Toast.makeText(context, "沒有允許存取相片，存不進相簿", Toast.LENGTH_LONG).show()
        }
    }
    return {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            ask.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            save()
        }
    }
}

/**
 * 存到相簿好了呼叫 [onSaved]（離開畫布，「已存到相簿」在作品分頁提示）；分享的圖準備好了打開系統的分享選單；失敗的提示。
 */
@Composable
private fun ExportResults(session: CanvasSession, onSaved: () -> Unit) {
    val context = LocalContext.current
    val saved by rememberUpdatedState(onSaved)
    LaunchedEffect(session) {
        for (result in session.exported) {
            when (result) {
                Exported.Saved -> saved()
                is Exported.Share -> try {
                    context.startActivity(shareChooser(context, result.uri, result.format))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "找不到可以分享的 App", Toast.LENGTH_SHORT).show()
                }
                is Exported.Failed -> Toast.makeText(
                    context,
                    if (result.export == Export.SAVE) "存不進相簿" else "準備不了要分享的圖",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

/** 作品四邊至少留這麼多，看得到邊。測試也用它算作品在畫面上的位置。 */
val CANVAS_PADDING = 16.dp

/**
 * 畫作品、接手勢的那一塊（testTag `canvas`）。作品照 [CanvasFrame] 放在中間，縮放平移看 [CanvasSession.zoom]。
 * 選到的東西外面畫選取框與控制點（螢幕座標，粗細不跟著縮放）；裁切的時候畫裁切框。
 * 作品的大小看 [Editor.width]／[Editor.height]（裁切、旋轉之後會變），底圖是 [CanvasSession.base]。
 */
@Composable
private fun DrawingSurface(session: CanvasSession, width: Int, height: Int, selectionColor: Color, modifier: Modifier) {
    val base = session.base
    val painter = remember(width, height, base) { MarkupPainter(width, height, base) }
    val padding = with(LocalDensity.current) { CANVAS_PADDING.toPx() }
    val frameOf = { size: Size -> CanvasFrame(size.width, size.height, width, height, padding) }
    // 只有 PerfTest 會關掉：關掉時是改之前的畫法（一層，每一格全部重畫），量來比較用
    val split = LocalContext.current.container.splitCanvasLayers

    // 在畫的階段才讀 zoom、editor：手指移動時只重畫，不重組。分兩層（Editor.drawLayers）：
    Box(
        modifier
            .testTag("canvas")
            .pointerInput(session, width, height) {
                canvasGestures(session) { frameOf(Size(size.width.toFloat(), size.height.toFloat())) }
            },
    ) {
        // 底圖和已經畫好的：自己一個圖層，畫成一張圖留著（Offscreen）。正在畫一筆、拖著一個走的時候它們不會變，
        // 不用每一格重畫（畫了很多筆的作品，每一格都全部重畫會越畫越頓）。縮放平移、放開手指時才重畫
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = if (split) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
                .drawBehind {
                    val viewport = frameOf(size).viewport(session.zoom)
                    val nodes = if (split) session.settledNodes else session.editor.visibleNodes
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.withTranslation(viewport.offsetX, viewport.offsetY) {
                            scale(viewport.scale, viewport.scale)
                            painter.draw(this, nodes)
                        }
                    }
                },
        )
        // 正在畫、拖著走的那一個（和蓋在它上面的）、選取框、裁切框：每一格都畫。自己一個圖層，重畫時不牽動外面
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawBehind {
                    val viewport = frameOf(size).viewport(session.zoom)
                    val editor = session.editor
                    val live = if (split) editor.drawLayers.live else emptyList()
                    if (live.isNotEmpty()) {
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.withTranslation(viewport.offsetX, viewport.offsetY) {
                                scale(viewport.scale, viewport.scale)
                                painter.drawOnPage(this, live)
                            }
                        }
                    }
                    val crop = session.crop
                    if (crop != null) {
                        drawCrop(crop, viewport, selectionColor)
                    } else {
                        editor.selectedNode?.let { drawSelection(it, editor.page, viewport, selectionColor) }
                    }
                },
        )
    }
}

/**
 * 畫布上的按鈕、輸入框、泡泡要看的那幾樣。手指移動時 [Editor] 每一格都換一個新的，這幾樣大多不變：
 * 用 derivedStateOf 包起來，沒變就不重組整個畫面。
 *
 * @property bubble 選到文字、印章時泡泡跟著的那一個；拖動的時候是 null（泡泡先不出現）
 */
private data class CanvasUi(
    val width: Int,
    val height: Int,
    val text: TextEdit?,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val selected: String?,
    val bubble: Node?,
    val page: Page,
    val canClear: Boolean,
    val tools: ToolChoice,
) {
    companion object {
        fun of(e: Editor) = CanvasUi(
            width = e.width,
            height = e.height,
            text = e.text,
            canUndo = e.canUndo,
            canRedo = e.canRedo,
            selected = e.selected,
            bubble = e.selectedNode?.takeIf { e.gesture == null },
            page = e.page,
            canClear = e.markup.nodes.isNotEmpty(),
            tools = e.toolChoice,
        )
    }
}

/** 裁切框：框外面調暗、白色的框與三等分的細線、四個角的控制點。 */
private fun DrawScope.drawCrop(crop: Crop, viewport: Viewport, color: Color) {
    val tl = viewport.toScreen(Point(crop.box.left, crop.box.top))
    val br = viewport.toScreen(Point(crop.box.right, crop.box.bottom))
    clipRect(tl.x, tl.y, br.x, br.y, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.6f)) }
    val w = br.x - tl.x
    val h = br.y - tl.y
    val thin = Stroke(1.dp.toPx())
    for (i in 1..2) {
        drawLine(Color.White.copy(alpha = 0.6f), Offset(tl.x + w * i / 3, tl.y), Offset(tl.x + w * i / 3, br.y), thin.width)
        drawLine(Color.White.copy(alpha = 0.6f), Offset(tl.x, tl.y + h * i / 3), Offset(br.x, tl.y + h * i / 3), thin.width)
    }
    drawRect(Color.White, Offset(tl.x, tl.y), Size(w, h), style = Stroke(2.dp.toPx()))
    val radius = 10.dp.toPx()
    for (handle in crop.handles) {
        val c = viewport.toScreen(handle).let { Offset(it.x, it.y) }
        drawCircle(Color.White, radius, c)
        drawCircle(color, radius, c, style = Stroke(3.dp.toPx()))
    }
}

/** 選取框（白底的虛線，在深色、淺色的圖上都看得到）與控制點。 */
private fun DrawScope.drawSelection(node: Node, page: Page, viewport: Viewport, color: Color) {
    val b = node.bounds(page)
    val topLeft = viewport.toScreen(Point(b.left, b.top))
    val bottomRight = viewport.toScreen(Point(b.right, b.bottom))
    val pad = 6.dp.toPx()
    val origin = Offset(topLeft.x - pad, topLeft.y - pad)
    val size = Size(bottomRight.x - topLeft.x + 2 * pad, bottomRight.y - topLeft.y + 2 * pad)
    drawRect(Color.White, origin, size, style = Stroke(3.dp.toPx()))
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
    drawRect(color, origin, size, style = Stroke(1.5.dp.toPx(), pathEffect = dash))
    val radius = 9.dp.toPx()
    for (handle in node.handles()) {
        val c = viewport.toScreen(handle).let { Offset(it.x, it.y) }
        drawCircle(Color.White, radius, c)
        drawCircle(color, radius, c, style = Stroke(2.5.dp.toPx()))
    }
}

/**
 * 選到文字、印章時浮在它上面的小泡泡：文字切換卡通字／純色字；印章加尾巴、加文字。
 * 上面放不下就放在下面，左右不超出畫面。
 */
@Composable
private fun SelectionBubble(node: Node, page: Page, viewport: Viewport, session: CanvasSession) {
    if (node !is Node.Text && node !is Node.Stamp) return
    val b = node.bounds(page)
    val topLeft = viewport.toScreen(Point(b.left, b.top))
    val bottomRight = viewport.toScreen(Point(b.right, b.bottom))
    val gap = with(LocalDensity.current) { 16.dp.roundToPx() }
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.layout { measurable, constraints ->
            val bubble = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            // 自己佔滿整塊畫布（沒有手勢，點得到下面的東西），泡泡放在選到的東西上面
            layout(constraints.maxWidth, constraints.maxHeight) {
                val above = topLeft.y.roundToInt() - gap - bubble.height
                val y = if (above >= margin) above else bottomRight.y.roundToInt() + gap
                val x = ((topLeft.x + bottomRight.x) / 2).roundToInt() - bubble.width / 2
                // 離畫面的邊留一點：選到的東西在最右邊時，泡泡貼著螢幕邊緣看起來像被切掉
                bubble.place(
                    x.coerceIn(margin, maxOf(margin, constraints.maxWidth - bubble.width - margin)),
                    y.coerceIn(margin, maxOf(margin, constraints.maxHeight - bubble.height - margin)),
                )
            }
        },
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (node) {
                is Node.Text -> {
                    BubbleButton("卡通字", "bubble_cartoon", selected = node.outlined) { if (!node.outlined) session.toggleOutline() }
                    BubbleButton("純色字", "bubble_plain", selected = !node.outlined) { if (node.outlined) session.toggleOutline() }
                }
                is Node.Stamp -> {
                    BubbleButton(if (node.tail == null) "加尾巴" else "拿掉尾巴", "bubble_tail", onClick = session::toggleTail)
                    BubbleButton(if (node.label == null) "加文字" else "改文字", "bubble_label", onClick = session::editLabel)
                }
            }
        }
    }
}

@Composable
private fun BubbleButton(label: String, tag: String, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current,
        modifier = Modifier.testTag(tag),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/**
 * 正在打的字：在文字的位置放一個輸入框（字級照目前的縮放），按「完成」之後才照標註的樣子（卡通字）畫上去。
 * 只在換行鍵換行，不會自動折行。
 */
@Composable
private fun TextEditBox(edit: TextEdit, viewport: Viewport, canvasWidth: Int, canvasHeight: Int, onChange: (String) -> Unit) {
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    val textPx = edit.fontSize(canvasWidth, canvasHeight) * viewport.scale
    val at = viewport.toScreen(edit.at)
    val inset = 6.dp
    val insetPx = with(density) { inset.roundToPx() }
    // 白字、黃字放在深色的框裡，其他放在淺色的框裡
    val light = edit.color == MarkupColor.WHITE || edit.color == MarkupColor.YELLOW
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = edit.text,
        onValueChange = onChange,
        textStyle = TextStyle(
            color = edit.color.compose,
            fontSize = with(density) { textPx.toSp() },
            fontWeight = FontWeight.Bold,
        ),
        cursorBrush = SolidColor(if (light) Color.White else Color.Black),
        modifier = Modifier
            .offset { IntOffset(at.x.roundToInt() - insetPx, at.y.roundToInt() - insetPx) }
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .background(if (light) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.8f), shape)
            .border(1.5.dp, edit.color.compose, shape)
            .padding(inset)
            .widthIn(min = with(density) { textPx.toDp() })
            .focusRequester(focus)
            .testTag("text_field"),
    )
    LaunchedEffect(edit.id) { focus.requestFocus() }
}

/**
 * 畫布的手勢：一指交給 [Editor]（照目前的工具畫、點一下選取、拖選到的東西），第二指一按下來就改成縮放平移，
 * 畫到一半的那一個作廢，直到手指全部放開。
 * 平移工具：一指拖動整張圖；但按在選到的東西上還是拖它，點一下還是選取。
 * 裁切的時候：一指是拉裁切框（[Crop]），兩指一樣縮放平移。
 */
private suspend fun PointerInputScope.canvasGestures(session: CanvasSession, frame: () -> CanvasFrame) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        fun toImage(position: Offset) = frame().viewport(session.zoom).toImage(Point(position.x, position.y))

        // touch slop 換算成底圖像素（縮放中不會變：第二指按下來就不畫了）
        fun slop() = viewConfiguration.touchSlop / frame().viewport(session.zoom).scale

        val cropping = session.crop != null
        if (cropping) session.cropPress(toImage(down.position), slop() * Editor.REACH) else session.press(toImage(down.position), slop())
        // 平移工具、而且沒有抓到選到的東西：一指是拖動整張圖
        val panning = !cropping && session.editor.tool == Tool.PAN && session.editor.gesture?.grab == Grab.Draw
        var transforming = false
        var time = down.uptimeMillis
        try {
            while (true) {
                val event = awaitPointerEvent()
                time = event.changes.first().uptimeMillis
                if (event.changes.none { it.pressed }) break
                if (!transforming && event.changes.count { it.pressed } >= 2) {
                    transforming = true
                    if (cropping) session.cropCancel() else session.cancelDraft()
                }
                val finger = event.changes.firstOrNull { it.id == down.id }
                if (transforming || panning) {
                    // 平移時圖跟著手指走，換算成底圖座標手指等於沒動：用螢幕距離判斷是拖動還是點一下
                    if (panning && finger != null && (finger.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        session.cancelDraft()
                    }
                    val focus = event.calculateCentroid(useCurrent = false)
                    if (focus.isSpecified) {
                        val pan = event.calculatePan()
                        session.zoom = frame().transform(
                            session.zoom,
                            pan = Point(pan.x, pan.y),
                            factor = event.calculateZoom(),
                            focus = Point(focus.x, focus.y),
                        )
                    }
                } else if (finger != null) {
                    if (cropping) session.cropDrag(toImage(finger.position)) else session.drag(toImage(finger.position), slop())
                }
                event.changes.forEach { it.consume() }
            }
        } finally {
            // 正常放開、或手勢被系統取消（轉螢幕、切到別的 App）：畫到一半的當成放開，不會丟掉。
            // 縮放、平移過的已經 cancelDraft，這裡什麼都不會發生
            if (cropping) session.cropRelease() else session.release(slop(), time)
        }
    }
}

/** 畫布外面的深色細條紋底。 */
private fun Modifier.stripes() = drawWithCache {
    val gap = 10.dp.toPx()
    val path = Path().apply {
        var x = -size.height
        while (x < size.width) {
            moveTo(x, size.height)
            lineTo(x + size.height, 0f)
            x += gap
        }
    }
    val line = Stroke(1.dp.toPx())
    onDrawBehind {
        drawRect(STRIPE_BACKGROUND)
        drawPath(path, STRIPE_LINE, style = line)
    }
}

private val STRIPE_BACKGROUND = Color(0xFF232323)
private val STRIPE_LINE = Color(0xFF2E2E2E)
