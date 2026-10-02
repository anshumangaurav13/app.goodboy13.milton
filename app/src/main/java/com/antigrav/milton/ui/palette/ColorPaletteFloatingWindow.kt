package com.antigrav.milton.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigrav.milton.ui.components.DraggableFloatingWindow
import com.antigrav.milton.ui.components.FloatingWindowState
import com.antigrav.milton.ui.components.HsvColorWheel

/**
 * Floating, draggable color palette containing:
 * - Full HSV Circular Color Wheel with inner Saturation/Value diamond.
 * - Live hex code preview.
 * - Eyedropper sampling tool.
 * - Dynamic Recent Colors row under the wheel.
 */
@Composable
fun ColorPaletteFloatingWindow(
    currentColorRgb: Int,
    onColorSelected: (Int) -> Unit,
    recentColors: List<Int>,
    isEyedropperActive: Boolean,
    onToggleEyedropper: () -> Unit,
    onClose: () -> Unit,
    containerWidth: Int,
    containerHeight: Int,
    state: FloatingWindowState
) {
    val initialHsv = remember(currentColorRgb) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(currentColorRgb, hsv)
        hsv
    }

    var hue by remember { mutableFloatStateOf(if (initialHsv[1] > 0.05f) initialHsv[0] else 210f) }
    var saturation by remember { mutableFloatStateOf(if (initialHsv[1] > 0.05f) initialHsv[1] else 0.85f) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }

    LaunchedEffect(currentColorRgb) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(currentColorRgb, hsv)
        if (hsv[1] > 0.04f && hsv[2] > 0.04f) {
            hue = hsv[0]
        }
        if (hsv[2] > 0.04f && hsv[1] > 0.02f) {
            saturation = hsv[1]
        }
        value = hsv[2]
    }

    val activeColorInt = remember(hue, saturation, value) {
        android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
    }

    DraggableFloatingWindow(
        title = "Color Palette",
        onClose = onClose,
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        state = state,
        modifier = Modifier.width(204.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Full HSV Color Wheel
            HsvColorWheel(
                hue = hue,
                onHueChange = { hue = it },
                saturation = saturation,
                onSaturationChange = { saturation = it },
                value = value,
                onValueChange = { value = it },
                onColorChanged = onColorSelected,
                wheelSize = 160.dp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Dynamic Recent Colors Row (Under Wheel)
            if (recentColors.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                ) {
                    recentColors.take(6).forEach { col ->
                        val isSelected = (col == activeColorInt)
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(col))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color.White else Color(0x33FFFFFF),
                                    shape = CircleShape
                                )
                                .clickable {
                                    val hsv = FloatArray(3)
                                    android.graphics.Color.colorToHSV(col, hsv)
                                    hue = hsv[0]
                                    saturation = hsv[1]
                                    value = hsv[2]
                                    onColorSelected(col)
                                }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Color(0x25FFFFFF), thickness = 1.dp)
            Spacer(modifier = Modifier.height(8.dp))

            // Preview & Eyedropper Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(activeColorInt))
                            .border(1.5.dp, Color.White, CircleShape)
                    )
                    Text(
                        text = String.format("#%06X", (0xFFFFFF and activeColorInt)),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xEEFFFFFF)
                    )
                }

                IconButton(
                    onClick = onToggleEyedropper,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (isEyedropperActive) Color(0xFF64B5F6).copy(alpha = 0.35f) else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (isEyedropperActive) Color(0xFF64B5F6) else Color(0x33FFFFFF),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Colorize,
                        contentDescription = "Eyedropper",
                        tint = if (isEyedropperActive) Color(0xFF64B5F6) else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
