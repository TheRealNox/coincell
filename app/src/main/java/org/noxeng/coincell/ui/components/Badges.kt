package org.noxeng.coincell.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.noxeng.coincell.library.DisplayPlatform
import org.noxeng.coincell.ui.theme.GbfTheme

/**
 * 48 dp platform badge: tinted container with the platform abbreviation as text (docs/UI_DESIGN.md 4.4, 5.1). Identity
 * comes from the text first, the tint second.
 */
@Composable
fun PlatformBadge(platform: DisplayPlatform, modifier: Modifier = Modifier) {
    val colors = GbfTheme.extended.platform(platform)
    Surface(
        modifier.size(48.dp).semantics { contentDescription = platform.spoken },
        shape = RoundedCornerShape(12.dp),
        color = colors.container,
        contentColor = colors.onContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(platform.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

val DisplayPlatform.spoken: String
    get() = when (this) {
        DisplayPlatform.GB -> "Game Boy"
        DisplayPlatform.GBC -> "Game Boy Color"
        DisplayPlatform.GBA -> "Game Boy Advance"
    }

/**
 * Generic cartridge outlines drawn as vectors, not copied from any artwork (docs/UI_DESIGN.md 4.2). They are to scale
 * relative to each other: GBA is clearly smaller and landscape. [color] is normally `onSurfaceVariant`.
 */
@Composable
fun CartridgeGlyph(platform: DisplayPlatform, modifier: Modifier = Modifier, size: Dp = 72.dp, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val widthFactor = if (platform == DisplayPlatform.GBA) 1.0f else 0.9f
    val heightFactor = if (platform == DisplayPlatform.GBA) 0.6f else 0.82f
    Canvas(modifier.size(width = size * widthFactor, height = size * heightFactor).clearAndSetSemantics { }) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = 2.5.dp.toPx(), join = StrokeJoin.Round)
        val body = Path()
        when (platform) {
            DisplayPlatform.GB, DisplayPlatform.GBC -> {
                // Wide body, two cut corners at the bottom; the Color-only variant also has the top right corner cut.
                val cut = w * 0.16f
                body.moveTo(0f, 0f)
                if (platform == DisplayPlatform.GBC) {
                    body.lineTo(w - cut, 0f)
                    body.lineTo(w, cut)
                } else {
                    body.lineTo(w, 0f)
                }
                body.lineTo(w, h - cut)
                body.lineTo(w - cut, h)
                body.lineTo(cut, h)
                body.lineTo(0f, h - cut)
                body.close()
                drawPath(body, color, style = stroke)
                drawRoundRect(color, Offset(w * 0.14f, h * 0.14f), Size(w * 0.72f, h * 0.42f), CornerRadius(6.dp.toPx()), style = stroke)
            }
            DisplayPlatform.GBA -> {
                // Small landscape body with a notch on the left and a ribbed grip on the right.
                val notch = h * 0.22f
                body.moveTo(0f, 0f)
                body.lineTo(w, 0f)
                body.lineTo(w, h)
                body.lineTo(0f, h)
                body.lineTo(0f, h / 2 + notch)
                body.lineTo(notch, h / 2)
                body.lineTo(0f, h / 2 - notch)
                body.close()
                drawPath(body, color, style = stroke)
                drawRoundRect(color, Offset(w * 0.2f, h * 0.18f), Size(w * 0.46f, h * 0.5f), CornerRadius(4.dp.toPx()), style = stroke)
                for (i in 0 until 4) {
                    val x = w * (0.74f + i * 0.06f)
                    drawLine(color, Offset(x, h * 0.2f), Offset(x, h * 0.8f), strokeWidth = 2.dp.toPx())
                }
            }
        }
    }
}
