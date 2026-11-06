package com.twtrubiks.snapmark

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.RetainObserver
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 畫布下面兩顆圓鈕展開的選單。 */
enum class Picker { TOOL, COLOR }

/** 畫成品圖的兩個去處：存到相簿、交給系統的分享選單。 */
enum class Export { SAVE, SHARE }

/** 存好了、準備好了要畫面做的事（提示、打開分享選單）。 */
sealed interface Exported {
    data object Saved : Exported

    data class Share(val uri: Uri, val format: OutputFormat) : Exported

    data class Failed(val export: Export) : Exported
}

/**
 * 打開一次畫布的期間：編輯狀態（[Editor]，含復原／重做）、縮放的位置、展開的選單、裁切的框。
 *
 * [CanvasScreen] 用 `retain` 拿著它：轉螢幕時畫面重建、它留著（畫到一半的、工具與顏色、縮放、復原都不會丟）；
 * 離開畫布就丟掉，下次打開是新的一份（復原只記這一次打開的期間）。
 *
 * 規則都在 [Editor]，這裡只管讀作品、自動存檔（連縮圖）、存到相簿與分享、裁切與旋轉底圖、離開時收尾。
 *
 * 裁切、旋轉（見 Transform.kt）：在背景把底圖裁好、轉好，寫成一張新的底圖檔，再換掉 [editor] 與 [base]。
 * 舊的底圖檔留著給復原（復原、重做換到另一張底圖時再讀回來），離開畫布時刪掉沒在用的。
 *
 * @param isNew 這次才開的作品（從相機、照片、空白進來）：什麼都沒畫就離開的話直接刪掉
 */
class CanvasSession(
    private val workId: String,
    private val isNew: Boolean,
    private val container: AppContainer,
) : RetainObserver {
    private val store get() = container.works

    /** 剛打開時讀到的作品（id、有沒有底圖）。null 表示還在讀。現在的大小、底圖看 [editor]。 */
    var work by mutableStateOf<Work?>(null)
        private set

    /** 照片的底圖（空白畫布是 null），跟 [Editor.base] 是同一張。跟作品一起讀好才設 [work]：畫面一出來就有照片，不會先白一下。 */
    var base by mutableStateOf<BaseLayer?>(null)
        private set

    /** 作品已經不在了（例如被刪掉）：畫面直接返回。 */
    var missing by mutableStateOf(false)
        private set

    // 讀到作品之前先放一個空的（畫面在 work 讀到之前不畫）
    var editor by mutableStateOf(Editor(Markup(), 1, 1))
        private set

    /**
     * 底圖上已經畫好、手指移動時不會變的那一層（[Editor.drawLayers]）。derivedStateOf：editor 換了但這一層一樣時，
     * 讀它的地方（畫面上快取的圖層）不用重畫。
     */
    val settledNodes: List<Node> by derivedStateOf { editor.drawLayers.settled }

    var zoom by mutableStateOf(Zoom())

    var picker by mutableStateOf<Picker?>(null)

    /** 裁切中的框（null：不是在裁切）。 */
    var crop by mutableStateOf<Crop?>(null)
        private set

    /** 正在畫成品圖（存到相簿、準備分享），畫面蓋一層等它。 */
    var exporting by mutableStateOf<Export?>(null)
        private set

    /** 正在裁切、旋轉底圖，或復原時讀回另一張底圖：畫面蓋一層等它。 */
    var transforming by mutableStateOf(false)
        private set

    val busy get() = exporting != null || transforming

    /** 存好了、準備好了：畫面收到之後提示或打開分享選單。轉螢幕時畫面重建也收得到（排著等新的畫面）。 */
    val exported = Channel<Exported>(Channel.BUFFERED)

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 要存的整份樣子，與畫它縮圖的 [Thumbnailer]（大小、底圖要對得上）。 */
    private class SaveJob(val sheet: Sheet, val thumbs: Thumbnailer)

    // 存檔排隊：只留最新的一份，連續改很多次也只存最後那一份
    private val saves = Channel<SaveJob>(Channel.CONFLATED)
    private var saver: Job? = null
    private var finished = false

    // 這一次打開時存到相簿過：剛開的作品沒畫東西就存了，離開時也不刪（存完會回到作品分頁，那張要在）
    private var savedToGallery = false

    // 現在的大小、底圖的縮圖畫法（裁切、旋轉、換底圖時換一個）。在主執行緒換，只在存檔的那一條用
    private var thumbs: Thumbnailer? = null

    // 讀過的底圖（檔名 → 底圖）：剛裁切完按復原不用再讀一次檔。一張 4096 的照片 48 MB，只留兩張
    private val bases = object : LinkedHashMap<String, BaseLayer>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BaseLayer>) = size > 2
    }

    // 剛打開時的樣子：這次才開的作品回到這個樣子就離開的話刪掉
    private var initial: Sheet? = null

    override fun onRetained() {
        main.launch {
            val loaded = withContext(container.worksDispatcher) { store.find(workId) }
            if (loaded == null) {
                missing = true
                return@launch
            }
            // 讀 find 時已經排在作品那一條的後面：剛匯入的照片，底圖一定已經寫完了
            val sheet = loaded.sheet
            val layer = sheet.base?.let { name ->
                withContext(Dispatchers.IO) { BaseLayer.load(store.baseFile(workId, name), sheet.markup) }?.also { bases[name] = it }
            }
            thumbs = withContext(Dispatchers.IO) { Thumbnailer(sheet.width, sheet.height, layer) }
            base = layer
            initial = sheet
            // 工具、顏色、粗細用上次的（第一次是筆）
            editor = Editor(sheet.markup, sheet.width, sheet.height, sheet.base, measure = AndroidTextMeasure())
                .withToolChoice(container.tools.value.opening(loaded.hasBase))
            work = loaded
            // 存檔在 App 的 scope：離開畫布之後還沒存完的會存完
            saver = container.background.launch {
                for (job in saves) {
                    // 縮圖先寫好再存標註：存標註時首頁會重讀，讀到的縮圖已經是新的
                    writeThumb(job)
                    store.saveSheet(workId, job.sheet)
                }
            }
        }
    }

    private fun writeThumb(job: SaveJob) {
        // 作品已經被刪掉了：不要寫縮圖（會把資料夾建回來）
        if (!store.dir(workId).exists()) return
        try {
            job.thumbs.write(job.sheet.markup.nodes, store.thumbFile(workId))
        } catch (e: Exception) {
            // 縮圖寫不進去不影響存標註：作品分頁那一張先是舊的
            Log.w("SnapMark", "縮圖寫不進去：$workId", e)
        }
    }

    /**
     * 把現在的樣子畫成成品圖，存到相簿或準備分享，好了從 [exported] 通知畫面。正在做的話不理。
     * 畫的是已經畫好的標註（手指還沒放開的、正在打的字不算：那時候也按不到分享）。
     */
    fun export(what: Export) {
        val work = work ?: return
        if (busy) return
        exporting = what
        val sheet = editor.sheet
        val base = base
        val context = container.context
        main.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val image = renderWork(sheet.width, sheet.height, base, sheet.markup.nodes)
                    try {
                        val format = OutputFormat.of(work)
                        when (what) {
                            Export.SAVE -> {
                                val uri = saveToGallery(context, image, format)
                                // 記下來：刪這張作品時可以選要不要一起刪掉相簿裡的
                                withContext(container.worksDispatcher) { container.works.addGalleryImage(work.id, uri.toString()) }
                                Exported.Saved
                            }
                            Export.SHARE -> Exported.Share(writeShareFile(context, image, format), format)
                        }
                    } finally {
                        image.recycle()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 空間不夠、相簿不讓寫、記憶體不夠畫整張
                Log.w("SnapMark", "成品圖 $what 失敗", e)
                Exported.Failed(what)
            } catch (e: OutOfMemoryError) {
                Log.w("SnapMark", "成品圖 $what 記憶體不夠", e)
                Exported.Failed(what)
            }
            exporting = null
            if (result == Exported.Saved) savedToGallery = true
            exported.send(result)
        }
    }

    /** 清除標註（可以復原）。 */
    fun clear() {
        if (busy) return
        picker = null
        update(editor.clear())
    }

    /** 向右轉（[clockwise]）或向左轉 90 度：底圖與標註一起。 */
    fun rotate(clockwise: Boolean) {
        picker = null
        transform({ rotateBitmap(it, clockwise) }) { e, name -> e.rotated(clockwise, name) }
    }

    /** 進入裁切：框住整張，上面一排換成「取消」「完成」。 */
    fun startCrop() {
        if (busy || work == null) return
        picker = null
        update(editor.finishText())
        crop = Crop(editor.width, editor.height)
    }

    fun cancelCrop() {
        crop = null
    }

    /** 按「完成」：裁成框住的那一塊（整張都框住的話什麼都不做）。 */
    fun finishCrop() {
        val c = crop ?: return
        crop = null
        if (c.isWhole) return
        val box = c.rect
        transform({ cropBitmap(it, box) }) { e, name -> e.cropped(box, name) }
    }

    /** 裁切框的手勢（底圖座標）。[reach]：手指點得到的距離（底圖像素）。 */
    fun cropPress(at: Point, reach: Float) {
        crop = crop?.press(at, reach)
    }

    fun cropDrag(to: Point) {
        crop = crop?.drag(to)
    }

    fun cropRelease() {
        crop = crop?.release()
    }

    /** 第二指按下來（改成縮放平移）：這次拉到一半的框不算。 */
    fun cropCancel() {
        crop = crop?.cancel()
    }

    /**
     * 裁切、旋轉：在背景把底圖做成新的一張（[image]）、寫成新的底圖檔，再用 [apply] 改 [editor]（標註跟著變）。
     * 空白畫布沒有底圖，只改標註與大小。做的時候畫面蓋一層，手指碰不到畫布。
     */
    private fun transform(image: (Bitmap) -> Bitmap, apply: (Editor, String?) -> Editor) {
        val work = work ?: return
        if (busy) return
        transforming = true
        val layer = base
        main.launch {
            try {
                val name = if (work.hasBase) store.newBaseName() else null
                val next = apply(editor, name)
                val nextLayer = if (layer != null && name != null) {
                    withContext(Dispatchers.IO) {
                        val bitmap = image(layer.photo)
                        // 底圖檔先寫好：存檔時才把 base_name 換成它
                        writeJpeg(bitmap, store.baseFile(workId, name))
                        bitmap.setHasMipMap(true)
                        BaseLayer(bitmap).prepare(next.markup)
                    }
                } else {
                    null
                }
                switchTo(next, nextLayer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SnapMark", "裁切、旋轉失敗", e)
            } catch (e: OutOfMemoryError) {
                Log.w("SnapMark", "裁切、旋轉記憶體不夠", e)
            } finally {
                transforming = false
            }
        }
    }

    /** 換成 [next]（大小、底圖可能不一樣）與它的底圖 [layer]：縮圖的畫法跟著換，縮放回到剛好放進畫面。 */
    private suspend fun switchTo(next: Editor, layer: BaseLayer?) {
        thumbs = withContext(Dispatchers.IO) { Thumbnailer(next.width, next.height, layer) }
        if (layer != null && next.base != null) bases[next.base] = layer
        base = layer
        zoom = Zoom()
        update(next)
    }

    override fun onEnteredComposition() = Unit

    override fun onExitedComposition() = Unit

    override fun onRetired() {
        // 沒有按返回就被丟掉（例如 Activity 整個關掉）：存完排隊中的就好，作品留著
        saves.close()
        main.cancel()
        cleanUpBases(editor.base)
    }

    override fun onUnused() = main.cancel()

    /**
     * 離開畫布（按返回、存到相簿之後）：正在打的字當成按了完成，存完最後一次；
     * 這次才開的作品什麼都沒畫（也沒裁切、旋轉、沒存到相簿）的話刪掉，不然刪掉復原用的舊底圖。
     */
    fun finish() {
        if (finished) return
        crop = null
        update(editor.finishText())
        finished = true
        saves.close()
        val pending = saver
        val sheet = editor.sheet
        val untouched = isNew && !savedToGallery && sheet.markup.nodes.isEmpty() && initial?.let { it.width == sheet.width && it.height == sheet.height && it.base == sheet.base } != false
        if (untouched) {
            container.background.launch {
                pending?.join()
                store.delete(workId)
            }
        } else {
            cleanUpBases(sheet.base)
        }
    }

    // 存完之後，刪掉資料夾裡沒在用的底圖（復原用的舊底圖）
    private fun cleanUpBases(keep: String?) {
        if (keep == null) return
        val pending = saver
        container.background.launch {
            pending?.join()
            store.removeUnusedBases(workId, keep)
        }
    }

    private fun update(next: Editor) {
        val changed = next.markup !== editor.markup || next.width != editor.width || next.height != editor.height || next.base != editor.base
        editor = next
        // 只有標註（或大小、底圖）真的變了才存：拖動時改的是 draft，放開才會進來
        val t = thumbs
        if (changed && !finished && t != null) saves.trySend(SaveJob(next.sheet, t))
    }

    /** [slop]：touch slop 換算成底圖像素。 */
    fun press(at: Point, slop: Float) = update(editor.press(at, UUID.randomUUID().toString(), slop))

    fun drag(to: Point, slop: Float) = update(editor.drag(to, slop))

    /** [time]：放開的時間（毫秒），判斷點兩下。 */
    fun release(slop: Float, time: Long) = update(editor.release(slop, time))

    fun deleteSelected() = update(editor.deleteSelected())

    fun toggleOutline() = update(editor.toggleOutline())

    fun toggleTail() = update(editor.toggleTail())

    fun editLabel() = update(editor.editLabel())

    fun cancelDraft() = update(editor.cancel())

    fun undo() = step(editor.undo())

    fun redo() = step(editor.redo())

    /** 復原、重做：換到別張底圖（裁切、旋轉之前）的話先把那張讀回來（剛用過的還在記憶體裡）。 */
    private fun step(next: Editor) {
        if (busy) return
        if (next.base == editor.base && next.width == editor.width && next.height == editor.height) {
            update(next)
            return
        }
        transforming = true
        main.launch {
            try {
                val name = next.base
                val layer = name?.let {
                    bases[it]?.let { cached -> withContext(Dispatchers.IO) { cached.prepare(next.markup) } }
                        ?: withContext(Dispatchers.IO) { BaseLayer.load(store.baseFile(workId, it), next.markup) }
                }
                switchTo(next, layer)
            } finally {
                transforming = false
            }
        }
    }

    fun editText(value: String) = update(editor.editText(value))

    fun finishText() = update(editor.finishText())

    fun selectTool(tool: Tool) = pick(editor.withTool(tool))

    fun selectShape(shape: ShapeKind) = pick(editor.withShape(shape))

    fun selectPen(highlighter: Boolean) = pick(editor.withPen(highlighter))

    fun selectStamp(stamp: StampKind) = pick(editor.withStamp(stamp))

    fun selectColor(color: MarkupColor) = pick(editor.withColor(color))

    fun selectSize(size: StrokeSize) = pick(editor.withSize(size))

    // 選了工具、顏色、粗細：收起選單，記下來給下次打開畫布用
    private fun pick(next: Editor) {
        update(next)
        picker = null
        container.tools.remember(editor.toolChoice)
    }

    fun togglePicker(which: Picker) {
        picker = if (picker == which) null else which
    }
}
