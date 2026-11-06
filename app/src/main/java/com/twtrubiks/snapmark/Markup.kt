package com.twtrubiks.snapmark

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 標註的資料模型，純 Kotlin（JVM 測試）。
 * 存成 JSON，發版之後就是要長期相容的格式（見最下面的 MarkupJson）。
 *
 * 座標一律是「底圖的像素」（空白畫布就是畫布的像素），跟螢幕大小、縮放無關：
 * 換一支手機、轉螢幕、重新打開，標註都在同一個位置。
 *
 * 粗細、字級、印章大小、馬賽克的格子是跟著底圖的長邊算的（[StrokeSize.width]）。裁切之後長邊變短，
 * 之前畫的要維持原本的像素大小（不然字會變小、馬賽克會變細看得出底下的字）：每個物件的 [Node.scale] 記著要再乘幾倍，
 * 新畫的是 1（跟著裁切後的長邊）。選到之後改粗細就回到 1。
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

/** 五種粗細。實際幾個像素跟著底圖的長邊（`StrokeSize.width`，再乘 `Node.scale`：用 `Selection.kt` 的 `lineWidth` 等）。 */
@Serializable
enum class StrokeSize { XS, S, M, L, XL }

/** 形狀，照選單上的順序：方框、圓角方框、直線、橢圓。 */
@Serializable
enum class ShapeKind { RECT, ROUND_RECT, LINE, ELLIPSE }

@Serializable
enum class StampKind { CHECK, QUESTION, NOTICE, HEART, CROSS }

@Serializable
data class Point(val x: Float, val y: Float)

/** 畫布上的一個物件。[id] 給選取、復原用。[scale] 見檔案開頭（裁切之後的大小）。 */
@Serializable
sealed interface Node {
    val id: String
    val scale: Float

    @Serializable
    @SerialName("arrow")
    data class Arrow(
        override val id: String,
        val from: Point,
        val to: Point,
        val color: MarkupColor,
        val size: StrokeSize,
        override val scale: Float = 1f,
    ) : Node

    /** [at] 是文字框的左上角，可以有好幾行（`\n`）。[outlined]：「卡通」字（有白邊），false 是純色字。 */
    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String,
        val at: Point,
        val text: String,
        val color: MarkupColor,
        val size: StrokeSize,
        val outlined: Boolean = true,
        override val scale: Float = 1f,
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
        override val scale: Float = 1f,
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
        override val scale: Float = 1f,
    ) : Node

    /**
     * 印章，[at] 是中心，大小跟著 [size]（選到之後改粗細就是改它的大小）。
     * [label] 是右邊的字，[tail] 是尾巴指到的位置，都可以沒有。
     */
    @Serializable
    @SerialName("stamp")
    data class Stamp(
        override val id: String,
        val kind: StampKind,
        val at: Point,
        val label: String? = null,
        val tail: Point? = null,
        val size: StrokeSize = StrokeSize.M,
        override val scale: Float = 1f,
    ) : Node

    /** 馬賽克：把底圖 [from]–[to] 這一塊打碎。 */
    @Serializable
    @SerialName("pixelate")
    data class Pixelate(
        override val id: String,
        val from: Point,
        val to: Point,
        override val scale: Float = 1f,
    ) : Node
}

/**
 * 一張作品上全部的標註，照畫上去的順序（後面的蓋在前面的上面）。
 * 裁切、旋轉直接改底圖與每個物件的座標（2026-11-05 使用者確認），不另外記檢視範圍：見 Transform.kt。
 */
@Serializable
data class Markup(val nodes: List<Node> = emptyList()) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        // 之後加欄位，舊版存的 JSON 要讀得回來：新欄位一律給預設值；讀到不認得的欄位不當掉
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): Markup = json.decodeFromString(serializer(), text)
    }
}
