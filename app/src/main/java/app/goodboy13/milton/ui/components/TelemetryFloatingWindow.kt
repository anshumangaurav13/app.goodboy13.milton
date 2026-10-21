package app.goodboy13.milton.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.goodboy13.milton.core.telemetry.TelemetryManager
import app.goodboy13.milton.core.telemetry.TelemetrySnapshot
import java.util.Locale

/**
 * Carmack Game Engine Telemetry HUD & Lagometer overlay for Milton.
 *
 * Inspired by John Carmack's Quake engine `cl_showfps` lagometer and Leonardo's telemetry.
 * Displays live touch-to-photon latency, 120 FPS frame timing graph, VRAM resident tile
 * pool consumption, single-draw-call presentation status, and interactive engine CVars.
 */
@Composable
fun TelemetryFloatingWindow(
    onClose: () -> Unit,
    containerWidth: Int,
    containerHeight: Int,
    state: FloatingWindowState = rememberFloatingWindowState(initialX = 80f, initialY = 80f),
    onFlushVram: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val snapshot by TelemetryManager.snapshot.collectAsState()

    var showTileBorders by remember { mutableStateOf(TelemetryManager.cvarShowTileBorders) }
    var forceOverviewLod by remember { mutableStateOf(TelemetryManager.cvarForceOverviewLod) }
    var useArrayBlit by remember { mutableStateOf(TelemetryManager.cvarUseTextureArrayBlit) }

    DraggableFloatingWindow(
        title = "CARMACK HUD",
        onClose = onClose,
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        state = state,
        modifier = modifier.width(280.dp),
        headerActions = {
            // Live FPS Badge
            val fpsColor = when {
                snapshot.fps >= 105f -> Color(0xFF00E676)
                snapshot.fps >= 55f -> Color(0xFFFFD600)
                else -> Color(0xFFFF5252)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(fpsColor.copy(alpha = 0.15f))
                    .border(1.dp, fpsColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f FPS", snapshot.fps),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = fpsColor
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Quake / Leonardo Lagometer Frame Graph
            CarmackLagometer(history = snapshot.lagometerHistory)

            // 2. Timing Breakdown
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF1B1D22),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x25FFFFFF))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem("Frame Time", String.format(Locale.US, "%.2f ms", snapshot.frameTimeMs))
                        MetricItem("Touch Latency", String.format(Locale.US, "%.0f ms", snapshot.touchLatencyMs))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem("Front Buffer", String.format(Locale.US, "%.2f ms", snapshot.frontBufferTimeMs))
                        MetricItem("Dabs / sec", "${snapshot.dabsPerSecond}")
                    }
                }
            }

            // 3. VRAM Resident Tiles & Draw Calls
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF1B1D22),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x25FFFFFF))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem(
                            label = "Resident VRAM",
                            value = "${snapshot.residentTilesCount}/${snapshot.maxResidentTiles} (${snapshot.vramUsageMb.toInt()} MB)"
                        )
                        MetricItem(
                            label = "Disk Swap",
                            value = "${snapshot.diskSwapTilesCount} tiles"
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem(
                            label = "Draw Calls",
                            value = "${snapshot.drawCalls} (Layer Array)"
                        )
                        MetricItem(
                            label = "Active LOD",
                            value = if (snapshot.activeLODLevel == 0) "L0 (512px Full)" else "L1 (1024px Over)"
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0x25FFFFFF), thickness = 1.dp)

            // 4. Interactive CVars & Engine Debug Toggles
            Text(
                text = "ENGINE CVARS & DEBUG",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0x88FFFFFF),
                letterSpacing = 0.5.sp
            )

            // CVar: r_show_tile_borders
            CVarSwitchRow(
                label = "Tile Wireframe Grid",
                description = "r_show_tile_borders",
                checked = showTileBorders,
                onCheckedChange = {
                    showTileBorders = it
                    TelemetryManager.cvarShowTileBorders = it
                }
            )

            // CVar: r_lod_force_overview
            CVarSwitchRow(
                label = "Force Overview LOD",
                description = "r_lod_force_overview",
                checked = forceOverviewLod,
                onCheckedChange = {
                    forceOverviewLod = it
                    TelemetryManager.cvarForceOverviewLod = it
                }
            )

            // CVar: r_use_texture_array_blit
            CVarSwitchRow(
                label = "2D Array Single Draw Call",
                description = "r_use_texture_array_blit",
                checked = useArrayBlit,
                onCheckedChange = {
                    useArrayBlit = it
                    TelemetryManager.cvarUseTextureArrayBlit = it
                }
            )

            // Action: Flush Resident Cache
            if (onFlushVram != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onFlushVram() },
                    color = Color(0x25FF7043),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x40FF7043))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CleaningServices,
                            contentDescription = null,
                            tint = Color(0xFFFFAB91),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Prune VRAM & Flush to Disk",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFFAB91)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 9.sp,
            color = Color(0x88FFFFFF),
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CVarSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                text = description,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0x66FFFFFF)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.7f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF00E5FF),
                checkedTrackColor = Color(0x4400E5FF),
                uncheckedThumbColor = Color(0x88FFFFFF),
                uncheckedTrackColor = Color(0x22FFFFFF)
            )
        )
    }
}

/**
 * Authentic Quake 3 / Leonardo Lagometer visualizer.
 * Renders vertical bars corresponding to recent frame durations with horizontal
 * reference thresholds for 120 FPS (8.33 ms) and 60 FPS (16.66 ms).
 */
@Composable
private fun CarmackLagometer(
    history: FloatArray,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0B0C0E))
            .border(1.dp, Color(0x35FFFFFF), RoundedCornerShape(6.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(52.dp)) {
            val w = size.width
            val h = size.height
            val count = history.size
            if (count == 0) return@Canvas

            val maxScaleMs = 25.0f // Clamped ceiling for graph
            val y120 = h - (8.33f / maxScaleMs) * h
            val y60 = h - (16.66f / maxScaleMs) * h

            // 1. Draw 120 FPS Target Line (Cyan)
            drawLine(
                color = Color(0x5500E5FF),
                start = Offset(0f, y120),
                end = Offset(w, y120),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            )

            // 2. Draw 60 FPS Target Line (Amber)
            drawLine(
                color = Color(0x55FFD600),
                start = Offset(0f, y60),
                end = Offset(w, y60),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            )

            // 3. Draw vertical frame time bars
            val barWidth = w / count
            for (i in 0 until count) {
                val ms = history[i]
                val barHeight = ((ms / maxScaleMs) * h).coerceIn(2f, h)
                val barColor = when {
                    ms <= 8.5f -> Color(0xFF00E676)  // Green (< 8.3ms = 120 FPS)
                    ms <= 16.66f -> Color(0xFFFFD600) // Amber (< 16.6ms = 60 FPS)
                    else -> Color(0xFFFF5252)         // Red (Stutter / jank)
                }

                drawRect(
                    color = barColor,
                    topLeft = Offset(i * barWidth, h - barHeight),
                    size = Size(barWidth - 0.5f, barHeight)
                )
            }
        }

        // Overlay text tags
        Text(
            text = "120Hz (8.3ms)",
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0x8800E5FF),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 4.dp, top = 2.dp)
        )
        Text(
            text = "60Hz (16.6ms)",
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0x88FFD600),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp, top = 2.dp)
        )
    }
}
