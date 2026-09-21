package com.personalos.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Small flat outline glyphs drawn on a 24x24 grid, mirroring the design's
 * inline-SVG icons (1.7px stroke, butt/round joins, no fills except dots).
 * Drawn with Canvas so there is no dependency on any icon set.
 */
enum class Glyph {
    Radar,
    Bell,
    Banknote,
    News,
    Plane,
    Chat,
    Briefcase,
    Broadcast,
    Tag,
    Rss,
    Eye,
    Cloud,
    CloudRain,
    Sun,
    CloudSun,
    Storm,
    Moon,
    Note,
    Tune,
    Plus,
    Home,
    Grid,
    Mail,
    Wallet,
    Person,
    Search,
    Back,
    Forward,
    Scan,
    Clock,
    Refresh,
    Dots,
    Place,
    Stack,
    Card,
    Rules,
    Bookmark,
    BookmarkFilled,
}

@Composable
fun GlyphIcon(
    glyph: Glyph,
    tint: Color,
    size: Dp = 20.dp,
    strokeWidth: Float = 1.7f,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, tint, strokeWidth) }
}

private fun DrawScope.drawGlyph(
    glyph: Glyph,
    tint: Color,
    strokeWidth: Float,
) {
    val s = size.minDimension / 24f
    if (s <= 0f) return
    val sw = strokeWidth * s
    val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun xy(
        x: Float,
        y: Float,
    ) = Offset(x * s, y * s)

    fun ln(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) = drawLine(tint, xy(x1, y1), xy(x2, y2), sw, StrokeCap.Round)

    fun dot(
        cx: Float,
        cy: Float,
        r: Float,
    ) = drawCircle(tint, r * s, xy(cx, cy))

    fun ring(
        cx: Float,
        cy: Float,
        r: Float,
    ) = drawCircle(tint, r * s, xy(cx, cy), style = stroke)

    fun box(
        l: Float,
        t: Float,
        w: Float,
        h: Float,
        r: Float = 0f,
    ) = drawRoundRect(
        color = tint,
        topLeft = xy(l, t),
        size = Size(w * s, h * s),
        cornerRadius = CornerRadius(r * s, r * s),
        style = stroke,
    )

    fun arc(
        cx: Float,
        cy: Float,
        r: Float,
        start: Float,
        sweep: Float,
    ) = drawArc(
        color = tint,
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = xy(cx - r, cy - r),
        size = Size(2 * r * s, 2 * r * s),
        style = stroke,
    )

    fun path(block: Path.() -> Unit) = drawPath(Path().apply(block), tint, style = stroke)

    /** The same shape, solid — for a state that is on rather than available. */
    fun fill(block: Path.() -> Unit) = drawPath(Path().apply(block), tint)

    when (glyph) {
        Glyph.Back -> {
            // A full arrow, not a bare chevron: at 20dp a chevron alone reads as
            // "expand/collapse" rather than "go back".
            ln(19f, 12f, 5f, 12f)
            ln(11f, 6f, 5f, 12f)
            ln(5f, 12f, 11f, 18f)
        }
        Glyph.Forward -> {
            // Mirror of Back: same shaft and head, pointing right. Drawn here
            // rather than reusing a text arrow so the search submit glyph
            // matches the set's 1.7 stroke at the same size as the search icon.
            ln(5f, 12f, 19f, 12f)
            ln(13f, 6f, 19f, 12f)
            ln(19f, 12f, 13f, 18f)
        }
        Glyph.Radar -> {
            dot(12f, 12f, 1.6f)
            ln(12f, 12f, 18f, 6f)
            arc(12f, 12f, 8f, -90f, 90f)
            arc(12f, 12f, 4f, -90f, 90f)
        }
        Glyph.Bell -> {
            arc(12f, 9f, 5f, 180f, 180f)
            ln(7f, 9f, 7f, 12f)
            ln(17f, 9f, 17f, 12f)
            ln(7f, 12f, 5.5f, 14.5f)
            ln(17f, 12f, 18.5f, 14.5f)
            ln(5.5f, 14.5f, 18.5f, 14.5f)
            arc(12f, 15f, 2f, 0f, 180f)
        }
        Glyph.Banknote -> {
            box(3f, 6f, 18f, 12f, 1.5f)
            ring(12f, 12f, 2.5f)
            ln(6.5f, 9f, 6.5f, 15f)
            ln(17.5f, 9f, 17.5f, 15f)
        }
        Glyph.News -> {
            box(4f, 4.5f, 16f, 15f, 1f)
            ln(7.5f, 9f, 16.5f, 9f)
            ln(7.5f, 12f, 16.5f, 12f)
            ln(7.5f, 15f, 12.5f, 15f)
        }
        Glyph.Plane -> {
            path {
                moveTo(2.5f * s, 12f * s)
                lineTo(21f * s, 4f * s)
                lineTo(13f * s, 21f * s)
                lineTo(10f * s, 13f * s)
                close()
            }
            ln(10f, 13f, 14f, 9f)
        }
        Glyph.Chat, Glyph.Mail -> {
            path {
                moveTo(4f * s, 5f * s)
                lineTo(20f * s, 5f * s)
                lineTo(20f * s, 16f * s)
                lineTo(9f * s, 16f * s)
                lineTo(4f * s, 20f * s)
                close()
            }
            ln(8f, 9f, 16f, 9f)
            ln(8f, 12f, 13f, 12f)
        }
        Glyph.Briefcase -> {
            box(3f, 8f, 18f, 11f, 1f)
            path {
                moveTo(9f * s, 8f * s)
                lineTo(9f * s, 6f * s)
                lineTo(15f * s, 6f * s)
                lineTo(15f * s, 8f * s)
            }
            ln(3f, 13f, 21f, 13f)
        }
        Glyph.Broadcast -> {
            ring(12f, 12f, 1.8f)
            arc(12f, 12f, 5f, 135f, 90f)
            arc(12f, 12f, 5f, -45f, 90f)
            arc(12f, 12f, 9f, 135f, 90f)
            arc(12f, 12f, 9f, -45f, 90f)
        }
        Glyph.Tag -> {
            path {
                moveTo(4f * s, 4f * s)
                lineTo(11f * s, 4f * s)
                lineTo(20f * s, 13f * s)
                lineTo(13f * s, 20f * s)
                lineTo(4f * s, 11f * s)
                close()
            }
            ring(8f, 8f, 1.3f)
        }
        Glyph.Rss -> {
            dot(6f, 18f, 1.6f)
            arc(5f, 19f, 8f, -90f, 90f)
            arc(5f, 19f, 14f, -90f, 90f)
        }
        Glyph.Eye -> {
            path {
                moveTo(2.5f * s, 12f * s)
                cubicTo(6f * s, 6.5f * s, 18f * s, 6.5f * s, 21.5f * s, 12f * s)
                cubicTo(18f * s, 17.5f * s, 6f * s, 17.5f * s, 2.5f * s, 12f * s)
                close()
            }
            ring(12f, 12f, 2.4f)
        }
        Glyph.Cloud -> drawCloud(s, tint, stroke)
        Glyph.CloudRain -> {
            drawCloud(s, tint, stroke, dy = -1.5f)
            ln(9f, 17.5f, 8f, 20f)
            ln(12.5f, 17.5f, 11.5f, 20f)
            ln(16f, 17.5f, 15f, 20f)
        }
        Glyph.Sun -> {
            ring(12f, 12f, 4.2f)
            ln(12f, 2.4f, 12f, 5.1f)
            ln(12f, 18.9f, 12f, 21.6f)
            ln(2.4f, 12f, 5.1f, 12f)
            ln(18.9f, 12f, 21.6f, 12f)
            ln(5.2f, 5.2f, 7.1f, 7.1f)
            ln(16.9f, 16.9f, 18.8f, 18.8f)
            ln(16.9f, 7.1f, 18.8f, 5.2f)
            ln(5.2f, 18.8f, 7.1f, 16.9f)
        }
        Glyph.CloudSun -> {
            // Sun drawn first, cloud over it: the overlap is what reads as
            // "partly cloudy" rather than two icons sitting side by side.
            ring(15.6f, 7.4f, 3.2f)
            ln(15.6f, 2.7f, 15.6f, 4.3f)
            ln(20.2f, 7.4f, 21.8f, 7.4f)
            ln(19.1f, 3.7f, 20.2f, 2.6f)
            ln(19.1f, 11.1f, 20.2f, 12.2f)
            drawCloud(s, tint, stroke, dy = 4f)
        }
        Glyph.Storm -> {
            drawCloud(s, tint, stroke, dy = -1.5f)
            path {
                moveTo(13f * s, 16f * s)
                lineTo(9.8f * s, 20.8f * s)
                lineTo(12.2f * s, 20.8f * s)
                lineTo(10.6f * s, 23.6f * s)
                lineTo(14.6f * s, 18.4f * s)
                lineTo(12.2f * s, 18.4f * s)
                close()
            }
        }
        Glyph.Moon -> {
            // A crescent, drawn as two arcs: a wide outer circle, then a smaller
            // one carving the bite out of the top-right. Two arcs keep the horns
            // sharp instead of rounding them off.
            path {
                arcTo(Rect(3f * s, 3f * s, 21f * s, 21f * s), 5f, 260f, true)
                arcTo(Rect(9.84f * s, 0.16f * s, 23.84f * s, 14.16f * s), 216.5f, -163f, false)
                close()
            }
        }
        Glyph.Note -> {
            path {
                moveTo(5f * s, 4f * s)
                lineTo(16f * s, 4f * s)
                lineTo(19f * s, 7f * s)
                lineTo(19f * s, 20f * s)
                lineTo(5f * s, 20f * s)
                close()
            }
            ln(8f, 9f, 16f, 9f)
            ln(8f, 12f, 16f, 12f)
            ln(8f, 15f, 13f, 15f)
        }
        Glyph.Tune -> {
            ln(4f, 7f, 11f, 7f)
            ln(15f, 7f, 20f, 7f)
            ring(13f, 7f, 2f)
            ln(4f, 12f, 6f, 12f)
            ln(10f, 12f, 20f, 12f)
            ring(8f, 12f, 2f)
            ln(4f, 17f, 13f, 17f)
            ln(17f, 17f, 20f, 17f)
            ring(15f, 17f, 2f)
        }
        Glyph.Plus -> {
            ln(12f, 5f, 12f, 19f)
            ln(5f, 12f, 19f, 12f)
        }
        Glyph.Home -> {
            path {
                moveTo(3.5f * s, 11f * s)
                lineTo(12f * s, 4f * s)
                lineTo(20.5f * s, 11f * s)
            }
            path {
                moveTo(6f * s, 9.5f * s)
                lineTo(6f * s, 20f * s)
                lineTo(18f * s, 20f * s)
                lineTo(18f * s, 9.5f * s)
            }
        }
        Glyph.Grid -> {
            box(3.5f, 3.5f, 7f, 7f)
            box(13.5f, 3.5f, 7f, 7f)
            box(3.5f, 13.5f, 7f, 7f)
            box(13.5f, 13.5f, 7f, 7f)
        }
        Glyph.Wallet -> {
            box(3f, 6f, 18f, 12f, 1.5f)
            ln(3f, 10f, 21f, 10f)
            ring(16.5f, 14f, 1.2f)
        }
        Glyph.Person -> {
            ring(12f, 8f, 3.6f)
            path {
                moveTo(4.5f * s, 20f * s)
                cubicTo(4.5f * s, 16.4f * s, 8f * s, 14.2f * s, 12f * s, 14.2f * s)
                cubicTo(16f * s, 14.2f * s, 19.5f * s, 16.4f * s, 19.5f * s, 20f * s)
            }
        }
        Glyph.Search -> {
            ring(11f, 11f, 6f)
            ln(15.3f, 15.3f, 21f, 21f)
        }
        Glyph.Scan -> {
            path {
                moveTo(4f * s, 8f * s)
                lineTo(4f * s, 4f * s)
                lineTo(8f * s, 4f * s)
            }
            path {
                moveTo(16f * s, 4f * s)
                lineTo(20f * s, 4f * s)
                lineTo(20f * s, 8f * s)
            }
            path {
                moveTo(20f * s, 16f * s)
                lineTo(20f * s, 20f * s)
                lineTo(16f * s, 20f * s)
            }
            path {
                moveTo(8f * s, 20f * s)
                lineTo(4f * s, 20f * s)
                lineTo(4f * s, 16f * s)
            }
            ln(7f, 12f, 17f, 12f)
        }
        Glyph.Clock -> {
            ring(12f, 12f, 8f)
            path {
                moveTo(12f * s, 7.5f * s)
                lineTo(12f * s, 12f * s)
                lineTo(15f * s, 14f * s)
            }
        }
        Glyph.Refresh -> {
            arc(12f, 12f, 8f, -80f, 300f)
            ln(15.5f, 3.5f, 20.5f, 3.5f)
            ln(20.5f, 3.5f, 20.5f, 8.5f)
        }
        Glyph.Dots -> {
            dot(12f, 5f, 1.7f)
            dot(12f, 12f, 1.7f)
            dot(12f, 19f, 1.7f)
        }
        Glyph.Place -> {
            path {
                moveTo(12f * s, 21f * s)
                cubicTo(16f * s, 16.2f * s, 18f * s, 13f * s, 18f * s, 10.5f * s)
                cubicTo(18f * s, 7.2f * s, 15.3f * s, 4.5f * s, 12f * s, 4.5f * s)
                cubicTo(8.7f * s, 4.5f * s, 6f * s, 7.2f * s, 6f * s, 10.5f * s)
                cubicTo(6f * s, 13f * s, 8f * s, 16.2f * s, 12f * s, 21f * s)
                close()
            }
            ring(12f, 10.5f, 2.2f)
        }
        Glyph.Stack -> {
            box(4f, 4f, 16f, 6f, 1f)
            box(4f, 14f, 16f, 6f, 1f)
            ln(8f, 7f, 12f, 7f)
            ln(8f, 17f, 12f, 17f)
        }
        Glyph.Card -> {
            box(3f, 6f, 18f, 12f, 1.5f)
            ln(3f, 10f, 21f, 10f)
            ln(7f, 14f, 10f, 14f)
        }
        Glyph.Rules -> {
            ln(4f, 6f, 20f, 6f)
            ln(4f, 12f, 20f, 12f)
            ln(4f, 18f, 14f, 18f)
            ring(19.5f, 18f, 2f)
        }
        // A ribbon with a notch: outline when it can be saved, solid once it is.
        Glyph.Bookmark -> bookmarkRibbon(s, ::path)
        Glyph.BookmarkFilled -> bookmarkRibbon(s, ::fill)
    }
}

/** The bookmark ribbon at 24-unit scale, drawn by [emit] (stroke or fill). */
private fun DrawScope.bookmarkRibbon(
    s: Float,
    emit: (Path.() -> Unit) -> Unit,
) {
    emit {
        moveTo(6.2f * s, 3.6f * s)
        lineTo(17.8f * s, 3.6f * s)
        lineTo(17.8f * s, 20.8f * s)
        lineTo(12f * s, 15.4f * s)
        lineTo(6.2f * s, 20.8f * s)
        close()
    }
}

private fun DrawScope.drawCloud(
    s: Float,
    tint: Color,
    stroke: Stroke,
    dy: Float = 0f,
) {
    val p =
        Path().apply {
            moveTo(7.5f * s, (16.5f + dy) * s)
            cubicTo(4.5f * s, (16.5f + dy) * s, 3.5f * s, (13.5f + dy) * s, 5.2f * s, (11.6f + dy) * s)
            cubicTo(4.8f * s, (8.2f + dy) * s, 8.6f * s, (6.2f + dy) * s, 10.8f * s, (8.4f + dy) * s)
            cubicTo(12.2f * s, (5.8f + dy) * s, 16.4f * s, (6.2f + dy) * s, 16.8f * s, (9.6f + dy) * s)
            cubicTo(19.8f * s, (9.8f + dy) * s, 20.6f * s, (14.2f + dy) * s, 17.6f * s, (16.0f + dy) * s)
            cubicTo(15f * s, (16.5f + dy) * s, 9.5f * s, (16.5f + dy) * s, 7.5f * s, (16.5f + dy) * s)
            close()
        }
    drawPath(p, tint, style = stroke)
}
