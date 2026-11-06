package com.twtrubiks.snapmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupTest {
    @Test
    fun jsonRoundTripKeepsEveryKindOfNode() {
        val markup = Markup(
            listOf(
                Node.Arrow("1", Point(0f, 0f), Point(10f, 10f), MarkupColor.PINK, StrokeSize.M),
                Node.Text("2", Point(5f, 5f), "這裡", MarkupColor.RED, StrokeSize.L, outlined = false),
                Node.Shape("3", ShapeKind.ROUND_RECT, Point(1f, 1f), Point(9f, 9f), MarkupColor.BLUE, StrokeSize.S),
                Node.Stroke("4", listOf(Point(0f, 0f), Point(1f, 2f)), MarkupColor.YELLOW, StrokeSize.XL, highlighter = true),
                Node.Stamp("5", StampKind.CHECK, Point(3f, 3f), label = "OK", tail = Point(4f, 8f)),
                Node.Pixelate("6", Point(2f, 2f), Point(6f, 6f)),
            ),
        )

        assertEquals(markup, Markup.fromJson(markup.toJson()))
    }

    /*
     * 發版之後手機上存的就是這些 JSON：欄位的名字、type、enum 的名字一個字都不能變。
     * 這裡的字串是寫死的，改了模型讓它讀不回來（或寫出來不一樣）就是破壞相容，不要跟著改字串。
     */
    private val frozen: List<Pair<String, Node>> = listOf(
        """{"type":"arrow","id":"1","from":{"x":0.0,"y":0.5},"to":{"x":10.0,"y":20.0},"color":"PINK","size":"M"}""" to
            Node.Arrow("1", Point(0f, 0.5f), Point(10f, 20f), MarkupColor.PINK, StrokeSize.M),
        """{"type":"text","id":"2","at":{"x":5.0,"y":6.0},"text":"這裡\n第二行","color":"RED","size":"L","outlined":false,"scale":2.5}""" to
            Node.Text("2", Point(5f, 6f), "這裡\n第二行", MarkupColor.RED, StrokeSize.L, outlined = false, scale = 2.5f),
        """{"type":"shape","id":"3","kind":"ROUND_RECT","from":{"x":1.0,"y":2.0},"to":{"x":9.0,"y":8.0},"color":"BLUE","size":"XS"}""" to
            Node.Shape("3", ShapeKind.ROUND_RECT, Point(1f, 2f), Point(9f, 8f), MarkupColor.BLUE, StrokeSize.XS),
        """{"type":"stroke","id":"4","points":[{"x":0.0,"y":0.0},{"x":1.0,"y":2.0}],"color":"YELLOW","size":"XL","highlighter":true}""" to
            Node.Stroke("4", listOf(Point(0f, 0f), Point(1f, 2f)), MarkupColor.YELLOW, StrokeSize.XL, highlighter = true),
        """{"type":"stamp","id":"5","kind":"CHECK","at":{"x":3.0,"y":4.0},"label":"OK","tail":{"x":4.0,"y":8.0},"size":"S"}""" to
            Node.Stamp("5", StampKind.CHECK, Point(3f, 4f), label = "OK", tail = Point(4f, 8f), size = StrokeSize.S),
        """{"type":"pixelate","id":"6","from":{"x":2.0,"y":3.0},"to":{"x":6.0,"y":7.0},"scale":1.5}""" to
            Node.Pixelate("6", Point(2f, 3f), Point(6f, 7f), scale = 1.5f),
    )

    @Test
    fun storedJsonOfEveryKindOfNodeStaysReadable() {
        val json = frozen.joinToString(",", """{"nodes":[""", "]}") { it.first }
        val markup = Markup(frozen.map { it.second })

        assertEquals(markup, Markup.fromJson(json))
        // 寫出來的也一樣：舊版的 App 讀得了新版存的（沒有多寫、改名）
        assertEquals(json, markup.toJson())
    }

    @Test
    fun fieldsWithDefaultsMayBeMissing() {
        // 每一種最少要有的欄位：之後加的欄位都要有預設值，這幾句才讀得回來
        val json = """{"nodes":[
            {"type":"text","id":"2","at":{"x":5,"y":6},"text":"字","color":"BLACK","size":"S"},
            {"type":"shape","id":"3","kind":"LINE","from":{"x":1,"y":2},"to":{"x":9,"y":8},"color":"GREEN","size":"L"},
            {"type":"shape","id":"3b","kind":"RECT","from":{"x":1,"y":2},"to":{"x":9,"y":8},"color":"ORANGE","size":"M"},
            {"type":"shape","id":"3c","kind":"ELLIPSE","from":{"x":1,"y":2},"to":{"x":9,"y":8},"color":"WHITE","size":"M"},
            {"type":"stroke","id":"4","points":[{"x":0,"y":0}],"color":"PINK","size":"M"},
            {"type":"stamp","id":"5","kind":"QUESTION","at":{"x":3,"y":4}},
            {"type":"stamp","id":"5b","kind":"NOTICE","at":{"x":3,"y":4}},
            {"type":"stamp","id":"5c","kind":"HEART","at":{"x":3,"y":4}},
            {"type":"stamp","id":"5d","kind":"CROSS","at":{"x":3,"y":4}},
            {"type":"pixelate","id":"6","from":{"x":2,"y":3},"to":{"x":6,"y":7}}
        ]}"""

        assertEquals(
            listOf(
                Node.Text("2", Point(5f, 6f), "字", MarkupColor.BLACK, StrokeSize.S, outlined = true, scale = 1f),
                Node.Shape("3", ShapeKind.LINE, Point(1f, 2f), Point(9f, 8f), MarkupColor.GREEN, StrokeSize.L, scale = 1f),
                Node.Shape("3b", ShapeKind.RECT, Point(1f, 2f), Point(9f, 8f), MarkupColor.ORANGE, StrokeSize.M, scale = 1f),
                Node.Shape("3c", ShapeKind.ELLIPSE, Point(1f, 2f), Point(9f, 8f), MarkupColor.WHITE, StrokeSize.M, scale = 1f),
                Node.Stroke("4", listOf(Point(0f, 0f)), MarkupColor.PINK, StrokeSize.M, highlighter = false, scale = 1f),
                Node.Stamp("5", StampKind.QUESTION, Point(3f, 4f), label = null, tail = null, size = StrokeSize.M, scale = 1f),
                Node.Stamp("5b", StampKind.NOTICE, Point(3f, 4f)),
                Node.Stamp("5c", StampKind.HEART, Point(3f, 4f)),
                Node.Stamp("5d", StampKind.CROSS, Point(3f, 4f)),
                Node.Pixelate("6", Point(2f, 3f), Point(6f, 7f), scale = 1f),
            ),
            Markup.fromJson(json).nodes,
        )
    }

    @Test
    fun unknownFieldsFromNewerVersionsAreIgnored() {
        val json = """{"nodes":[],"somethingNew":1}"""

        assertTrue(Markup.fromJson(json).nodes.isEmpty())
    }

    @Test
    fun scaleIsOnlyStoredAfterACrop() {
        val plain = Node.Arrow("1", Point(0f, 0f), Point(10f, 10f), MarkupColor.PINK, StrokeSize.M)
        // 沒裁切過的不寫 scale：有 scale 之前存的 JSON 讀回來就是 1
        assertTrue("scale" !in Markup(listOf(plain)).toJson())
        assertEquals(1f, Markup.fromJson("""{"nodes":[{"type":"arrow","id":"1","from":{"x":0,"y":0},"to":{"x":1,"y":1},"color":"PINK","size":"M"}]}""").nodes.single().scale, 0f)

        val scaled = Markup(listOf(plain.copy(scale = 2.5f)))
        assertEquals(scaled, Markup.fromJson(scaled.toJson()))
    }
}
