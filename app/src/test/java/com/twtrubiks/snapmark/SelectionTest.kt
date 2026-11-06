package com.twtrubiks.snapmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
    private val page = Page(1440, 1920)
    private val slop = 10f
    private val reach = slop * Editor.REACH
    private var clock = 10_000L

    private val arrow = Node.Arrow("a", Point(100f, 100f), Point(500f, 100f), MarkupColor.PINK, StrokeSize.M)
    private val rect = Node.Shape("r", ShapeKind.RECT, Point(600f, 600f), Point(1000f, 900f), MarkupColor.BLUE, StrokeSize.M)
    private val text = Node.Text("t", Point(100f, 1200f), "Hello", MarkupColor.RED, StrokeSize.M)
    private val stamp = Node.Stamp("s", StampKind.CHECK, Point(1000f, 1500f))
    private val editor = Editor(Markup(listOf(arrow, rect, text, stamp)), 1440, 1920, tool = Tool.ARROW)

    private fun Editor.tap(at: Point, gapMs: Long = 1_000): Editor {
        clock += gapMs
        return press(at, "new", slop).release(slop, clock)
    }

    private fun Editor.dragFrom(from: Point, to: Point): Editor {
        clock += 1_000
        return press(from, "new", slop).drag(Point((from.x + to.x) / 2, (from.y + to.y) / 2), slop).drag(to, slop).release(slop, clock)
    }

    private fun Editor.nodeById(id: String) = markup.nodes.first { it.id == id }

    @Test
    fun hitOnLinesNotInsideBoxes() {
        assertTrue(arrow.hit(Point(300f, 110f), reach, page))
        assertFalse(arrow.hit(Point(300f, 300f), reach, page))

        // 方框：點在框線上才算，裡面不算
        assertTrue(rect.hit(Point(605f, 750f), reach, page))
        assertTrue(rect.hit(Point(800f, 895f), reach, page))
        assertFalse(rect.hit(Point(800f, 750f), reach, page))

        val ellipse = rect.copy(kind = ShapeKind.ELLIPSE)
        // 橢圓的邊：中心 (800, 750)、半徑 200×150
        assertTrue(ellipse.hit(Point(1000f, 750f), reach, page))
        assertTrue(ellipse.hit(Point(800f, 600f), reach, page))
        assertFalse(ellipse.hit(Point(800f, 750f), reach, page))
        // 方框的角在橢圓外面
        assertFalse(ellipse.hit(Point(600f, 600f), reach, page))

        val stroke = Node.Stroke("p", listOf(Point(0f, 0f), Point(100f, 0f), Point(100f, 100f)), MarkupColor.PINK, StrokeSize.M)
        assertTrue(stroke.hit(Point(100f, 50f), reach, page))
        assertFalse(stroke.hit(Point(40f, 60f), reach, page))
    }

    @Test
    fun hitInsideTextAndStamp() {
        assertTrue(text.hit(Point(150f, 1230f), reach, page))
        assertFalse(text.hit(Point(150f, 1500f), reach, page))

        assertTrue(stamp.hit(Point(1010f, 1490f), reach, page))
        assertFalse(stamp.hit(Point(1300f, 1800f), reach, page))
        // 加了尾巴、文字之後，點在它們上面也算
        val withTail = stamp.copy(tail = Point(1300f, 1800f), label = "OK")
        assertTrue(withTail.hit(Point(1300f, 1800f), reach, page))
        val label = withTail.labelAt("OK", 1440, 1920, TextMeasure.APPROX)
        assertTrue(withTail.hit(Point(label.x + 10f, label.y + 10f), reach, page))
    }

    @Test
    fun topmostWins() {
        val under = Node.Arrow("under", Point(0f, 0f), Point(500f, 500f), MarkupColor.PINK, StrokeSize.M)
        val over = Node.Arrow("over", Point(0f, 500f), Point(500f, 0f), MarkupColor.BLUE, StrokeSize.M)
        // 兩支交叉在 (250, 250)：後畫的在上面
        assertEquals("over", listOf(under, over).topAt(Point(250f, 250f), reach, page)?.id)
        assertNull(listOf(under, over).topAt(Point(250f, 50f), reach, page))
    }

    @Test
    fun handlesMoveEndsOrCorners() {
        assertEquals(listOf(arrow.from, arrow.to), arrow.handles())
        assertEquals(Point(700f, 300f), (arrow.withHandle(1, Point(700f, 300f)) as Node.Arrow).to)

        // 方框拉右下角：左上角不動
        val bigger = rect.withHandle(2, Point(1200f, 1000f)) as Node.Shape
        assertEquals(Bounds(600f, 600f, 1200f, 1000f), Bounds.of(bigger.from, bigger.to))
        // 拉左上角：右下角不動
        val fromTopLeft = rect.withHandle(0, Point(500f, 500f)) as Node.Shape
        assertEquals(Bounds(500f, 500f, 1000f, 900f), Bounds.of(fromTopLeft.from, fromTopLeft.to))

        // 筆跡拉右下角：等比例拉長，左上角不動
        val stroke = Node.Stroke("p", listOf(Point(0f, 0f), Point(100f, 50f), Point(200f, 100f)), MarkupColor.PINK, StrokeSize.M)
        val stretched = stroke.withHandle(2, Point(400f, 300f)) as Node.Stroke
        assertEquals(listOf(Point(0f, 0f), Point(200f, 150f), Point(400f, 300f)), stretched.points)

        assertTrue(text.handles().isEmpty())
        assertTrue(stamp.handles().isEmpty())
        assertEquals(listOf(Point(1f, 2f)), stamp.copy(tail = Point(1f, 2f)).handles())
    }

    @Test
    fun movedMovesEverything() {
        val d = Point(10f, -20f)
        assertEquals(arrow.copy(from = Point(110f, 80f), to = Point(510f, 80f)), arrow.moved(d))
        val withTail = stamp.copy(tail = Point(0f, 0f))
        assertEquals(withTail.copy(at = Point(1010f, 1480f), tail = Point(10f, -20f)), withTail.moved(d))
    }

    @Test
    fun tapSelectsAndTapOnEmptyDeselects() {
        val selected = editor.tap(Point(300f, 105f))
        assertEquals("a", selected.selected)
        // 點一下選取不會畫出東西、不算一步
        assertEquals(editor.markup, selected.markup)
        assertFalse(selected.canUndo)

        assertNull(selected.tap(Point(300f, 400f)).selected)
    }

    @Test
    fun anyToolSelects() {
        for (tool in listOf(Tool.ARROW, Tool.TEXT, Tool.SHAPE, Tool.PEN, Tool.STAMP, Tool.PAN)) {
            val e = editor.withTool(tool).tap(Point(605f, 750f))
            assertEquals("$tool", "r", e.selected)
            assertNull(e.text)
            assertEquals(editor.markup, e.markup)
        }
    }

    @Test
    fun tapOnEmptyWhileSelectedOnlyDeselects() {
        // 有東西選著時點空白：只是取消選取，不會開始打字、不會蓋印章
        val text = editor.withTool(Tool.TEXT).tap(Point(300f, 105f)).tap(Point(300f, 400f))
        assertNull(text.selected)
        assertNull(text.text)

        val stamped = editor.withStamp(StampKind.HEART).tap(Point(300f, 105f)).tap(Point(300f, 400f))
        assertNull(stamped.selected)
        assertEquals(editor.markup, stamped.markup)
    }

    @Test
    fun dragSelectedMovesItAsOneStep() {
        val selected = editor.tap(Point(300f, 105f))
        val moving = selected.press(Point(300f, 105f), "new", slop).drag(Point(350f, 205f), slop)

        // 拖的時候畫面上已經移過去了，但還沒寫進標註（不會每一格都存檔）
        assertEquals(Point(150f, 200f), (moving.visibleNodes.first { it.id == "a" } as Node.Arrow).from)
        assertEquals(arrow, moving.nodeById("a"))

        val moved = moving.release(slop, clock + 5_000)
        assertEquals(arrow.moved(Point(50f, 100f)), moved.nodeById("a"))
        assertEquals("a", moved.selected)
        // 順序不變（沒有跑到最上面）
        assertEquals(listOf("a", "r", "t", "s"), moved.markup.nodes.map { it.id })
        assertEquals(editor.markup, moved.undo().markup)
    }

    @Test
    fun dragOnUnselectedThingDrawsANewOne() {
        // 沒選到的箭頭上面拉：畫一支新的，不是移動
        val e = editor.dragFrom(Point(300f, 105f), Point(300f, 600f))
        assertEquals(arrow, e.nodeById("a"))
        assertEquals(5, e.markup.nodes.size)
    }

    @Test
    fun dragHandleResizes() {
        val selected = editor.tap(Point(605f, 750f))
        val resized = selected.dragFrom(Point(1000f, 900f), Point(1200f, 1100f))
        val r = resized.nodeById("r") as Node.Shape
        assertEquals(Bounds(600f, 600f, 1200f, 1100f), Bounds.of(r.from, r.to))
        assertEquals(1, resized.history.undo.size)
    }

    @Test
    fun movingStaysOnThePage() {
        val selected = editor.tap(Point(1000f, 1500f))
        val moved = selected.dragFrom(Point(1000f, 1500f), Point(5000f, 1500f))
        assertEquals(Point(1440f, 1500f), (moved.nodeById("s") as Node.Stamp).at)
    }

    @Test
    fun colorAndSizeChangeTheSelectedOne() {
        val selected = editor.tap(Point(300f, 105f))

        val recolored = selected.withColor(MarkupColor.GREEN)
        assertEquals(MarkupColor.GREEN, (recolored.nodeById("a") as Node.Arrow).color)
        // 之後畫的也是這個顏色
        assertEquals(MarkupColor.GREEN, recolored.color)
        assertEquals(1, recolored.history.undo.size)

        val resized = recolored.withSize(StrokeSize.XL)
        assertEquals(StrokeSize.XL, (resized.nodeById("a") as Node.Arrow).size)

        // 印章：改粗細是改大小，改顏色不變
        val stampSelected = editor.tap(Point(1000f, 1500f))
        assertEquals(StrokeSize.L, (stampSelected.withSize(StrokeSize.L).nodeById("s") as Node.Stamp).size)
        assertEquals(stampSelected.markup, stampSelected.withColor(MarkupColor.BLUE).markup)

        // 沒選東西：只改之後畫的
        assertEquals(editor.markup, editor.withColor(MarkupColor.GREEN).markup)
    }

    @Test
    fun deleteAndUndo() {
        val deleted = editor.tap(Point(300f, 105f)).deleteSelected()
        assertTrue(deleted.markup.nodes.none { it.id == "a" })
        assertNull(deleted.selected)
        assertEquals(editor.markup, deleted.undo().markup)
    }

    @Test
    fun undoDropsSelectionOfThingsThatAreGone() {
        val drawn = editor.dragFrom(Point(100f, 1700f), Point(600f, 1700f))
        val newId = drawn.markup.nodes.last().id
        val selected = drawn.tap(Point(350f, 1702f))
        assertEquals(newId, selected.selected)

        assertNull(selected.undo().selected)
    }

    @Test
    fun doubleTapTextEditsIt() {
        val once = editor.tap(Point(150f, 1230f))
        assertEquals("t", once.selected)
        assertNull(once.text)

        val twice = once.tap(Point(150f, 1230f), gapMs = 200)
        val edit = requireNotNull(twice.text)
        assertTrue(edit.existing)
        assertEquals("Hello", edit.text)
        // 改的時候畫面上先不畫原本那一段（輸入框自己顯示）
        assertTrue(twice.visibleNodes.none { it.id == "t" })

        val done = twice.editText("Hi").finishText()
        assertEquals("Hi", (done.nodeById("t") as Node.Text).text)
        assertEquals(listOf("a", "r", "t", "s"), done.markup.nodes.map { it.id })
        assertEquals("t", done.selected)
        assertEquals(editor.markup, done.undo().markup)
    }

    @Test
    fun slowSecondTapDoesNotEdit() {
        val e = editor.tap(Point(150f, 1230f)).tap(Point(150f, 1230f), gapMs = 2_000)
        assertNull(e.text)
        assertEquals("t", e.selected)
    }

    @Test
    fun clearingAnExistingTextDeletesIt() {
        val e = editor.tap(Point(150f, 1230f)).tap(Point(150f, 1230f), gapMs = 200).editText("").finishText()
        assertTrue(e.markup.nodes.none { it.id == "t" })
    }

    @Test
    fun undoWhileEditingKeepsTheOriginal() {
        val e = editor.tap(Point(150f, 1230f)).tap(Point(150f, 1230f), gapMs = 200).editText("Bye").undo()
        assertNull(e.text)
        assertEquals(editor.markup, e.markup)
    }

    @Test
    fun toggleOutline() {
        val e = editor.tap(Point(150f, 1230f)).toggleOutline()
        assertFalse((e.nodeById("t") as Node.Text).outlined)
        assertTrue((e.toggleOutline().nodeById("t") as Node.Text).outlined)
    }

    @Test
    fun stampTailAndLabel() {
        val selected = editor.tap(Point(1000f, 1500f))

        val tailed = selected.toggleTail()
        val tail = requireNotNull((tailed.nodeById("s") as Node.Stamp).tail)
        assertTrue(tail.x > 1000f && tail.y > 1500f)
        // 尾巴的端點是控制點，拉了改方向
        val pointed = tailed.dragFrom(tail, Point(400f, 1000f))
        assertEquals(Point(400f, 1000f), (pointed.nodeById("s") as Node.Stamp).tail)
        assertNull((pointed.toggleTail().nodeById("s") as Node.Stamp).tail)

        val labeling = selected.editLabel()
        assertEquals("s", labeling.text?.labelOf)
        val labeled = labeling.editText("就是這個").finishText()
        assertEquals("就是這個", (labeled.nodeById("s") as Node.Stamp).label)
        assertEquals("s", labeled.selected)
        // 打成空的就是拿掉
        val cleared = labeled.editLabel().editText(" ").finishText()
        assertNull((cleared.nodeById("s") as Node.Stamp).label)
    }

    @Test
    fun newStampUsesTheCurrentSize() {
        val e = Editor(Markup(), 1440, 1920).withStamp(StampKind.QUESTION).withSize(StrokeSize.XL).tap(Point(500f, 500f))
        assertEquals(StrokeSize.XL, (e.markup.nodes.single() as Node.Stamp).size)
    }
}
