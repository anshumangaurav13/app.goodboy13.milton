package app.goodboy13.milton.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class ShortcutItem(
    val keys: List<String>,
    val description: String
)

data class ShortcutCategory(
    val title: String,
    val color: Color,
    val items: List<ShortcutItem>
)

@Composable
fun KeyboardShortcutsDialog(
    onDismissRequest: () -> Unit
) {
    val categories = listOf(
        ShortcutCategory(
            title = "1. Spring-Loaded Modifiers (Held Key + Stylus Drag)",
            color = Color(0xFF81D4FA),
            items = listOf(
                ShortcutItem(listOf("Space", "+", "Stylus Drag"), "Pan / Hand tool"),
                ShortcutItem(listOf("Ctrl", "+", "Space", "+", "Stylus Drag"), "Scrubby Zoom (Drag Right/Up in, Left/Down out)"),
                ShortcutItem(listOf("R", "+", "Stylus Drag"), "Rotate canvas around center"),
                ShortcutItem(listOf("Ctrl", "+", "Alt", "+", "Stylus Drag"), "Interactive dynamic brush size adjustment"),
                ShortcutItem(listOf("Alt (Hold)", "+", "Stylus Drag / Tap"), "Temporary spring-loaded eyedropper")
            )
        ),
        ShortcutCategory(
            title = "2. Viewport Resets & Quick Toggles",
            color = Color(0xFFA5D6A7),
            items = listOf(
                ShortcutItem(listOf("M"), "Horizontal Canvas Flip toggle"),
                ShortcutItem(listOf("Ctrl", "+", "0"), "Reset Canvas View (Pan to center, 50% Zoom)"),
                ShortcutItem(listOf("Ctrl", "+", "1"), "Cycle between zoom levels 50% and 100%"),
                ShortcutItem(listOf("Shift", "+", "R"), "Reset Rotation to 0°")
            )
        ),
        ShortcutCategory(
            title = "3. Tool Switching & Cycling",
            color = Color(0xFFFFCC80),
            items = listOf(
                ShortcutItem(listOf("B"), "Paintbrush tool"),
                ShortcutItem(listOf("P"), "Cycle between Pen and Pencil"),
                ShortcutItem(listOf("E"), "Cycle between Eraser and previous brush"),
                ShortcutItem(listOf("I"), "Eyedropper tool toggle")
            )
        ),
        ShortcutCategory(
            title = "4. Brush Parameters & Color",
            color = Color(0xFFCE93D8),
            items = listOf(
                ShortcutItem(listOf("[", "]"), "Decrease / Increase brush size in stepped increments"),
                ShortcutItem(listOf("1", "–", "0"), "Set brush opacity in 10% steps (1 = 10%, 5 = 50%, 0 = 100%)"),
                ShortcutItem(listOf("X"), "Swap current color with previous recent color"),
                ShortcutItem(listOf("D"), "Reset brush color to default (Graphite)")
            )
        ),
        ShortcutCategory(
            title = "5. Document & History",
            color = Color(0xFF90CAF9),
            items = listOf(
                ShortcutItem(listOf("Ctrl", "+", "Z"), "Undo last stroke or action"),
                ShortcutItem(listOf("Ctrl", "+", "Shift", "+", "Z"), "Redo stroke or action (or Ctrl + Y)"),
                ShortcutItem(listOf("Ctrl", "+", "S"), "Manual Save project to library")
            )
        ),
        ShortcutCategory(
            title = "6. Layer Controls",
            color = Color(0xFFFFAB91),
            items = listOf(
                ShortcutItem(listOf("Ctrl", "+", "Shift", "+", "N"), "Add new layer above active"),
                ShortcutItem(listOf("Delete", "or", "Ctrl", "+", "Delete"), "Clear active layer content"),
                ShortcutItem(listOf("Page Up", "/", "Ctrl", "+", "]"), "Select layer above"),
                ShortcutItem(listOf("Page Down", "/", "Ctrl", "+", "["), "Select layer below")
            )
        ),
        ShortcutCategory(
            title = "7. Interface",
            color = Color(0xFF80CBC4),
            items = listOf(
                ShortcutItem(listOf("Tab"), "Toggle Zen / Fullscreen mode (hide/show rails and bars)")
            )
        )
    )

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 400.dp, max = 680.dp)
                .heightIn(max = 620.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xF216181D),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x35FFFFFF)),
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = null,
                            tint = Color(0xFF80CBC4),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Keyboard Shortcuts",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xAAFFFFFF),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                HorizontalDivider(
                    color = Color(0x22FFFFFF),
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                // Scrollable categories
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    categories.forEach { category ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = category.title,
                                color = category.color,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )

                            category.items.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = item.description,
                                        color = Color(0xDDFFFFFF),
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        item.keys.forEach { key ->
                                            if (key == "+" || key == "–" || key == "/" || key == "or") {
                                                Text(
                                                    text = key,
                                                    color = Color(0x88FFFFFF),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            } else {
                                                KeyBadge(text = key)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyBadge(text: String) {
    Box(
        modifier = Modifier
            .background(Color(0x33FFFFFF), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}
