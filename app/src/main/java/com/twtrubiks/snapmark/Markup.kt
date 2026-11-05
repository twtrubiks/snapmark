package com.twtrubiks.snapmark

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 標註的資料模型，純 Kotlin（JVM 測試）。
 * 骨架階段先把形狀定下來，實作時不夠用就改：還沒有發過版，沒有舊資料要顧。
 *
 * 座標一律是「底圖的像素」（空白畫布就是畫布的像素），跟螢幕大小、縮放無關：
 * 換一支手機、轉螢幕、重新打開，標註都在同一個位置。
 */

/** 畫布右下角的工具，照選單上的順序。形狀、筆、印章各自還有一排子選項。 */
enum class Tool {
    ARROW,
    TEXT,
    SHAPE,
    PEN,
    STAMP,
    PIXELATE,
    PAN,
}

/** 八個顏色，預設粉紅。 */
@Serializable
enum class MarkupColor(val argb: Long) {
    RED(0xFFD63410),
    ORANGE(0xFFE28520),
    YELLOW(0xFFFCF121),
    GREEN(0xFF6AD919),
    BLUE(0xFF3C78FC),
    PINK(0xFFFF4678),
    BLACK(0xFF444444),
    WHITE(0xFFFFFFFF),
}

/** 五種粗細。實際幾個像素由畫的那一層依底圖大小決定。 */
@Serializable
enum class StrokeSize { XS, S, M, L, XL }

@Serializable
enum class ShapeKind { RECT, ROUND_RECT, ELLIPSE, LINE }

@Serializable
enum class StampKind { CHECK, QUESTION, NOTICE, HEART, CROSS }

@Serializable
data class Point(val x: Float, val y: Float)

/** 畫布上的一個物件。[id] 給選取、復原用。 */
@Serializable
sealed interface Node {
    val id: String

    @Serializable
    @SerialName("arrow")
    data class Arrow(
        override val id: String,
        val from: Point,
        val to: Point,
        val color: MarkupColor,
        val size: StrokeSize,
    ) : Node

    /** [outlined]：「卡通」字（有白邊），false 是純色字。 */
    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String,
        val at: Point,
        val text: String,
        val color: MarkupColor,
        val size: StrokeSize,
        val outlined: Boolean = true,
    ) : Node

    /** 方框、圓角方框、橢圓、直線：都是從 [from] 拉到 [to]。 */
    @Serializable
    @SerialName("shape")
    data class Shape(
        override val id: String,
        val kind: ShapeKind,
        val from: Point,
        val to: Point,
        val color: MarkupColor,
        val size: StrokeSize,
    ) : Node

    /** 手畫的線。[highlighter]：螢光筆（半透明、比較粗）。 */
    @Serializable
    @SerialName("stroke")
    data class Stroke(
        override val id: String,
        val points: List<Point>,
        val color: MarkupColor,
        val size: StrokeSize,
        val highlighter: Boolean = false,
    ) : Node

    /** 印章。[label] 是旁邊的字，[tail] 是尾巴指到的位置，都可以沒有。 */
    @Serializable
    @SerialName("stamp")
    data class Stamp(
        override val id: String,
        val kind: StampKind,
        val at: Point,
        val label: String? = null,
        val tail: Point? = null,
    ) : Node

    /** 馬賽克：把底圖 [from]–[to] 這一塊打碎。 */
    @Serializable
    @SerialName("pixelate")
    data class Pixelate(
        override val id: String,
        val from: Point,
        val to: Point,
    ) : Node
}

/** 一張作品上全部的標註，照畫上去的順序（後面的蓋在前面的上面）。裁切、旋轉還沒定怎麼存，見 CLAUDE.md。 */
@Serializable
data class Markup(val nodes: List<Node> = emptyList()) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        // 之後加欄位，舊版存的 JSON 要讀得回來：新欄位一律給預設值；讀到不認得的欄位不當掉
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): Markup = json.decodeFromString(serializer(), text)
    }
}
