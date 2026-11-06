package com.twtrubiks.snapmark

/**
 * 作品現在的樣子：標註、畫布大小，與底圖是哪一張（[base] 是作品資料夾裡的檔名，空白畫布是 null）。
 * 裁切、旋轉會換一張底圖、改大小，復原時整份換回來（存檔也是整份存）。
 */
data class Sheet(val markup: Markup, val width: Int, val height: Int, val base: String? = null)

/**
 * 復原／重做：記每一步之前的整份 [Sheet]。Markup 是不可變的，沒改到的 [Node] 前後共用同一個物件，
 * 記一份不怎麼佔記憶體（底圖只記檔名）。只記這一次打開畫布的期間（不存進資料庫）。
 */
data class History(val undo: List<Sheet> = emptyList(), val redo: List<Sheet> = emptyList()) {
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** 要改成新的樣子之前，把 [before] 記下來；重做的那一串就作廢。 */
    fun record(before: Sheet) = History((undo + before).takeLast(LIMIT), emptyList())

    companion object {
        const val LIMIT = 100
    }
}

/** 這次手勢抓到的是什麼。 */
sealed interface Grab {
    /** 照目前的工具畫新的（或只是點一下）。 */
    data object Draw : Grab

    /** 拖著選到的那一個走。 */
    data class Move(val origin: Node) : Grab

    /** 拉選到的那一個的第 [index] 個控制點（[handles]）。 */
    data class Handle(val origin: Node, val index: Int) : Grab
}

/**
 * 一指按下到放開的這一次手勢。[moved]：手指離開按下的地方超過 touch slop 了（拖動），沒有的話放開時算「點一下」。
 * [id] 是這次手勢如果產生新物件要用的 id。
 */
data class Gesture(val id: String, val start: Point, val moved: Boolean = false, val grab: Grab = Grab.Draw)

/**
 * 正在打的字：還沒按「完成」，不算進 [Markup]（不能復原、不存檔），畫面上是一個輸入框。
 * [existing]：改一段已經有的字（點兩下）；[labelOf]：改印章旁邊的字（那個印章的 id）。
 */
data class TextEdit(
    val id: String,
    val at: Point,
    val text: String,
    val color: MarkupColor,
    val size: StrokeSize,
    val outlined: Boolean = true,
    val existing: Boolean = false,
    val labelOf: String? = null,
    val scale: Float = 1f,
) {
    fun toNode() = Node.Text(id, at, text, color, size, outlined, scale)

    /** 字級（像素）：跟 [Node.Text.fontSize] 一樣。 */
    fun fontSize(canvasWidth: Int, canvasHeight: Int) = size.textSize(canvasWidth, canvasHeight) * scale
}

/** [Editor.drawLayers]：[settled] 是手指移動時不會變的、[live] 是每一格都要重畫的。 */
data class DrawLayers(val settled: List<Node>, val live: List<Node>)

/**
 * 畫布右下角、左下角選的東西：工具（含形狀、筆、印章的子選項）、顏色、粗細。
 * 打開畫布時用上次選的（`AppContainer.tools`，存在 settings），第一次用是 [DEFAULT]：
 * **筆**（麥克筆）、粉紅、中（2026-11-05 使用者要的：預設是筆，之後記住上次用的）。
 */
data class ToolChoice(
    val tool: Tool = Tool.PEN,
    val shape: ShapeKind = ShapeKind.RECT,
    val highlighter: Boolean = false,
    val stamp: StampKind = StampKind.CHECK,
    val color: MarkupColor = MarkupColor.PINK,
    val size: StrokeSize = StrokeSize.M,
) {
    /** 要記下來的樣子：平移不記（下次打開就畫不了東西），留著 [previous] 那個用來畫的工具。 */
    fun remembered(previous: ToolChoice) = if (tool == Tool.PAN) copy(tool = previous.tool) else this

    /** 打開一張作品時用的：馬賽克只有照片有，空白畫布（[hasBase] 是 false）退回預設的工具。 */
    fun opening(hasBase: Boolean) =
        if (tool == Tool.PAN || (tool == Tool.PIXELATE && !hasBase)) copy(tool = DEFAULT.tool) else this

    companion object {
        val DEFAULT = ToolChoice()
    }
}

/**
 * 畫布的編輯狀態機，純 Kotlin（JVM 測試）：目前的工具（含形狀、筆、印章的子選項）、顏色、粗細，
 * 手指按下／移動／放開會產生或修改哪個 [Node]，選取，打字，以及復原／重做。
 * 座標都是底圖的像素（[width]×[height]）：螢幕座標的換算（[Viewport]）在呼叫之前就做完了；
 * 長度的門檻（touch slop）也是換算成底圖像素再傳進來。
 *
 * 每個操作都回傳新的 [Editor]，不改自己。
 *
 * - 拖動才畫的：箭頭、形狀、筆
 * - 點一下就有的：印章（按下就出現在手指下，拖著走，放開才蓋上去）、文字（點一下開始打字）
 * - 任何工具下，點一下已經畫上去的物件就選到它（[selected]）：拖它是移動，拉控制點改大小／方向，
 *   這時候改顏色、粗細是改它；點空白的地方取消選取。文字點兩下重新打字
 * - 整張作品：清除標註（[clear]）、裁切（[cropped]）、旋轉（[rotated]），都可以復原（連大小、底圖一起換回來）
 *
 * @property base 底圖是作品資料夾裡的哪一個檔（空白畫布是 null）：裁切、旋轉會換一張，復原時換回來
 * @property draft 手指還沒放開、正在畫（或正在移動、改大小）的那一個。放開才寫進 [markup]（也才記一步復原、才存檔）
 * @property text 正在打的字，按「完成」（[finishText]）才寫進 [markup]
 * @property lastTap 上一次「點一下」的時間（毫秒），判斷點兩下
 * @property measure 量文字大小（選取框、點到文字）：手機上是 [AndroidTextMeasure]
 */
data class Editor(
    val markup: Markup,
    val width: Int,
    val height: Int,
    val base: String? = null,
    val tool: Tool = ToolChoice.DEFAULT.tool,
    val color: MarkupColor = ToolChoice.DEFAULT.color,
    val size: StrokeSize = ToolChoice.DEFAULT.size,
    val shape: ShapeKind = ToolChoice.DEFAULT.shape,
    val highlighter: Boolean = ToolChoice.DEFAULT.highlighter,
    val stamp: StampKind = ToolChoice.DEFAULT.stamp,
    val draft: Node? = null,
    val gesture: Gesture? = null,
    val text: TextEdit? = null,
    val selected: String? = null,
    val lastTap: Long = NEVER,
    val history: History = History(),
    val measure: TextMeasure = TextMeasure.APPROX,
) {
    val page get() = Page(width, height, measure)

    /** 現在的樣子（存檔、復原用）。 */
    val sheet get() = Sheet(markup, width, height, base)

    /**
     * 畫面上要畫的：已經畫好的（正在移動、改大小的那一個換成 [draft]），加上正在畫的新的那一個（蓋在最上面）。
     * 正在打的字由輸入框自己顯示：改舊的字時先不畫它，改印章的字時印章先不畫字。
     */
    val visibleNodes: List<Node>
        get() {
            val d = draft
            val t = text
            if (d == null && t == null) return markup.nodes
            val nodes = markup.nodes.mapNotNull { n ->
                when {
                    d != null && n.id == d.id -> d
                    t != null && t.existing && n.id == t.id -> null
                    t != null && n.id == t.labelOf && n is Node.Stamp -> n.copy(label = null)
                    else -> n
                }
            }
            return if (d != null && markup.nodes.none { it.id == d.id }) nodes + d else nodes
        }

    /**
     * [visibleNodes] 分兩層畫（照順序接起來就是 [visibleNodes]）：手指移動時，正在畫、移動的那一個（[draft]）底下的都不會變，
     * 畫面把它們放在快取的圖層（`DrawingSurface`），每一格只重畫 [DrawLayers.live]（[draft] 和蓋在它上面的）。
     */
    val drawLayers: DrawLayers
        get() {
            val d = draft ?: return DrawLayers(visibleNodes, emptyList())
            val all = visibleNodes
            val split = all.indexOfFirst { it.id == d.id }
            return DrawLayers(all.subList(0, split), all.subList(split, all.size))
        }

    /** 選到的那一個（正在移動、改大小的話是移動中的樣子）。打字的時候沒有。 */
    val selectedNode: Node? get() = if (text != null) null else selected?.let { id -> visibleNodes.firstOrNull { it.id == id } }

    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo

    private fun node(id: String?) = markup.nodes.firstOrNull { it.id == id }

    /** 一指按下。[id] 是新物件的 id（呼叫的人產生，測試才能固定）。[slop]：touch slop（底圖像素）。 */
    fun press(at: Point, id: String, slop: Float): Editor {
        if (text != null) return finishText().press(at, id, slop)
        val reach = slop * REACH
        node(selected)?.let { sel ->
            val handle = sel.handles().indexOfFirst { distance(it, at) <= reach }
            if (handle >= 0) return copy(gesture = Gesture(id, at, grab = Grab.Handle(sel, handle)), draft = null)
            if (sel.hit(at, reach, page)) return copy(gesture = Gesture(id, at, grab = Grab.Move(sel)), draft = null)
        }
        val draft = when (tool) {
            Tool.ARROW -> Node.Arrow(id, at, at, color, size)
            Tool.SHAPE -> Node.Shape(id, shape, at, at, color, size)
            Tool.PEN -> Node.Stroke(id, listOf(at), color, size, highlighter)
            // 點在東西上面（或有東西選著）是要選取／取消選取，不先冒出一個印章
            Tool.STAMP -> if (selected == null && markup.nodes.topAt(at, reach, page) == null) {
                Node.Stamp(id, stamp, at.inPage(), size = size)
            } else {
                null
            }
            // 馬賽克只打碎底圖：框拉不出底圖
            Tool.PIXELATE -> at.inPage().let { Node.Pixelate(id, it, it) }
            Tool.TEXT, Tool.PAN -> null
        }
        return copy(gesture = Gesture(id, at), draft = draft)
    }

    /** 手指移動到 [to]。超過 [slop] 才算拖動；筆的點至少隔 slop 的 1/6 才記一個。 */
    fun drag(to: Point, slop: Float): Editor {
        val g = gesture ?: return this
        val moved = g.moved || distance(g.start, to) >= slop
        val draft = when (val grab = g.grab) {
            // 移動、改大小時手指拉不出底圖：抓著的那一點一直在底圖裡，東西就不會整個跑到外面點不到
            is Grab.Move -> if (moved) grab.origin.moved(to.inPage() - g.start) else null
            is Grab.Handle -> if (moved) grab.origin.withHandle(grab.index, to.inPage()) else null
            Grab.Draw -> when (val d = draft) {
                is Node.Arrow -> d.copy(to = to)
                is Node.Shape -> d.copy(to = to)
                is Node.Stroke -> if (distance(d.points.last(), to) >= slop / 6) d.copy(points = d.points + to) else d
                is Node.Stamp -> d.copy(at = to.inPage())
                is Node.Pixelate -> d.copy(to = to.inPage())
                else -> d
            }
        }
        return copy(gesture = g.copy(moved = moved), draft = draft)
    }

    /** 手指放開，[time] 是放開的時間（毫秒，判斷點兩下）。拖動才畫的工具，拉得比 [slop] 短（只是點一下）就不算畫了東西。 */
    fun release(slop: Float, time: Long): Editor {
        val g = gesture ?: return this
        val d = draft
        val done = copy(gesture = null, draft = null)
        if (!g.moved) return done.tap(g.start, g.id, slop * REACH, time, d as? Node.Stamp)
        return when (g.grab) {
            is Grab.Move, is Grab.Handle -> if (d != null) done.replace(d) else done
            Grab.Draw -> when (d) {
                is Node.Arrow -> if (distance(d.from, d.to) >= slop) done.add(d) else done
                is Node.Shape -> if (distance(d.from, d.to) >= slop) done.add(d) else done
                // 拉在底圖外面（被拉回邊上）會是扁的一條，什麼都蓋不到
                is Node.Pixelate -> if (distance(d.from, d.to) >= slop && d.from.x != d.to.x && d.from.y != d.to.y) done.add(d) else done
                is Node.Stroke, is Node.Stamp -> done.add(d)
                else -> done
            }
        }
    }

    /**
     * 點一下：點到東西就選它（已經選著的文字，很快點第二下就重新打字）；點空白的地方取消選取；
     * 什麼都沒選的時候，文字工具開始打字、印章工具蓋印章（[stamp] 是按下時冒出來的那一個）。
     */
    private fun tap(p: Point, id: String, reach: Float, time: Long, stamp: Node.Stamp?): Editor {
        val hit = markup.nodes.topAt(p, reach, page)
        if (hit != null) {
            if (hit.id == selected && hit is Node.Text && time - lastTap <= DOUBLE_TAP_MS) {
                return copy(lastTap = NEVER).editExisting(hit)
            }
            return copy(selected = hit.id, lastTap = time)
        }
        if (selected != null) return copy(selected = null, lastTap = time)
        return when (tool) {
            Tool.TEXT -> startText(id, p)
            Tool.STAMP -> stamp?.let { add(it) } ?: this
            else -> this
        }
    }

    /** 畫到一半放棄：第二指按下來（改成縮放平移）、或這一下其實是在平移畫面時。 */
    fun cancel() = copy(draft = null, gesture = null)

    /**
     * 底圖外面畫不出來（成品圖也沒有）：印章的中心、文字的起點拉回底圖裡，不會蓋了一個看不到、也點不到的東西。
     * 箭頭、形狀、筆超出去的部分只是被切掉，不用拉回來。
     */
    private fun Point.inPage() = Point(x.coerceIn(0f, width.toFloat()), y.coerceIn(0f, height.toFloat()))

    /** 點一下的地方是第一行字的左邊、上下的中間；存的是文字框的左上角。 */
    private fun startText(id: String, tap: Point): Editor {
        val start = tap.inPage()
        val at = Point(start.x, start.y - size.textSize(width, height) * 0.6f)
        return copy(text = TextEdit(id, at, "", color, size), selected = null)
    }

    private fun editExisting(t: Node.Text) =
        copy(text = TextEdit(t.id, t.at, t.text, t.color, t.size, t.outlined, existing = true, scale = t.scale), selected = null)

    fun editText(value: String) = copy(text = text?.copy(text = value))

    /**
     * 按「完成」。新的字：有打字才加進標註（算一步復原），空的就當沒開過；
     * 改舊的字：改成空的就是刪掉；印章的字：空的就是拿掉。
     */
    fun finishText(): Editor {
        val t = text ?: return this
        val done = copy(text = null)
        val value = t.text.takeIf { it.isNotBlank() }
        return when {
            t.labelOf != null -> {
                val stamp = done.node(t.labelOf) as? Node.Stamp ?: return done
                if (stamp.label == value) done.copy(selected = stamp.id) else done.replace(stamp.copy(label = value)).copy(selected = stamp.id)
            }
            t.existing -> {
                val old = done.node(t.id) ?: return done
                when {
                    value == null -> done.remove(t.id)
                    t.toNode() == old -> done.copy(selected = t.id)
                    else -> done.replace(t.toNode()).copy(selected = t.id)
                }
            }
            value == null -> done
            else -> done.add(t.toNode())
        }
    }

    /** 打字的時候按復原：丟掉正在打的字（改舊的字就是不改了）。 */
    fun undo(): Editor {
        if (text != null) return copy(text = null)
        val previous = history.undo.lastOrNull() ?: return this
        return restore(previous, History(history.undo.dropLast(1), history.redo + sheet))
    }

    fun redo(): Editor {
        if (text != null) return this
        val next = history.redo.lastOrNull() ?: return this
        return restore(next, History(history.undo + sheet, history.redo.dropLast(1)))
    }

    private fun restore(to: Sheet, history: History) = copy(
        markup = to.markup,
        width = to.width,
        height = to.height,
        base = to.base,
        draft = null,
        gesture = null,
        selected = selected?.takeIf { id -> to.markup.nodes.any { it.id == id } && to.width == width && to.height == height },
        history = history,
    )

    /** 清除標註：全部清掉（算一步復原）。裁切、旋轉過的不會回去。 */
    fun clear(): Editor {
        val done = finishText()
        if (done.markup.nodes.isEmpty()) return done
        return done.transformed(Markup(), width, height, base)
    }

    /**
     * 裁切成 [box]（底圖像素），底圖換成 [newBase]（呼叫的人已經把裁好的底圖寫好；空白畫布是 null）。
     * 標註怎麼跟著變見 [Markup.cropped]。整張都留下的話什麼都不做。
     */
    fun cropped(box: IntBox, newBase: String?): Editor {
        val done = finishText()
        if (box == IntBox.whole(width, height)) return done
        return done.transformed(done.markup.cropped(box, done.page), box.width, box.height, newBase)
    }

    /** 整張向右轉（[clockwise]）或向左轉 90 度，底圖換成 [newBase]。標註見 [Markup.rotated]。 */
    fun rotated(clockwise: Boolean, newBase: String?): Editor {
        val done = finishText()
        return done.transformed(done.markup.rotated(clockwise, done.page), height, width, newBase)
    }

    private fun transformed(markup: Markup, width: Int, height: Int, base: String?) = copy(
        markup = markup,
        width = width,
        height = height,
        base = base,
        draft = null,
        gesture = null,
        selected = null,
        history = history.record(sheet),
    )

    /** 換工具時，正在打的字當成按了「完成」，也取消選取。 */
    fun withTool(tool: Tool) = finishText().copy(tool = tool, draft = null, gesture = null, selected = null)

    fun withShape(shape: ShapeKind) = withTool(Tool.SHAPE).copy(shape = shape)

    fun withPen(highlighter: Boolean) = withTool(Tool.PEN).copy(highlighter = highlighter)

    fun withStamp(stamp: StampKind) = withTool(Tool.STAMP).copy(stamp = stamp)

    /** 現在選的工具、顏色、粗細（要記下來給下次打開用的）。 */
    val toolChoice get() = ToolChoice(tool, shape, highlighter, stamp, color, size)

    /** 一次換掉工具、子選項、顏色、粗細（剛打開畫布時套上次用的），不動標註。 */
    fun withToolChoice(choice: ToolChoice) = withTool(choice.tool).copy(
        shape = choice.shape,
        highlighter = choice.highlighter,
        stamp = choice.stamp,
        color = choice.color,
        size = choice.size,
    )

    /** 改顏色：之後畫的用這個顏色；正在打的字、選到的那一個也改（算一步復原）。 */
    fun withColor(color: MarkupColor): Editor {
        val next = copy(color = color, text = text?.copy(color = color))
        val sel = next.node(next.selected)?.takeIf { text == null } ?: return next
        val changed = sel.recolored(color)
        return if (changed == sel) next else next.replace(changed)
    }

    /** 改粗細：之後畫的用這個粗細；正在打的字、選到的那一個也改（印章是改大小）。 */
    fun withSize(size: StrokeSize): Editor {
        val next = copy(size = size, text = text?.copy(size = size, scale = 1f))
        val sel = next.node(next.selected)?.takeIf { text == null } ?: return next
        val changed = sel.resized(size)
        return if (changed == sel) next else next.replace(changed)
    }

    /** 刪掉選到的那一個。 */
    fun deleteSelected(): Editor {
        val id = selected ?: return this
        return remove(id)
    }

    /** 選到的文字：卡通字（白邊）與純色字切換。 */
    fun toggleOutline(): Editor {
        val t = node(selected) as? Node.Text ?: return this
        return replace(t.copy(outlined = !t.outlined))
    }

    /** 選到的印章：加一條尾巴（先指向右下方，拉端點改方向），或拿掉尾巴。 */
    fun toggleTail(): Editor {
        val s = node(selected) as? Node.Stamp ?: return this
        return replace(s.copy(tail = if (s.tail == null) s.defaultTail(width, height).inPage() else null))
    }

    /** 選到的印章：開始打它旁邊的字。 */
    fun editLabel(): Editor {
        val s = node(selected) as? Node.Stamp ?: return this
        val current = s.label ?: ""
        val edit = TextEdit(
            id = s.id,
            at = s.labelAt(current.ifEmpty { " " }, width, height, measure),
            text = current,
            color = MarkupColor.BLACK,
            size = s.size,
            labelOf = s.id,
            scale = s.scale,
        )
        return copy(text = edit, selected = null)
    }

    private fun add(node: Node) =
        copy(markup = markup.copy(nodes = markup.nodes + node), history = history.record(sheet), selected = null)

    private fun replace(node: Node) =
        copy(markup = markup.copy(nodes = markup.nodes.map { if (it.id == node.id) node else it }), history = history.record(sheet))

    private fun remove(id: String) =
        copy(markup = markup.copy(nodes = markup.nodes.filter { it.id != id }), history = history.record(sheet), selected = null)

    companion object {
        /** 手指點得到的距離是 touch slop 的幾倍。 */
        const val REACH = 2.5f

        /** 兩下之間在這麼短的時間內才算點兩下（毫秒）。 */
        const val DOUBLE_TAP_MS = 400L

        private const val NEVER = Long.MIN_VALUE / 2
    }
}
