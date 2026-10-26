package app.goodboy13.milton.core.gl

import android.graphics.Bitmap
import android.util.Log
import androidx.graphics.lowlatency.BufferInfo
import app.goodboy13.milton.core.brush.BrushDab
import app.goodboy13.milton.core.history.DeleteLayerCommand
import app.goodboy13.milton.core.history.TileDelta
import app.goodboy13.milton.core.history.UndoManager
import app.goodboy13.milton.core.layer.LayerManager
import app.goodboy13.milton.core.memory.NativeDabRingBuffer
import app.goodboy13.milton.core.tile.RasterTile
import app.goodboy13.milton.core.tile.TileCoord
import app.goodboy13.milton.core.viewport.Viewport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Domain engine coordinating canvas business logic, layer management, undo history,
 * stroke commitments, and render commands sent to [GlSceneRenderer].
 */
class CanvasEngine(
    val layerManager: LayerManager = LayerManager(),
    val viewport: Viewport = Viewport(),
    val undoManager: UndoManager = UndoManager(),
    val sceneRenderer: GlSceneRenderer = GlSceneRenderer()
) {
    companion object {
        private const val TAG = "CanvasEngine"
    }

    data class UndoRedoState(
        val canUndo: Boolean = false,
        val canRedo: Boolean = false
    )

    private val _undoRedoState = MutableStateFlow(UndoRedoState())
    val undoRedoState: StateFlow<UndoRedoState> = _undoRedoState.asStateFlow()

    init {
        undoManager.onStateChangedListener = {
            _undoRedoState.value = UndoRedoState(
                canUndo = undoManager.canUndo,
                canRedo = undoManager.canRedo
            )
        }
    }

    val commandQueue = GlCommandQueue()

    var onLayerThumbnailUpdated: ((layerId: Long, bitmap: Bitmap?) -> Unit)? = null
    var onColorPicked: ((Int) -> Unit)? = null
    var isThumbnailCaptureEnabled: Boolean = true
    var onRequestProgressiveRedraw: (() -> Unit)? = null
    var selectionManager: app.goodboy13.milton.core.selection.SelectionTransformManager? = null

    private val pendingStrokeFinished = AtomicBoolean(false)
    private val pendingTrimBudget = AtomicBoolean(false)
    private val dabsToCommit = mutableListOf<BrushDab>()
    private val ringDabsToCommit = mutableListOf<GlRenderCommand.SubmitRingDabs>()

    fun enqueueCommand(command: GlRenderCommand) {
        commandQueue.enqueue(command)
    }

    fun setGrainParams(type: Int, scale: Float, depth: Float) {
        sceneRenderer.setGrainParams(type, scale, depth)
    }

    fun clear() {
        commandQueue.clear()
        dabsToCommit.clear()
        ringDabsToCommit.clear()
        sceneRenderer.resetSmudgeCarrier()
    }

    /**
     * Executes front-buffer immediate overlay rendering for low-latency stroke dabs.
     */
    fun renderFrontBuffer(
        dabs: List<BrushDab>,
        bufferInfo: BufferInfo,
        transform: FloatArray
    ) {
        sceneRenderer.ensureGlInitialized()
        if (dabs.isEmpty()) return

        val activeLayer = layerManager.activeLayer
        if (!activeLayer.isVisible || activeLayer.opacity <= 0.001f) return

        sceneRenderer.renderFrontBufferDabs(
            dabs = dabs,
            activeLayerOpacity = activeLayer.opacity,
            bufferInfo = bufferInfo,
            transform = transform,
            viewport = viewport
        )
    }

    /**
     * Zero-allocation front-buffer immediate overlay rendering directly from [NativeDabRingBuffer].
     */
    fun renderFrontBuffer(
        ringBuffer: NativeDabRingBuffer,
        startHead: Long,
        count: Int,
        bufferInfo: BufferInfo,
        transform: FloatArray
    ) {
        sceneRenderer.ensureGlInitialized()
        if (count <= 0) return

        val activeLayer = layerManager.activeLayer
        if (!activeLayer.isVisible || activeLayer.opacity <= 0.001f) return

        sceneRenderer.renderFrontBufferRingDabs(
            ringBuffer = ringBuffer,
            startHead = startHead,
            count = count,
            activeLayerOpacity = activeLayer.opacity,
            bufferInfo = bufferInfo,
            transform = transform,
            viewport = viewport
        )
    }

    fun renderFrontBuffer(
        ringBuffer: NativeDabRingBuffer,
        startIndex: Int,
        count: Int,
        bufferInfo: BufferInfo,
        transform: FloatArray
    ) {
        renderFrontBuffer(ringBuffer, startIndex.toLong(), count, bufferInfo, transform)
    }

    /**
     * Drains command queue, stamps accumulated dabs, updates undo history,
     * clears and blits visible scene, captures thumbnails, and samples color.
     * Called strictly on the GL render thread during multi-buffered frames.
     */
    fun processFrame(
        bufferInfo: BufferInfo,
        transform: FloatArray,
        backgroundColorRgb: Int
    ) {
        sceneRenderer.ensureGlInitialized()

        var hadUndo = false
        var hadRedo = false
        dabsToCommit.clear()
        ringDabsToCommit.clear()
        var isStrokeDone = false
        var hasPickPoint = false
        var pickX = 0f
        var pickY = 0f

        // 1. Drain all pending commands in the queue
        while (!commandQueue.isEmpty()) {
            when (val cmd = commandQueue.poll() ?: break) {
                is GlRenderCommand.ExecuteTask -> {
                    try {
                        cmd.action()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error executing custom GL task", e)
                    }
                }
                is GlRenderCommand.Undo -> {
                    undoManager.undo(layerManager)
                    hadUndo = true
                }
                is GlRenderCommand.Redo -> {
                    undoManager.redo(layerManager)
                    hadRedo = true
                }
                is GlRenderCommand.SubmitDabs -> {
                    dabsToCommit.addAll(cmd.dabs)
                    if (cmd.isStrokeFinished) {
                        isStrokeDone = true
                    }
                }
                is GlRenderCommand.SubmitRingDabs -> {
                    ringDabsToCommit.add(cmd)
                    if (cmd.isStrokeFinished) {
                        isStrokeDone = true
                    }
                }
                is GlRenderCommand.PickColor -> {
                    hasPickPoint = true
                    pickX = cmd.worldX
                    pickY = cmd.worldY
                }
                is GlRenderCommand.TrimBudget -> {
                    pendingTrimBudget.set(true)
                }
                is GlRenderCommand.DeleteLayer -> {
                    executeDeleteLayerInternal(cmd.layerId)
                    cmd.onDone()
                }
                is GlRenderCommand.ClearLayer -> {
                    executeClearLayerInternal(cmd.layerId)
                    cmd.onDone()
                }
            }
        }

        if (pendingStrokeFinished.compareAndSet(true, false)) {
            isStrokeDone = true
        }

        var capturedLayerId = 0L
        if (hadUndo || hadRedo || isStrokeDone) {
            capturedLayerId = layerManager.activeLayer.id
        }

        // 2. Commit dabs into active layer tile FBOs
        if (ringDabsToCommit.isNotEmpty()) {
            val activeLayer = layerManager.activeLayer
            capturedLayerId = activeLayer.id

            for (cmd in ringDabsToCommit) {
                sceneRenderer.stampRingDabsIntoTiles(
                    targetTileMap = activeLayer.tileMap,
                    ringBuffer = cmd.ringBuffer,
                    startHead = cmd.startHead,
                    count = cmd.count,
                    onTilesResolved = { affectedTiles ->
                        undoManager.capturePreStrokeTiles(activeLayer.id, affectedTiles)
                    }
                )
            }

            if (isStrokeDone) {
                undoManager.commitStroke(activeLayer.id, activeLayer.tileMap)
                pendingTrimBudget.set(true)
                sceneRenderer.resetSmudgeCarrier()
            }
        } else if (dabsToCommit.isNotEmpty()) {
            val activeLayer = layerManager.activeLayer
            capturedLayerId = activeLayer.id

            sceneRenderer.stampDabsIntoTiles(
                targetTileMap = activeLayer.tileMap,
                dabs = dabsToCommit,
                onTilesResolved = { affectedTiles ->
                    undoManager.capturePreStrokeTiles(activeLayer.id, affectedTiles)
                }
            )

            if (isStrokeDone) {
                undoManager.commitStroke(activeLayer.id, activeLayer.tileMap)
                pendingTrimBudget.set(true)
            }
        } else if (isStrokeDone) {
            val activeLayer = layerManager.activeLayer
            undoManager.commitStroke(activeLayer.id, activeLayer.tileMap)
            pendingTrimBudget.set(true)
        }

        // 3. Clear backbuffer
        sceneRenderer.clearScreen(bufferInfo, backgroundColorRgb)

        // 4. Blit layers
        val visibleBounds = viewport.getVisibleWorldBounds()
        val selState = selectionManager?.state?.value
        sceneRenderer.blitLayers(
            layers = layerManager.layers,
            visibleBounds = visibleBounds,
            backgroundColorRgb = backgroundColorRgb,
            bufferInfo = bufferInfo,
            transform = transform,
            viewport = viewport,
            selectionState = selState,
            nativeDoc = layerManager.nativeDoc
        )

        // Progressive streaming: if visible tiles on disk are pending restoration,
        // schedule next frame to progressively stream them in without stalling gestures.
        if (layerManager.hasPendingRestores) {
            onRequestProgressiveRedraw?.invoke()
        }

        // 5. Capture thumbnail when stroke commits or on undo/redo
        if (capturedLayerId != 0L && isThumbnailCaptureEnabled && (isStrokeDone || hadUndo || hadRedo)) {
            val layer = layerManager.getLayer(capturedLayerId)
            if (layer != null) {
                val thumb = sceneRenderer.captureThumbnail(
                    layer = layer,
                    visibleBounds = visibleBounds,
                    restoreFboId = bufferInfo.frameBufferId,
                    restoreWidth = bufferInfo.width,
                    restoreHeight = bufferInfo.height
                )
                onLayerThumbnailUpdated?.invoke(capturedLayerId, thumb)
            }
        }

        // 6. Eyedropper pixel sampling
        if (hasPickPoint) {
            val color = sceneRenderer.pickColorAt(
                layers = layerManager.layers,
                worldX = pickX,
                worldY = pickY,
                backgroundColorRgb = backgroundColorRgb
            )
            onColorPicked?.invoke(color)
        }

        // 7. Enforce VRAM tile memory budget
        if (pendingTrimBudget.compareAndSet(true, false)) {
            layerManager.trimAllToBudget(visibleBounds)
        }
    }

    private fun executeDeleteLayerInternal(layerId: Long) {
        if (!layerManager.canDeleteLayer()) return
        val layer = layerManager.getLayer(layerId) ?: return
        val storageIndex = layerManager.layers.indexOf(layer)
        val name = layer.name
        val opacity = layer.opacity
        val isVisible = layer.isVisible
        val activeBefore = layerManager.activeLayerId
        val thumbnail = layer.thumbnailBitmap

        val tilesSnapshot = mutableMapOf<TileCoord, ByteArray>()
        val tiles = layer.tileMap.getAllTiles()
        for (tile in tiles) {
            if (tile.hasContent) {
                tile.ensureResident(layer.tileMap.cacheDir)
                val raw = tile.readPixels()
                tilesSnapshot[tile.coord] = UndoManager.compress(raw)
            }
        }

        layer.tileMap.releaseAll()
        layerManager.removeLayer(layerId)

        undoManager.pushDeleteLayer(
            layerId = layerId,
            name = name,
            opacity = opacity,
            isVisible = isVisible,
            storageIndex = storageIndex,
            activeLayerIdBefore = activeBefore,
            tilesSnapshot = tilesSnapshot
        )

        val clearedDeltas = tilesSnapshot.map { (coord, _) ->
            TileDelta(layerId, coord, null, null)
        }
        undoManager.onTilesCommittedListener?.invoke(clearedDeltas)
    }

    private fun executeClearLayerInternal(layerId: Long) {
        val layer = layerManager.getLayer(layerId) ?: return
        val tiles = layer.tileMap.getAllTiles().filter { it.hasContent }
        if (tiles.isEmpty()) return

        val deltas = mutableListOf<TileDelta>()
        for (tile in tiles) {
            tile.ensureResident(layer.tileMap.cacheDir)
            val beforeBytes = tile.readPixels()
            val beforeCompressed = UndoManager.compress(beforeBytes)
            tile.clear()
            tile.deleteDiskSwap(layer.tileMap.cacheDir)
            deltas.add(TileDelta(layerId, tile.coord, beforeCompressed, null))
        }

        layer.thumbnailBitmap = null
        layerManager.notifyThumbnailsChanged()

        undoManager.pushStroke(deltas)
        undoManager.onTilesCommittedListener?.invoke(deltas)
    }

    fun markStrokeFinished() {
        pendingStrokeFinished.set(true)
    }

    fun requestTrimBudget() {
        pendingTrimBudget.set(true)
    }
}
