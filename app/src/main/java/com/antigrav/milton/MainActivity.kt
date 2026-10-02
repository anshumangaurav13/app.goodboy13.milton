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
import com.antigrav.milton.ui.MiltonCanvasView
import com.antigrav.milton.ui.MiltonToolbar
import com.antigrav.milton.ui.PALETTE_COLORS

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
                var isEraser by remember { mutableStateOf(false) }
                var brushSize by remember { mutableFloatStateOf(12f) }
                var brushOpacity by remember { mutableFloatStateOf(1.0f) }
                var brushColor by remember { mutableIntStateOf(PALETTE_COLORS[0]) }
                var canUndo by remember { mutableStateOf(false) }
                var canRedo by remember { mutableStateOf(false) }

                DisposableEffect(canvasView) {
                    val handler = Handler(Looper.getMainLooper())
                    canvasView.renderer.undoManager.onStateChangedListener = {
                        handler.post {
                            canUndo = canvasView.renderer.undoManager.canUndo
                            canRedo = canvasView.renderer.undoManager.canRedo
                        }
                    }
                    onDispose {
                        canvasView.renderer.undoManager.onStateChangedListener = null
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { canvasView },
                        modifier = Modifier.fillMaxSize()
                    )

                    MiltonToolbar(
                        isEraser = isEraser,
                        onToggleEraser = { eraser ->
                            isEraser = eraser
                            canvasView.isEraserMode = eraser
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
                        onResetCanvas = { canvasView.resetCanvas() },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    )
                }
            }
        }
    }
}
