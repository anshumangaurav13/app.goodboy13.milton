package com.antigrav.milton.ui.topbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.antigrav.milton.core.storage.SavedProjectSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class LeftBarSubmenu {
    NONE,
    EXPORT,
    SAVED_PROJECTS
}

/**
 * Top left header bar: Docked to top-left edge, flush with corner.
 * Contains:
 * 1. Compact sandwich menu with flyout submenus: New Project, Save Project, Saved Projects >, Export >, Import .milton, and RAM/Disk footer.
 * 2. In-place titlebar editable by holding (long press), but NOT merely tapping.
 */
@Composable
fun TabletTopLeftBar(
    documentTitle: String,
    onTitleChange: (String) -> Unit,
    onNewProjectClick: () -> Unit,
    onManualSave: () -> Unit,
    savedProjects: List<SavedProjectSummary>,
    totalSavedProjectsSize: String,
    currentProjectDiskSize: String,
    appRamUsageMb: Int,
    onLoadProject: (String) -> Unit,
    onDeleteSavedProject: (String) -> Unit,
    onMenuOpened: () -> Unit,
    onExportMilton: () -> Unit,
    onExportPng: () -> Unit,
    onExportJpg: () -> Unit,
    onImportMilton: () -> Unit
) {
    val barShape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 0.dp, bottomEnd = 14.dp)
    var isMenuExpanded by remember { mutableStateOf(false) }
    var activeSubmenu by remember { mutableStateOf(LeftBarSubmenu.NONE) }
    var isEditingTitle by remember { mutableStateOf(false) }
    var tempTitle by remember(documentTitle) { mutableStateOf(documentTitle) }
    val focusRequester = remember { FocusRequester() }

    Surface(
        modifier = Modifier
            .shadow(elevation = 10.dp, shape = barShape)
            .clip(barShape)
            .drawBehind {
                val sw = 1.dp.toPx()
                val r = 14.dp.toPx()
                val w = size.width
                val h = size.height
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w - sw / 2f, 0f)
                    lineTo(w - sw / 2f, h - r)
                    arcTo(
                        rect = androidx.compose.ui.geometry.Rect(
                            w - 2f * r + sw / 2f,
                            h - 2f * r + sw / 2f,
                            w - sw / 2f,
                            h - sw / 2f
                        ),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(0f, h - sw / 2f)
                }
                drawPath(
                    path = path,
                    color = Color(0x35FFFFFF),
                    style = Stroke(width = sw)
                )
            },
        shape = barShape,
        color = Color(0xF0181A1F),
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Sandwich menu button
            Box {
                IconButton(
                    onClick = {
                        isMenuExpanded = !isMenuExpanded
                        if (isMenuExpanded) {
                            activeSubmenu = LeftBarSubmenu.NONE
                            onMenuOpened()
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Menu",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (isMenuExpanded) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(x = 0, y = 110),
                        onDismissRequest = {
                            isMenuExpanded = false
                            activeSubmenu = LeftBarSubmenu.NONE
                        }
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            // Primary Compact Menu
                            Surface(
                                modifier = Modifier
                                    .width(185.dp)
                                    .shadow(elevation = 16.dp, shape = RoundedCornerShape(12.dp)),
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xF7181A1F),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x35FFFFFF))
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    // 1. New Project
                                    CompactMenuItem(
                                        icon = Icons.Default.Add,
                                        iconTint = Color(0xFF81C784),
                                        text = "New Project",
                                        onClick = {
                                            isMenuExpanded = false
                                            activeSubmenu = LeftBarSubmenu.NONE
                                            onNewProjectClick()
                                        }
                                    )

                                    // 2. Save Project
                                    CompactMenuItem(
                                        icon = Icons.Default.Save,
                                        iconTint = Color(0xFF64B5F6),
                                        text = "Save Project",
                                        onClick = {
                                            isMenuExpanded = false
                                            activeSubmenu = LeftBarSubmenu.NONE
                                            onManualSave()
                                        }
                                    )

                                    // 3. Saved Projects flyout
                                    CompactMenuItem(
                                        icon = Icons.Default.Folder,
                                        iconTint = Color(0xFFFFCA28),
                                        text = "Saved Projects",
                                        hasSubmenu = true,
                                        isSubmenuOpen = activeSubmenu == LeftBarSubmenu.SAVED_PROJECTS,
                                        onClick = {
                                            activeSubmenu = if (activeSubmenu == LeftBarSubmenu.SAVED_PROJECTS) {
                                                LeftBarSubmenu.NONE
                                            } else {
                                                onMenuOpened()
                                                LeftBarSubmenu.SAVED_PROJECTS
                                            }
                                        }
                                    )

                                    HorizontalDivider(
                                        color = Color(0x22FFFFFF),
                                        thickness = 1.dp,
                                        modifier = Modifier.padding(vertical = 3.dp, horizontal = 6.dp)
                                    )

                                    // 4. Export flyout
                                    CompactMenuItem(
                                        icon = Icons.Default.Share,
                                        iconTint = Color(0xFFFFB74D),
                                        text = "Export",
                                        hasSubmenu = true,
                                        isSubmenuOpen = activeSubmenu == LeftBarSubmenu.EXPORT,
                                        onClick = {
                                            activeSubmenu = if (activeSubmenu == LeftBarSubmenu.EXPORT) {
                                                LeftBarSubmenu.NONE
                                            } else {
                                                LeftBarSubmenu.EXPORT
                                            }
                                        }
                                    )

                                    // 5. Import .milton
                                    CompactMenuItem(
                                        icon = Icons.Default.FolderOpen,
                                        iconTint = Color(0xFF4FC3F7),
                                        text = "Import .milton",
                                        onClick = {
                                            isMenuExpanded = false
                                            activeSubmenu = LeftBarSubmenu.NONE
                                            onImportMilton()
                                        }
                                    )

                                    HorizontalDivider(
                                        color = Color(0x22FFFFFF),
                                        thickness = 1.dp,
                                        modifier = Modifier.padding(vertical = 3.dp, horizontal = 6.dp)
                                    )

                                    // 6. Metrics Footer (RAM & Disk)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "RAM: ${appRamUsageMb} MB",
                                            fontSize = 10.sp,
                                            color = Color(0x99FFFFFF),
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "Disk: $currentProjectDiskSize",
                                            fontSize = 10.sp,
                                            color = Color(0x99FFFFFF),
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            // Secondary Flyout Submenus
                            when (activeSubmenu) {
                                LeftBarSubmenu.EXPORT -> {
                                    Surface(
                                        modifier = Modifier
                                            .width(160.dp)
                                            .shadow(elevation = 16.dp, shape = RoundedCornerShape(12.dp)),
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xF7181A1F),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x35FFFFFF))
                                    ) {
                                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                            CompactMenuItem(
                                                icon = Icons.Default.Image,
                                                iconTint = Color(0xFF81C784),
                                                text = "PNG Image",
                                                onClick = {
                                                    isMenuExpanded = false
                                                    activeSubmenu = LeftBarSubmenu.NONE
                                                    onExportPng()
                                                }
                                            )
                                            CompactMenuItem(
                                                icon = Icons.Default.Photo,
                                                iconTint = Color(0xFFFFB74D),
                                                text = "JPG Image",
                                                onClick = {
                                                    isMenuExpanded = false
                                                    activeSubmenu = LeftBarSubmenu.NONE
                                                    onExportJpg()
                                                }
                                            )
                                            CompactMenuItem(
                                                icon = Icons.Default.UploadFile,
                                                iconTint = Color(0xFFBA68C8),
                                                text = ".milton Project",
                                                onClick = {
                                                    isMenuExpanded = false
                                                    activeSubmenu = LeftBarSubmenu.NONE
                                                    onExportMilton()
                                                }
                                            )
                                        }
                                    }
                                }
                                LeftBarSubmenu.SAVED_PROJECTS -> {
                                    Surface(
                                        modifier = Modifier
                                            .width(260.dp)
                                            .shadow(elevation = 16.dp, shape = RoundedCornerShape(12.dp)),
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xF7181A1F),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x35FFFFFF))
                                    ) {
                                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                            // Header with Total Size on Disk
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "Saved Projects",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0x2864B5F6),
                                                    border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0x5564B5F6))
                                                ) {
                                                    Text(
                                                        text = "Total: $totalSavedProjectsSize",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFF90CAF9),
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }

                                            HorizontalDivider(
                                                color = Color(0x22FFFFFF),
                                                thickness = 1.dp,
                                                modifier = Modifier.padding(vertical = 4.dp, horizontal = 6.dp)
                                            )

                                            if (savedProjects.isEmpty()) {
                                                Text(
                                                    text = "No saved projects found",
                                                    fontSize = 11.sp,
                                                    color = Color(0x77FFFFFF),
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                                )
                                            } else {
                                                Column(
                                                    modifier = Modifier
                                                        .heightIn(max = 260.dp)
                                                        .verticalScroll(rememberScrollState())
                                                ) {
                                                    savedProjects.forEach { proj ->
                                                        SavedProjectItemRow(
                                                            project = proj,
                                                            onLoad = {
                                                                isMenuExpanded = false
                                                                activeSubmenu = LeftBarSubmenu.NONE
                                                                onLoadProject(proj.id)
                                                            },
                                                            onDelete = {
                                                                onDeleteSavedProject(proj.id)
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                LeftBarSubmenu.NONE -> {}
                            }
                        }
                    }
                }
            }

            // Divider between sandwich menu and title
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(Color(0x35FFFFFF))
            )

            // Titlebar: in-place editable by HOLDING (long press), NOT merely tapping
            if (!isEditingTitle) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .pointerInput(documentTitle) {
                            detectTapGestures(
                                onLongPress = {
                                    tempTitle = documentTitle
                                    isEditingTitle = true
                                }
                            )
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = documentTitle,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp)
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    BasicTextField(
                        value = tempTitle,
                        onValueChange = { tempTitle = it },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        cursorBrush = SolidColor(Color(0xFF64B5F6)),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            val trimmed = tempTitle.trim()
                            if (trimmed.isNotEmpty()) {
                                onTitleChange(trimmed)
                            }
                            isEditingTitle = false
                        }),
                        modifier = Modifier
                            .widthIn(min = 100.dp, max = 220.dp)
                            .background(Color(0x33FFFFFF), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .focusRequester(focusRequester)
                    )

                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }

                    // Confirm edit button
                    IconButton(
                        onClick = {
                            val trimmed = tempTitle.trim()
                            if (trimmed.isNotEmpty()) {
                                onTitleChange(trimmed)
                            }
                            isEditingTitle = false
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Confirm Title",
                            tint = Color(0xFF81C784),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Cancel edit button
                    IconButton(
                        onClick = {
                            tempTitle = documentTitle
                            isEditingTitle = false
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel Edit",
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompactMenuItem(
    icon: ImageVector,
    iconTint: Color,
    text: String,
    hasSubmenu: Boolean = false,
    isSubmenuOpen: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = if (isSubmenuOpen) Color(0x2564B5F6) else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                color = Color.White,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (hasSubmenu) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = if (isSubmenuOpen) Color(0xFF64B5F6) else Color(0x77FFFFFF),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
internal fun SavedProjectItemRow(
    project: SavedProjectSummary,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(project.modifiedAt) {
        val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        sdf.format(Date(project.modifiedAt))
    }
    val sizeStr = remember(project.sizeOnDiskBytes) {
        when {
            project.sizeOnDiskBytes < 1024 -> "${project.sizeOnDiskBytes} B"
            project.sizeOnDiskBytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", project.sizeOnDiskBytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", project.sizeOnDiskBytes / (1024.0 * 1024.0))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLoad() }
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = project.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = dateStr,
                    fontSize = 10.sp,
                    color = Color(0x88FFFFFF)
                )
                Text(
                    text = "•",
                    fontSize = 10.sp,
                    color = Color(0x55FFFFFF)
                )
                Text(
                    text = sizeStr,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFFB74D)
                )
            }
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "Delete",
                tint = Color(0x99FF5252),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
