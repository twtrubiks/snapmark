package com.twtrubiks.snapmark

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 畫布四個角落浮在圖上面的控制項。狀態都在 CanvasSession，這裡只負責長相。
 * testTag 給 DemoRecordingTest 用（By.res(...)），列在 CLAUDE.md 的「錄影測試」。
 */

/** 工具選單裡有的工具，由下到上。馬賽克只打碎底圖：空白畫布上什麼都看不出來，只有照片的作品才有。 */
fun canvasTools(hasBase: Boolean): List<Tool> =
    listOfNotNull(Tool.ARROW, Tool.TEXT, Tool.SHAPE, Tool.PEN, Tool.STAMP, Tool.PIXELATE.takeIf { hasBase }, Tool.PAN)

val Tool.label: String
    get() = when (this) {
        Tool.ARROW -> "箭頭"
        Tool.TEXT -> "文字"
        Tool.SHAPE -> "形狀"
        Tool.PEN -> "筆"
        Tool.STAMP -> "印章"
        Tool.PIXELATE -> "馬賽克"
        Tool.PAN -> "平移"
    }

val Tool.tag: String get() = "tool_${name.lowercase()}"

/** 有子選項的工具：點一下先往左展開一排子選項。 */
private val Tool.hasOptions get() = this == Tool.SHAPE || this == Tool.PEN || this == Tool.STAMP

val ShapeKind.label: String
    get() = when (this) {
        ShapeKind.RECT -> "方框"
        ShapeKind.ROUND_RECT -> "圓角方框"
        ShapeKind.LINE -> "直線"
        ShapeKind.ELLIPSE -> "橢圓"
    }

val ShapeKind.tag: String get() = "shape_${name.lowercase()}"

fun penLabel(highlighter: Boolean) = if (highlighter) "螢光筆" else "麥克筆"

fun penTag(highlighter: Boolean) = if (highlighter) "pen_highlighter" else "pen_marker"

val StampKind.label: String
    get() = when (this) {
        StampKind.CHECK -> "打勾"
        StampKind.QUESTION -> "問號"
        StampKind.NOTICE -> "驚嘆號"
        StampKind.HEART -> "愛心"
        StampKind.CROSS -> "叉叉"
    }

val StampKind.tag: String get() = "stamp_${name.lowercase()}"

val MarkupColor.label: String
    get() = when (this) {
        MarkupColor.RED -> "紅"
        MarkupColor.ORANGE -> "橘"
        MarkupColor.YELLOW -> "黃"
        MarkupColor.GREEN -> "綠"
        MarkupColor.BLUE -> "藍"
        MarkupColor.PINK -> "粉紅"
        MarkupColor.BLACK -> "黑"
        MarkupColor.WHITE -> "白"
    }

val MarkupColor.tag: String get() = "color_${name.lowercase()}"

val MarkupColor.compose: Color get() = Color(argb.toInt())

val StrokeSize.label: String
    get() = when (this) {
        StrokeSize.XS -> "特小"
        StrokeSize.S -> "小"
        StrokeSize.M -> "中"
        StrokeSize.L -> "大"
        StrokeSize.XL -> "特大"
    }

val StrokeSize.tag: String get() = "size_${name.lowercase()}"

/** 粗細在按鈕上畫成多大的點。 */
private val StrokeSize.dot: Dp
    get() = when (this) {
        StrokeSize.XS -> 6.dp
        StrokeSize.S -> 10.dp
        StrokeSize.M -> 14.dp
        StrokeSize.L -> 19.dp
        StrokeSize.XL -> 25.dp
    }

/** 浮在畫布上的圓形按鈕。 */
@Composable
fun RoundButton(
    onClick: () -> Unit,
    tag: String,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier
            .size(size)
            .testTag(tag)
            .semantics {
                contentDescription = description
                role = Role.Button
                this.selected = selected
            },
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun IconRoundButton(@DrawableRes icon: Int, description: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    RoundButton(onClick = onClick, tag = tag, description = description, modifier = modifier) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

/** 工具的圖示。形狀、筆、印章顯示 [choice] 目前選的那一種。 */
@Composable
fun ToolIcon(tool: Tool, choice: ToolChoice, modifier: Modifier = Modifier) {
    when (tool) {
        Tool.ARROW -> ArrowIcon(modifier)
        Tool.TEXT -> Icon(painterResource(R.drawable.ic_text), contentDescription = null, modifier = modifier.size(24.dp))
        Tool.SHAPE -> ShapeIcon(choice.shape, modifier)
        Tool.PEN -> PenIcon(choice.highlighter, modifier)
        Tool.STAMP -> StampIcon(choice.stamp, modifier)
        Tool.PIXELATE -> Icon(painterResource(R.drawable.ic_mosaic), contentDescription = null, modifier = modifier.size(24.dp))
        Tool.PAN -> Icon(painterResource(R.drawable.ic_pan), contentDescription = null, modifier = modifier.size(24.dp))
    }
}

/** 箭頭直接用畫布上那個形狀（[arrowOutline]）畫，看起來跟畫出來的一樣。 */
@Composable
private fun ArrowIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val outline = arrowOutline(
            Point(size.width * 0.12f, size.height * 0.88f),
            Point(size.width * 0.9f, size.height * 0.1f),
            width = size.width * 0.09f,
        )
        val path = Path().apply {
            outline.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun ShapeIcon(kind: ShapeKind, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
        val topLeft = Offset(size.width * 0.12f, size.height * 0.24f)
        val box = Size(size.width * 0.76f, size.height * 0.52f)
        when (kind) {
            ShapeKind.RECT -> drawRect(color, topLeft, box, style = stroke)
            ShapeKind.ROUND_RECT -> drawRoundRect(color, topLeft, box, CornerRadius(4.dp.toPx()), style = stroke)
            ShapeKind.ELLIPSE -> drawOval(color, topLeft, box, style = stroke)
            ShapeKind.LINE -> drawLine(
                color,
                Offset(size.width * 0.15f, size.height * 0.85f),
                Offset(size.width * 0.85f, size.height * 0.15f),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun PenIcon(highlighter: Boolean, modifier: Modifier = Modifier) {
    Icon(
        painterResource(if (highlighter) R.drawable.ic_highlighter else R.drawable.ic_pen),
        contentDescription = null,
        modifier = modifier.size(24.dp),
    )
}

/** 印章用畫布上那個樣子（[StampArt]）畫。 */
@Composable
private fun StampIcon(kind: StampKind, modifier: Modifier = Modifier) {
    val art = remember { StampArt() }
    Canvas(modifier.size(28.dp)) {
        drawIntoCanvas {
            art.draw(it.nativeCanvas, kind, size.width / 2, size.height / 2, size.minDimension * 0.45f, shadow = false)
        }
    }
}

/** 字貼在按鈕左邊的小標籤。 */
@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * 右下角：圓鈕顯示目前的工具，點一下往上展開 [tools]（由下到上：箭頭、文字、形狀、筆、印章、馬賽克、平移），選了就收起來。
 * 形狀、筆、印章點一下先往左展開一排子選項，再點一下工具本身就用目前的那一種。
 * 橫的時候放不下，展開的那一串可以上下捲。
 */
@Composable
fun ToolPicker(
    choice: ToolChoice,
    tools: List<Tool>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onTool: (Tool) -> Unit,
    onShape: (ShapeKind) -> Unit,
    onPen: (highlighter: Boolean) -> Unit,
    onStamp: (StampKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 哪一個工具的子選項展開著；收起來就重來
    var open by remember(expanded) { mutableStateOf<Tool?>(null) }
    Column(
        modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
    ) {
        AnimatedVisibility(
            expanded,
            modifier = Modifier.weight(1f, fill = false),
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                // 留一點邊，按鈕的影子才不會被捲動的範圍切掉
                Modifier.verticalScroll(rememberScrollState()).padding(4.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (tool in tools.reversed()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (open == tool) {
                            ToolOptions(tool, choice, onShape, onPen, onStamp)
                        } else {
                            Label(tool.label)
                        }
                        RoundButton(
                            onClick = { if (tool.hasOptions && open != tool) open = tool else onTool(tool) },
                            tag = tool.tag,
                            description = tool.label,
                            selected = tool == choice.tool,
                        ) { ToolIcon(tool, choice) }
                    }
                }
            }
        }
        RoundButton(onClick = onToggle, tag = "tool_button", description = "工具：${choice.tool.label}", size = 56.dp) {
            ToolIcon(choice.tool, choice, Modifier.size(28.dp))
        }
    }
}

/** 形狀、筆、印章的子選項，一排往左展開。 */
@Composable
private fun ToolOptions(
    tool: Tool,
    choice: ToolChoice,
    onShape: (ShapeKind) -> Unit,
    onPen: (highlighter: Boolean) -> Unit,
    onStamp: (StampKind) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (tool) {
            Tool.SHAPE -> for (kind in ShapeKind.entries) {
                RoundButton(
                    onClick = { onShape(kind) },
                    tag = kind.tag,
                    description = kind.label,
                    size = 44.dp,
                    selected = choice.tool == Tool.SHAPE && choice.shape == kind,
                ) { ShapeIcon(kind) }
            }
            Tool.PEN -> for (highlighter in listOf(false, true)) {
                RoundButton(
                    onClick = { onPen(highlighter) },
                    tag = penTag(highlighter),
                    description = penLabel(highlighter),
                    size = 44.dp,
                    selected = choice.tool == Tool.PEN && choice.highlighter == highlighter,
                ) { PenIcon(highlighter) }
            }
            Tool.STAMP -> for (kind in StampKind.entries) {
                RoundButton(
                    onClick = { onStamp(kind) },
                    tag = kind.tag,
                    description = kind.label,
                    size = 44.dp,
                    selected = choice.tool == Tool.STAMP && choice.stamp == kind,
                ) { StampIcon(kind) }
            }
            else -> Unit
        }
    }
}

/**
 * 左下角：圓鈕顯示目前的顏色，點的大小跟著目前的粗細。點一下往上展開八個顏色，
 * 最下面這顆（就是它自己）再往右展開五種粗細。橫的時候放不下，顏色那一串可以上下捲。
 */
@Composable
fun ColorPicker(
    color: MarkupColor,
    size: StrokeSize,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPickColor: (MarkupColor) -> Unit,
    onPickSize: (StrokeSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
        AnimatedVisibility(
            expanded,
            modifier = Modifier.weight(1f, fill = false),
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                // 44dp 的顏色對齊下面 56dp 那顆的中線；留一點邊，影子才不會被切掉
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 由下到上：紅、橘、黃、綠、藍、粉紅、黑、白
                for (c in MarkupColor.entries.reversed()) {
                    RoundButton(
                        onClick = { onPickColor(c) },
                        tag = c.tag,
                        description = c.label,
                        size = 44.dp,
                        selected = c == color,
                    ) { ColorDot(c, 30.dp) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButton(onClick = onToggle, tag = "color_button", description = "顏色：${color.label}，粗細：${size.label}", size = 56.dp) {
                ColorDot(color, size.dot + 12.dp)
            }
            AnimatedVisibility(
                expanded,
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in StrokeSize.entries) {
                        RoundButton(
                            onClick = { onPickSize(s) },
                            tag = s.tag,
                            description = s.label,
                            size = 44.dp,
                            selected = s == size,
                        ) { ColorDot(color, s.dot) }
                    }
                }
            }
        }
    }
}

/** 一個顏色的圓點。白色、黃色在淺色按鈕上加一圈細框才看得到。 */
@Composable
private fun ColorDot(color: MarkupColor, diameter: Dp) {
    Canvas(
        Modifier
            .size(diameter)
            .border(1.dp, Color.Black.copy(alpha = 0.25f), CircleShape),
    ) {
        drawCircle(color.compose, radius = size.minDimension / 2, center = Offset(size.width / 2, size.height / 2))
    }
}
