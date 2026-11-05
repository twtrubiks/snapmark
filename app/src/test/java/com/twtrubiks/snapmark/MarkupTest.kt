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

    @Test
    fun unknownFieldsFromNewerVersionsAreIgnored() {
        val json = """{"nodes":[],"somethingNew":1}"""

        assertTrue(Markup.fromJson(json).nodes.isEmpty())
    }
}
