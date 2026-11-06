package com.twtrubiks.snapmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTest {
    // 這裡的測試從箭頭開始（App 打開畫布時用的是 ToolChoice，預設是筆）
    private val empty = Editor(Markup(), 1440, 1920, tool = Tool.ARROW)
    private val slop = 10f
    private var clock = 10_000L

    private fun Editor.press(at: Point, id: String) = press(at, id, slop)

    // 每次放開都隔一秒：不會被當成點兩下
    private fun Editor.release(slop: Float) = release(slop, clock.also { clock += 1_000 })

    /** 一指從 [from] 拉到 [to]（中間經過一個點）再放開。 */
    private fun Editor.drawFrom(from: Point, to: Point, id: String = "n"): Editor =
        press(from, id).drag(Point((from.x + to.x) / 2, (from.y + to.y) / 2), slop).drag(to, slop).release(slop)

    /** 一指點一下（沒有移動）。 */
    private fun Editor.tap(at: Point, id: String = "n"): Editor = press(at, id).release(slop)

    @Test
    fun defaultsArePenPinkMedium() {
        val fresh = Editor(Markup(), 1440, 1920)
        assertEquals(Tool.PEN, fresh.tool)
        assertFalse(fresh.highlighter)
        assertEquals(MarkupColor.PINK, fresh.color)
        assertEquals(StrokeSize.M, fresh.size)
        assertEquals(ToolChoice.DEFAULT, fresh.toolChoice)
        assertFalse(fresh.canUndo)
        assertFalse(fresh.canRedo)
    }

    @Test
    fun drawLayersKeepTheOrderAndOnlyTheDraftAndAboveAreLive() {
        val three = empty
            .drawFrom(Point(100f, 100f), Point(400f, 100f), id = "a")
            .drawFrom(Point(100f, 500f), Point(400f, 500f), id = "b")
            .drawFrom(Point(100f, 900f), Point(400f, 900f), id = "c")
        // 沒有在畫：全部都是不會變的那一層，而且就是同一個 list（畫面比對時不用一個一個比）
        assertEquals(DrawLayers(three.markup.nodes, emptyList()), three.drawLayers)
        assertTrue(three.drawLayers.settled === three.markup.nodes)

        // 正在畫新的：只有它是每一格重畫的
        val drawing = three.press(Point(100f, 1300f), "d").drag(Point(400f, 1300f), slop)
        assertEquals(three.markup.nodes, drawing.drawLayers.settled)
        assertEquals(listOf("d"), drawing.drawLayers.live.map { it.id })

        // 拖著中間那一個（b）走：b 和蓋在它上面的 c 每一格重畫，a 不會變；合起來跟畫面上的順序一樣
        val selected = three.tap(Point(250f, 500f))
        assertEquals("b", selected.selected)
        val moving = selected.press(Point(250f, 500f), "x").drag(Point(250f, 700f), slop)
        assertEquals(listOf("a"), moving.drawLayers.settled.map { it.id })
        assertEquals(listOf("b", "c"), moving.drawLayers.live.map { it.id })
        assertEquals(moving.visibleNodes, moving.drawLayers.settled + moving.drawLayers.live)
    }

    @Test
    fun toolChoiceIsAppliedAndReadBack() {
        val choice = ToolChoice(Tool.STAMP, ShapeKind.ELLIPSE, highlighter = true, stamp = StampKind.HEART, color = MarkupColor.BLUE, size = StrokeSize.XL)
        val applied = empty.withToolChoice(choice)
        assertEquals(choice, applied.toolChoice)
        // 只換工具，不動標註、不算一步
        assertEquals(empty.markup, applied.markup)
        assertFalse(applied.canUndo)
    }

    @Test
    fun panIsNotRememberedAndBlankCanvasHasNoMosaic() {
        val drawing = ToolChoice(Tool.SHAPE, ShapeKind.ELLIPSE, color = MarkupColor.GREEN)
        // 換到平移：顏色這些照記，工具留著之前用來畫的
        val panned = drawing.copy(tool = Tool.PAN, size = StrokeSize.L).remembered(drawing)
        assertEquals(drawing.copy(size = StrokeSize.L), panned)
        assertEquals(drawing, drawing.remembered(ToolChoice.DEFAULT))

        val mosaic = ToolChoice(Tool.PIXELATE, color = MarkupColor.RED)
        assertEquals(mosaic, mosaic.opening(hasBase = true))
        // 空白畫布沒有馬賽克：退回筆，顏色粗細照舊
        assertEquals(mosaic.copy(tool = Tool.PEN), mosaic.opening(hasBase = false))
        // 萬一記到平移（舊的資料）：一樣退回筆
        assertEquals(Tool.PEN, ToolChoice(Tool.PAN).opening(hasBase = true).tool)
        assertEquals(drawing, drawing.opening(hasBase = false))
    }

    @Test
    fun dragDrawsAnArrowWithTheCurrentColorAndSize() {
        val editor = empty.withColor(MarkupColor.BLUE).withSize(StrokeSize.XL)
            .drawFrom(Point(10f, 20f), Point(300f, 400f), id = "a")

        assertEquals(
            listOf<Node>(Node.Arrow("a", Point(10f, 20f), Point(300f, 400f), MarkupColor.BLUE, StrokeSize.XL)),
            editor.markup.nodes,
        )
        assertNull(editor.draft)
        assertNull(editor.gesture)
        assertTrue(editor.canUndo)
    }

    @Test
    fun arrowFollowsTheFingerBeforeRelease() {
        val drawing = empty.press(Point(0f, 0f), "a").drag(Point(50f, 60f), slop)

        // 還沒放開：畫面上看得到，但還不算進標註
        assertTrue(drawing.markup.nodes.isEmpty())
        assertEquals(Point(50f, 60f), (drawing.visibleNodes.single() as Node.Arrow).to)
        assertFalse(drawing.canUndo)
    }

    @Test
    fun tapDoesNotDrawWithDragTools() {
        for (tool in listOf(Tool.ARROW, Tool.SHAPE, Tool.PEN, Tool.PAN)) {
            val editor = empty.withTool(tool).press(Point(100f, 100f), "a").drag(Point(103f, 101f), slop).release(slop)

            assertTrue("$tool", editor.visibleNodes.isEmpty())
            assertFalse("$tool", editor.canUndo)
        }
    }

    @Test
    fun draggingOutAndBackIsNotAnArrow() {
        val editor = empty.press(Point(100f, 100f), "a").drag(Point(300f, 300f), slop).drag(Point(102f, 100f), slop).release(slop)

        assertTrue(editor.markup.nodes.isEmpty())
    }

    @Test
    fun cancelDropsTheShapeInProgress() {
        val editor = empty.press(Point(0f, 0f), "a").drag(Point(500f, 500f), slop).cancel().release(slop)

        assertTrue(editor.visibleNodes.isEmpty())
        assertFalse(editor.canUndo)
    }

    @Test
    fun panToolDoesNotDraw() {
        val editor = empty.withTool(Tool.PAN).drawFrom(Point(0f, 0f), Point(500f, 500f))

        assertTrue(editor.markup.nodes.isEmpty())
    }

    @Test
    fun shapeUsesTheChosenKind() {
        val editor = empty.withShape(ShapeKind.ELLIPSE).withColor(MarkupColor.GREEN)
            .drawFrom(Point(10f, 10f), Point(200f, 100f), id = "s")

        assertEquals(Tool.SHAPE, editor.tool)
        assertEquals(
            listOf<Node>(Node.Shape("s", ShapeKind.ELLIPSE, Point(10f, 10f), Point(200f, 100f), MarkupColor.GREEN, StrokeSize.M)),
            editor.markup.nodes,
        )
    }

    @Test
    fun penFollowsTheFingerButSkipsTinyMoves() {
        val editor = empty.withPen(highlighter = true)
            .press(Point(0f, 0f), "p")
            .drag(Point(20f, 0f), slop)
            // 比 slop/6 還近，不記
            .drag(Point(20.5f, 0f), slop)
            .drag(Point(40f, 10f), slop)
            .release(slop)

        val stroke = editor.markup.nodes.single() as Node.Stroke
        assertEquals(listOf(Point(0f, 0f), Point(20f, 0f), Point(40f, 10f)), stroke.points)
        assertTrue(stroke.highlighter)
    }

    @Test
    fun pixelateIsDraggedAsABoxInsideThePage() {
        val tool = empty.withTool(Tool.PIXELATE)
        val editor = tool.drawFrom(Point(100f, 200f), Point(500f, 260f), id = "m")
        assertEquals(listOf<Node>(Node.Pixelate("m", Point(100f, 200f), Point(500f, 260f))), editor.markup.nodes)

        // 拉到底圖外面：框停在邊上（只打碎得到底圖）
        val outside = tool.drawFrom(Point(1300f, 1800f), Point(1600f, 2100f), id = "o")
        assertEquals(listOf<Node>(Node.Pixelate("o", Point(1300f, 1800f), Point(1440f, 1920f))), outside.markup.nodes)

        // 點一下、或整個在底圖外面拉（拉回邊上變成扁的一條）都不算
        assertTrue(tool.tap(Point(100f, 100f)).markup.nodes.isEmpty())
        assertTrue(tool.drawFrom(Point(-50f, -300f), Point(-200f, 500f)).markup.nodes.isEmpty())
    }

    @Test
    fun pixelateCanBeSelectedMovedAndResizedButNotRecolored() {
        val drawn = empty.withTool(Tool.PIXELATE).drawFrom(Point(100f, 100f), Point(500f, 300f), id = "m")
        // 點在框裡面就選到（馬賽克蓋住的是一整塊）
        val selected = drawn.tap(Point(300f, 200f))
        assertEquals("m", selected.selected)

        val moved = selected.drawFrom(Point(300f, 200f), Point(400f, 250f))
        assertEquals(Node.Pixelate("m", Point(200f, 150f), Point(600f, 350f)), moved.markup.nodes.single())

        // 右下角的控制點拉大
        val resized = moved.drawFrom(Point(600f, 350f), Point(700f, 500f))
        assertEquals(Node.Pixelate("m", Point(200f, 150f), Point(700f, 500f)), resized.markup.nodes.single())

        // 改顏色、粗細對它沒有作用，也不多一步復原
        val recolored = resized.withColor(MarkupColor.BLUE).withSize(StrokeSize.XL)
        assertEquals(resized.markup, recolored.markup)
    }

    @Test
    fun stampIsPlacedOnTapAndCanBeDraggedBeforeRelease() {
        val tapped = empty.withStamp(StampKind.HEART).tap(Point(100f, 200f), id = "h")
        assertEquals(listOf<Node>(Node.Stamp("h", StampKind.HEART, Point(100f, 200f))), tapped.markup.nodes)

        val dragged = empty.withStamp(StampKind.CHECK).press(Point(100f, 200f), "c").drag(Point(300f, 400f), slop)
        assertEquals(Point(300f, 400f), (dragged.visibleNodes.single() as Node.Stamp).at)
        assertEquals(Point(300f, 400f), (dragged.release(slop).markup.nodes.single() as Node.Stamp).at)
    }

    @Test
    fun stampsAndTextStayOnThePage() {
        // 畫布是 1440×1920：點在外面的拉回邊上
        val stamped = empty.withStamp(StampKind.CHECK).tap(Point(-50f, 2500f), id = "c")
        assertEquals(Point(0f, 1920f), (stamped.markup.nodes.single() as Node.Stamp).at)

        val dragged = empty.withStamp(StampKind.CHECK).press(Point(100f, 100f), "d").drag(Point(3000f, 50f), slop).release(slop)
        assertEquals(Point(1440f, 50f), (dragged.markup.nodes.single() as Node.Stamp).at)

        val text = empty.withTool(Tool.TEXT).tap(Point(2000f, 900f)).text
        assertEquals(1440f, requireNotNull(text).at.x, 0f)
    }

    @Test
    fun tapWithTextToolStartsTypingAtThatSpot() {
        val editor = empty.withTool(Tool.TEXT).withColor(MarkupColor.RED).tap(Point(100f, 500f), id = "t")

        val edit = requireNotNull(editor.text)
        assertEquals("t", edit.id)
        assertEquals("", edit.text)
        assertEquals(MarkupColor.RED, edit.color)
        // 點的地方是第一行字的中間：文字框的左上角在它上面一點
        assertEquals(100f, edit.at.x, 0f)
        assertTrue(edit.at.y < 500f)
        // 還沒按完成：不算進標註
        assertTrue(editor.markup.nodes.isEmpty())
        assertFalse(editor.canUndo)
    }

    @Test
    fun dragWithTextToolDoesNothing() {
        val editor = empty.withTool(Tool.TEXT).drawFrom(Point(0f, 0f), Point(300f, 300f))

        assertNull(editor.text)
        assertTrue(editor.markup.nodes.isEmpty())
    }

    @Test
    fun finishTextAddsItAsOneStep() {
        val typing = empty.withTool(Tool.TEXT).tap(Point(100f, 500f), id = "t").editText("這裡").editText("這裡！")
            // 打字的時候換顏色、粗細，改的是這段字
            .withColor(MarkupColor.BLUE).withSize(StrokeSize.L)

        val done = typing.finishText()

        val node = done.markup.nodes.single() as Node.Text
        assertEquals("這裡！", node.text)
        assertEquals(MarkupColor.BLUE, node.color)
        assertEquals(StrokeSize.L, node.size)
        assertTrue(node.outlined)
        assertNull(done.text)
        assertEquals(Markup(), done.undo().markup)
    }

    @Test
    fun emptyTextIsDropped() {
        val editor = empty.withTool(Tool.TEXT).tap(Point(100f, 500f)).editText("  ").finishText()

        assertNull(editor.text)
        assertTrue(editor.markup.nodes.isEmpty())
        assertFalse(editor.canUndo)
    }

    @Test
    fun tappingElsewhereOrChangingToolFinishesTheText() {
        val typing = empty.withTool(Tool.TEXT).tap(Point(100f, 500f), id = "t").editText("A")

        // 點在空白的地方（離剛打的字遠一點）：剛打的字加進去，在點的地方開始打下一段
        val tappedAgain = typing.tap(Point(800f, 1500f), id = "u")
        assertEquals("A", (tappedAgain.markup.nodes.single() as Node.Text).text)
        assertEquals("u", tappedAgain.text?.id)

        val switched = typing.withTool(Tool.ARROW)
        assertEquals("A", (switched.markup.nodes.single() as Node.Text).text)
        assertNull(switched.text)
    }

    @Test
    fun undoWhileTypingDropsTheText() {
        val editor = empty.drawFrom(Point(0f, 0f), Point(100f, 0f), id = "a")
            .withTool(Tool.TEXT).tap(Point(100f, 500f)).editText("A")
            .undo()

        assertNull(editor.text)
        // 已經畫好的箭頭還在
        assertEquals(listOf("a"), editor.markup.nodes.map { it.id })
    }

    @Test
    fun changingColorDoesNotChangeWhatIsAlreadyDrawn() {
        val editor = empty.drawFrom(Point(0f, 0f), Point(100f, 0f), id = "a").withColor(MarkupColor.GREEN)

        assertEquals(MarkupColor.PINK, (editor.markup.nodes.single() as Node.Arrow).color)
        // 改顏色不算一步
        assertEquals(1, editor.history.undo.size)
    }

    @Test
    fun undoAndRedo() {
        val one = empty.drawFrom(Point(0f, 0f), Point(100f, 0f), id = "a")
        val two = one.drawFrom(Point(0f, 50f), Point(100f, 50f), id = "b")

        val undone = two.undo()
        assertEquals(one.markup, undone.markup)
        assertTrue(undone.canRedo)

        val undoneTwice = undone.undo()
        assertEquals(Markup(), undoneTwice.markup)
        assertFalse(undoneTwice.canUndo)
        // 沒有東西可以復原時不動
        assertEquals(undoneTwice, undoneTwice.undo())

        val redone = undoneTwice.redo().redo()
        assertEquals(two.markup, redone.markup)
        assertFalse(redone.canRedo)
        assertEquals(redone, redone.redo())
    }

    @Test
    fun drawingAfterUndoDropsTheRedo() {
        val editor = empty.drawFrom(Point(0f, 0f), Point(100f, 0f), id = "a")
            .undo()
            .drawFrom(Point(0f, 50f), Point(100f, 50f), id = "b")

        assertFalse(editor.canRedo)
        assertEquals(listOf("b"), editor.markup.nodes.map { it.id })
    }

    @Test
    fun undoWhileDrawingDropsTheDraft() {
        val editor = empty.drawFrom(Point(0f, 0f), Point(100f, 0f), id = "a")
            .press(Point(0f, 50f), "b").drag(Point(100f, 50f), slop)
            .undo()

        assertNull(editor.draft)
        assertTrue(editor.visibleNodes.isEmpty())
        // 放開手指時不會再冒出來
        assertTrue(editor.release(slop).visibleNodes.isEmpty())
    }

    @Test
    fun historyKeepsOnlyTheLastSteps() {
        var editor = empty
        repeat(History.LIMIT + 20) { editor = editor.drawFrom(Point(0f, it.toFloat()), Point(100f, it.toFloat()), id = "$it") }

        assertEquals(History.LIMIT, editor.history.undo.size)
        repeat(History.LIMIT) { editor = editor.undo() }
        assertFalse(editor.canUndo)
        // 最早的 20 筆回不去了
        assertEquals(20, editor.markup.nodes.size)
    }

    @Test
    fun clearRemovesEverythingAndCanBeUndone() {
        val drawn = empty.drawFrom(Point(0f, 0f), Point(100f, 0f), id = "a").drawFrom(Point(0f, 50f), Point(100f, 50f), id = "b")
        val cleared = drawn.clear()

        assertTrue(cleared.markup.nodes.isEmpty())
        assertEquals(drawn.markup, cleared.undo().markup)
        // 沒有東西可以清的時候不算一步
        assertEquals(cleared.history, cleared.clear().history)
    }

    @Test
    fun cropChangesTheSizeAndBaseAndUndoBringsThemBack() {
        val photo = Editor(Markup(), 2000, 1000, base = "base.jpg", tool = Tool.ARROW)
            .drawFrom(Point(600f, 300f), Point(900f, 500f), id = "a")
        val cropped = photo.cropped(IntBox(500, 200, 1500, 700), newBase = "base-2.jpg")

        assertEquals(1000 to 500, cropped.width to cropped.height)
        assertEquals("base-2.jpg", cropped.base)
        assertEquals(Point(100f, 100f), (cropped.markup.nodes.single() as Node.Arrow).from)

        val undone = cropped.undo()
        assertEquals(photo.sheet, undone.sheet)
        assertEquals(cropped.sheet, undone.redo().sheet)
    }

    @Test
    fun croppingTheWholePictureDoesNothing() {
        val photo = Editor(Markup(), 2000, 1000, base = "base.jpg", tool = Tool.ARROW)
        assertEquals(photo, photo.cropped(IntBox.whole(2000, 1000), newBase = "base-2.jpg"))
    }

    @Test
    fun rotateSwapsTheSides() {
        val drawn = empty.drawFrom(Point(100f, 200f), Point(300f, 200f), id = "a")
        val turned = drawn.rotated(clockwise = true, newBase = null)

        assertEquals(1920 to 1440, turned.width to turned.height)
        assertEquals(Point(1720f, 100f), (turned.markup.nodes.single() as Node.Arrow).from)
        assertEquals(drawn.sheet, turned.undo().sheet)
    }

    @Test
    fun cropFinishesTypingFirstAndDropsTheSelection() {
        val typing = empty.withTool(Tool.TEXT).tap(Point(500f, 500f), id = "t").editText("嗨")
        val cropped = typing.cropped(IntBox(0, 0, 1000, 1000), newBase = null)
        assertNull(cropped.text)
        assertEquals("t", cropped.markup.nodes.single().id)

        val selected = empty.drawFrom(Point(100f, 100f), Point(400f, 100f), id = "a").tap(Point(250f, 100f))
        assertEquals("a", selected.selected)
        assertNull(selected.rotated(clockwise = false, newBase = null).selected)
    }

    @Test
    fun changingTheSizeAfterACropFollowsTheNewPicture() {
        val cropped = empty.drawFrom(Point(100f, 100f), Point(600f, 100f), id = "a")
            .cropped(IntBox(0, 0, 720, 960), newBase = null)
        val arrow = cropped.markup.nodes.single()
        assertEquals(2f, arrow.scale, 0.001f)
        // 選到它改粗細：照裁切後的長邊算
        val resized = cropped.tap(Point(350f, 100f)).withSize(StrokeSize.L).markup.nodes.single()
        assertEquals(1f, resized.scale, 0f)
        // 之後新畫的也是
        assertEquals(1f, cropped.drawFrom(Point(100f, 500f), Point(600f, 500f), id = "b").markup.nodes.last().scale, 0f)
    }
}
