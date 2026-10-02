package com.antigrav.milton

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.antigrav.milton.core.brush.BrushType
import com.antigrav.milton.ui.MiltonCanvasView
import com.antigrav.milton.ui.MiltonTabletUi
import com.antigrav.milton.ui.QUICK_PALETTE_COLORS

class MainActivity : ComponentActivity() {

    private lateinit var canvasView: MiltonCanvasView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Clear window background so SurfaceView is directly visible
        window.setBackgroundDrawable(null)

        // Immersive sticky fullscreen for full drawing surface
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        canvasView = MiltonCanvasView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                isAutoHandwritingEnabled = false
            }
        }

        setContent {
            MaterialTheme {
                var brushType by remember { mutableStateOf(BrushType.PEN) }
                var brushSize by remember { mutableFloatStateOf(canvasView.brushSize) }
                var brushOpacity by remember { mutableFloatStateOf(canvasView.brushOpacity) }
                var brushColor by remember { mutableIntStateOf(QUICK_PALETTE_COLORS[0]) }
                var canUndo by remember { mutableStateOf(false) }
                var canRedo by remember { mutableStateOf(false) }
                var zoomLevel by remember { mutableFloatStateOf(1.0f) }
                var isZenMode by remember { mutableStateOf(false) }

                DisposableEffect(canvasView) {
                    val handler = Handler(Looper.getMainLooper())
                    canvasView.renderer.undoManager.onStateChangedListener = {
                        handler.post {
                            canUndo = canvasView.renderer.undoManager.canUndo
                            canRedo = canvasView.renderer.undoManager.canRedo
                        }
                    }
                    canvasView.onViewportChanged = { zoom ->
                        handler.post {
                            zoomLevel = zoom
                        }
                    }
                    onDispose {
                        canvasView.renderer.undoManager.onStateChangedListener = null
                        canvasView.onViewportChanged = null
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { canvasView },
                        modifier = Modifier.fillMaxSize()
                    )

                    MiltonTabletUi(
                        brushType = brushType,
                        onBrushTypeChange = { type ->
                            brushType = type
                            canvasView.brushType = type
                            brushSize = canvasView.brushSize
                            brushOpacity = canvasView.brushOpacity
                        },
                        brushSize = brushSize,
                        onBrushSizeChange = { size ->
                            brushSize = size
                            canvasView.brushSize = size
                        },
                        brushOpacity = brushOpacity,
                        onBrushOpacityChange = { opacity ->
                            brushOpacity = opacity
                            canvasView.brushOpacity = opacity
                        },
                        brushColorRgb = brushColor,
                        onBrushColorChange = { color ->
                            brushColor = color
                            canvasView.brushColorRgb = color
                        },
                        canUndo = canUndo,
                        onUndo = { canvasView.undo() },
                        canRedo = canRedo,
                        onRedo = { canvasView.redo() },
                        zoomLevel = zoomLevel,
                        onResetCanvas = { canvasView.resetCanvas() },
                        isZenMode = isZenMode,
                        onToggleZenMode = { isZenMode = it }
                    )
                }
            }
        }
    }
}
