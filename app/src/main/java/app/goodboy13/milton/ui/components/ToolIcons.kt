package app.goodboy13.milton.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * Custom vector icon for Technical Pen:
 * Features a distinct fountain pen nib with sharp tip, shoulders, center slit, and breather hole.
 */
@Composable
fun PenIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val scale = w / 24f
        fun pt(x: Float, y: Float) = Offset(x * scale, y * scale)

        // Pen nib outer polygon
        val nibPath = Path().apply {
            moveTo(5.5f * scale, 18.5f * scale) // sharp tip
            lineTo(8.5f * scale, 13f * scale)   // left taper
            lineTo(12f * scale, 9.5f * scale)   // left shoulder
            lineTo(14.5f * scale, 12f * scale)  // neck top
            lineTo(11f * scale, 15.5f * scale)  // right shoulder
            close()
        }
        drawPath(nibPath, color = tint)

        // Pen collar / barrel base
        val barrelPath = Path().apply {
            moveTo(12f * scale, 9.5f * scale)
            lineTo(17.5f * scale, 4f * scale)
            lineTo(20f * scale, 6.5f * scale)
            lineTo(14.5f * scale, 12f * scale)
            close()
        }
        drawPath(barrelPath, color = tint.copy(alpha = 0.50f))

        // Center ink slit
        drawLine(
            color = Color(0xFF181A1F),
            start = pt(5.5f, 18.5f),
            end = pt(11f, 13f),
            strokeWidth = 1.4f * scale,
            cap = StrokeCap.Round
        )

        // Breather hole
        drawCircle(
            color = Color(0xFF181A1F),
            radius = 1.2f * scale,
            center = pt(11f, 13f)
        )
    }
}

/**
 * Custom vector icon for Graphite Pencil:
 * Features a sharpened wooden collar, dark lead tip, hexagonal faceted barrel, and spine line.
 */
@Composable
fun PencilIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val scale = w / 24f
        fun pt(x: Float, y: Float) = Offset(x * scale, y * scale)

        // 1. Graphite lead tip
        val tipPath = Path().apply {
            moveTo(4f * scale, 20f * scale)
            lineTo(6.5f * scale, 15.5f * scale)
            lineTo(8.5f * scale, 17.5f * scale)
            close()
        }
        drawPath(tipPath, color = tint)

        // 2. Sharpened wood cone collar
        val woodPath = Path().apply {
            moveTo(6.5f * scale, 15.5f * scale)
            lineTo(9.5f * scale, 10.5f * scale)
            lineTo(13.5f * scale, 14.5f * scale)
            lineTo(8.5f * scale, 17.5f * scale)
            close()
        }
        drawPath(woodPath, color = tint.copy(alpha = 0.40f))

        // 3. Hexagonal pencil barrel
        val barrelPath = Path().apply {
            moveTo(9.5f * scale, 10.5f * scale)
            lineTo(16.5f * scale, 3.5f * scale)
            lineTo(20.5f * scale, 7.5f * scale)
            lineTo(13.5f * scale, 14.5f * scale)
            close()
        }
        drawPath(barrelPath, color = tint)

        // 4. Center spine facet line
        drawLine(
            color = Color(0xFF181A1F).copy(alpha = 0.55f),
            start = pt(11.5f, 12.5f),
            end = pt(18.5f, 5.5f),
            strokeWidth = 1.3f * scale
        )

        // 5. Back ferrule top edge
        drawLine(
            color = tint.copy(alpha = 0.85f),
            start = pt(16.5f, 3.5f),
            end = pt(20.5f, 7.5f),
            strokeWidth = 2.0f * scale
        )
    }
}

/**
 * Custom vector icon for Paintbrush:
 * Features curved bristle belly, crimped metal ferrule, and slender tapered handle.
 */
@Composable
fun PaintbrushIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val scale = w / 24f
        fun pt(x: Float, y: Float) = Offset(x * scale, y * scale)

        // 1. Bristle hairs pointing down-left
        val bristlePath = Path().apply {
            moveTo(4f * scale, 20f * scale) // bristle tip
            cubicTo(
                5f * scale, 15.5f * scale,
                7f * scale, 13.5f * scale,
                10f * scale, 12f * scale
            )
            lineTo(12f * scale, 14f * scale)
            cubicTo(
                10.5f * scale, 17f * scale,
                8.5f * scale, 19f * scale,
                4f * scale, 20f * scale
            )
            close()
        }
        drawPath(bristlePath, color = tint)

        // 2. Metal ferrule collar band
        val ferrulePath = Path().apply {
            moveTo(10f * scale, 12f * scale)
            lineTo(13f * scale, 9f * scale)
            lineTo(15f * scale, 11f * scale)
            lineTo(12f * scale, 14f * scale)
            close()
        }
        drawPath(ferrulePath, color = tint.copy(alpha = 0.50f))

        // Ferrule crimp ridge
        drawLine(
            color = Color(0xFF181A1F).copy(alpha = 0.60f),
            start = pt(11.5f, 10.5f),
            end = pt(13.5f, 12.5f),
            strokeWidth = 1.1f * scale
        )

        // 3. Long slender handle
        val handlePath = Path().apply {
            moveTo(13f * scale, 9f * scale)
            lineTo(20f * scale, 2f * scale)
            lineTo(22f * scale, 4f * scale)
            lineTo(15f * scale, 11f * scale)
            close()
        }
        drawPath(handlePath, color = tint.copy(alpha = 0.85f))
    }
}

/**
 * Custom vector icon for Art Eraser:
 * Features a beveled rubber wedge with front chisel angle and paper wrapper sleeve.
 */
@Composable
fun EraserIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val scale = w / 24f
        fun pt(x: Float, y: Float) = Offset(x * scale, y * scale)

        // 1. Exposed front rubber wedge
        val rubberPath = Path().apply {
            moveTo(4f * scale, 16.5f * scale)
            lineTo(8.5f * scale, 8f * scale)
            lineTo(13.5f * scale, 11f * scale)
            lineTo(9f * scale, 19.5f * scale)
            close()
        }
        drawPath(rubberPath, color = tint)

        // Front chisel bevel
        val bevelPath = Path().apply {
            moveTo(4f * scale, 16.5f * scale)
            lineTo(7.5f * scale, 20f * scale)
            lineTo(9f * scale, 19.5f * scale)
            close()
        }
        drawPath(bevelPath, color = tint.copy(alpha = 0.70f))

        // 2. Paper wrapper sleeve
        val sleevePath = Path().apply {
            moveTo(8.5f * scale, 8f * scale)
            lineTo(14.5f * scale, 3.5f * scale)
            lineTo(19.5f * scale, 6.5f * scale)
            lineTo(13.5f * scale, 11f * scale)
            close()
        }
        drawPath(sleevePath, color = tint.copy(alpha = 0.35f))

        // Sleeve side flank
        val sleeveSidePath = Path().apply {
            moveTo(13.5f * scale, 11f * scale)
            lineTo(19.5f * scale, 6.5f * scale)
            lineTo(20f * scale, 10f * scale)
            lineTo(14f * scale, 14.5f * scale)
            close()
        }
        drawPath(sleeveSidePath, color = tint.copy(alpha = 0.50f))

        // Sleeve edge line
        drawLine(
            color = Color(0xFF181A1F).copy(alpha = 0.70f),
            start = pt(8.5f, 8f),
            end = pt(13.5f, 11f),
            strokeWidth = 1.3f * scale
        )
    }
}

/**
 * Custom vector icon for Lasso Selection Tool:
 * Features a dynamic looped rope selection contour with hanging knot/tail.
 */
@Composable
fun LassoIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val scale = w / 24f
        val strokeW = 1.75f * scale

        // Main lasso loop
        val loopPath = Path().apply {
            moveTo(12f * scale, 15f * scale)
            cubicTo(
                6f * scale, 15f * scale,
                3.5f * scale, 9f * scale,
                8.5f * scale, 5.5f * scale
            )
            cubicTo(
                13.5f * scale, 2f * scale,
                20.5f * scale, 6f * scale,
                18.5f * scale, 12f * scale
            )
            cubicTo(
                17f * scale, 16.5f * scale,
                13f * scale, 17f * scale,
                11f * scale, 17.5f * scale
            )
        }
        drawPath(
            path = loopPath,
            color = tint,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = strokeW,
                cap = StrokeCap.Round
            )
        )

        // Hanging tail / knot
        val tailPath = Path().apply {
            moveTo(11f * scale, 17.5f * scale)
            quadraticTo(
                8f * scale, 19f * scale,
                7f * scale, 21.5f * scale
            )
        }
        drawPath(
            path = tailPath,
            color = tint.copy(alpha = 0.75f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = strokeW * 0.9f,
                cap = StrokeCap.Round
            )
        )

        // Small knot dot
        drawCircle(
            color = tint,
            radius = 1.4f * scale,
            center = Offset(11.5f * scale, 17f * scale)
        )
    }
}

/**
 * Custom vector icon for Flip Canvas Horizontally:
 * Shows mirrored geometric triangles reflected across a central vertical symmetry axis.
 */
@Composable
fun FlipCanvasIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val scale = w / 24f
        val cx = 12f * scale

        // Central vertical reflection axis (dashed)
        val dashEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f * scale, 3f * scale), 0f)
        drawLine(
            color = tint.copy(alpha = 0.70f),
            start = Offset(cx, 3.5f * scale),
            end = Offset(cx, 20.5f * scale),
            strokeWidth = 1.2f * scale,
            pathEffect = dashEffect
        )

        // Left triangle (solid)
        val leftPath = Path().apply {
            moveTo(10f * scale, 5.5f * scale)
            lineTo(3.5f * scale, 12f * scale)
            lineTo(10f * scale, 18.5f * scale)
            close()
        }
        drawPath(leftPath, color = tint)

        // Right triangle (mirrored, transparent tint with outline)
        val rightPath = Path().apply {
            moveTo(14f * scale, 5.5f * scale)
            lineTo(20.5f * scale, 12f * scale)
            lineTo(14f * scale, 18.5f * scale)
            close()
        }
        drawPath(rightPath, color = tint.copy(alpha = 0.40f))
        drawPath(
            rightPath,
            color = tint,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f * scale)
        )
    }
}

/**
 * Custom vector icon for Clear Layer:
 * Features a layer/canvas sheet with a chiseled eraser sweeping across it.
 */
@Composable
fun ClearLayerIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val scale = w / 24f
        fun pt(x: Float, y: Float) = Offset(x * scale, y * scale)

        // 1. Layer/canvas sheet outline (subtle dashed border)
        val rectPath = Path().apply {
            moveTo(4f * scale, 5f * scale)
            lineTo(20f * scale, 5f * scale)
            lineTo(20f * scale, 19f * scale)
            lineTo(4f * scale, 19f * scale)
            close()
        }
        drawPath(
            rectPath,
            color = tint.copy(alpha = 0.45f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.3f * scale,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f * scale, 2f * scale), 0f)
            )
        )

        // 2. Chiseled rubber eraser angled at 45 degrees
        val eraserPath = Path().apply {
            moveTo(8f * scale, 16f * scale)
            lineTo(14.5f * scale, 9.5f * scale)
            lineTo(18f * scale, 13f * scale)
            lineTo(11.5f * scale, 19.5f * scale)
            close()
        }
        drawPath(eraserPath, color = tint)

        // Eraser tip highlight band
        val tipBand = Path().apply {
            moveTo(8f * scale, 16f * scale)
            lineTo(10.5f * scale, 13.5f * scale)
            lineTo(13f * scale, 16f * scale)
            lineTo(10.5f * scale, 18.5f * scale)
            close()
        }
        drawPath(tipBand, color = Color(0xFF181A1F).copy(alpha = 0.35f))

        // 3. Dynamic sweep sparks / motion lines
        drawLine(
            color = tint.copy(alpha = 0.85f),
            start = pt(6f, 13f),
            end = pt(7.5f, 14.5f),
            strokeWidth = 1.4f * scale,
            cap = StrokeCap.Round
        )
    }
}


