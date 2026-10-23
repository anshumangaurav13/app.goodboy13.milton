package app.goodboy13.milton.core.native

import android.util.Log

/**
 * Native JNI bridge to libmilton_core.so for accelerated sub-engines:
 * #1: Multi-threaded ZSTD/Deflate tile compression and fast .milton ZIP packaging
 * #2: Off-heap tile virtual memory swap store
 * #3: SIMD vertical pixel flip and streaming PNG canvas export
 */
object MiltonNative {
    private const val TAG = "MiltonNative"

    var isLoaded: Boolean = false
        private set

    @JvmStatic
    fun isNativeAvailable(): Boolean = isLoaded

    init {
        try {
            System.loadLibrary("milton_core")
            isLoaded = true
            Log.i(TAG, "libmilton_core.so loaded successfully. Native acceleration active.")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libmilton_core.so not found or failed to load. Falling back to pure Kotlin engines.", e)
            isLoaded = false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading libmilton_core.so", e)
            isLoaded = false
        }
    }

    // ========================================================================
    // #1 COMPRESSION & ARCHIVE CODEC
    // ========================================================================

    @JvmStatic
    external fun compressTile(rawBytes: ByteArray): ByteArray

    @JvmStatic
    external fun compressTileDirect(directBuf: java.nio.ByteBuffer): ByteArray

    @JvmStatic
    external fun decompressTile(compressedBytes: ByteArray): ByteArray

    @JvmStatic
    external fun decompressTileDirect(compressedBytes: ByteArray, directBuf: java.nio.ByteBuffer): Boolean

    @JvmStatic
    external fun exportArchive(manifestJson: String, tilesDir: String, destinationZip: String): Boolean

    @JvmStatic
    external fun importArchive(sourceZip: String, destinationTilesDir: String): String?

    // ========================================================================
    // #2 TILE SWAP STORE
    // ========================================================================

    @JvmStatic
    external fun swapWriteTile(swapDir: String, layerId: Long, tx: Int, ty: Int, rawBytes: ByteArray): Boolean

    @JvmStatic
    external fun swapWriteTileDirect(swapDir: String, layerId: Long, tx: Int, ty: Int, directBuf: java.nio.ByteBuffer): Boolean

    @JvmStatic
    external fun swapReadTile(swapDir: String, layerId: Long, tx: Int, ty: Int, outBytes: ByteArray): Boolean

    @JvmStatic
    external fun swapReadTileDirect(swapDir: String, layerId: Long, tx: Int, ty: Int, directBuf: java.nio.ByteBuffer): Boolean

    @JvmStatic
    external fun swapDeleteTile(swapDir: String, layerId: Long, tx: Int, ty: Int): Boolean

    @JvmStatic
    external fun swapClearAll(swapDir: String): Boolean

    // ========================================================================
    // #3 HIGH-RESOLUTION EXPORT & PIXEL TRANSFORMS
    // ========================================================================

    @JvmStatic
    external fun flipPixelsVertically(src: ByteArray, width: Int, height: Int, dst: ByteArray): Boolean

    @JvmStatic
    external fun exportCanvasPng(width: Int, height: Int, rgbaBytes: ByteArray, outputPath: String): Boolean

    // ========================================================================
    // SUB-TILE DIRTY-RECT UNDO/REDO
    // ========================================================================

    @JvmStatic
    external fun computeDirtyRect(oldBytes: ByteArray, newBytes: ByteArray): IntArray?

    @JvmStatic
    external fun computeDirtyRectDirect(oldDirectBuf: java.nio.ByteBuffer, newDirectBuf: java.nio.ByteBuffer): IntArray?

    @JvmStatic
    external fun createSubTilePatch(tileBuf: ByteArray, minX: Int, minY: Int, width: Int, height: Int): ByteArray

    @JvmStatic
    external fun createSubTilePatchDirect(tileBufDirect: java.nio.ByteBuffer, minX: Int, minY: Int, width: Int, height: Int): ByteArray

    @JvmStatic
    external fun applySubTilePatch(tileBuf: ByteArray, patchCompressed: ByteArray, minX: Int, minY: Int, width: Int, height: Int): Boolean

    @JvmStatic
    external fun applySubTilePatchDirect(tileBufDirect: java.nio.ByteBuffer, patchCompressed: ByteArray, minX: Int, minY: Int, width: Int, height: Int): Boolean

    // ========================================================================
    // #4 LASSO SELECTION & FREE TRANSFORM
    // ========================================================================

    data class TransformedPatch(
        val width: Int,
        val height: Int,
        val offsetX: Float,
        val offsetY: Float,
        val rgbaBytes: ByteArray
    )

    @JvmStatic
    external fun rasterizePolygonMask(pointsX: FloatArray, pointsY: FloatArray, tileLeft: Float, tileTop: Float): ByteArray?

    @JvmStatic
    external fun transformPatchPixels(
        srcRgba: ByteArray,
        srcW: Int,
        srcH: Int,
        scaleX: Float,
        scaleY: Float,
        rotationRad: Float,
        pivotX: Float,
        pivotY: Float,
        flipH: Boolean,
        flipV: Boolean
    ): ByteArray?

    fun transformPatch(
        srcRgba: ByteArray,
        srcW: Int,
        srcH: Int,
        scaleX: Float,
        scaleY: Float,
        rotationRad: Float,
        pivotX: Float,
        pivotY: Float,
        flipH: Boolean,
        flipV: Boolean
    ): TransformedPatch? {
        if (!isLoaded) return null
        val packed = transformPatchPixels(
            srcRgba, srcW, srcH, scaleX, scaleY, rotationRad, pivotX, pivotY, flipH, flipV
        ) ?: return null
        if (packed.size < 16) return null
        val buf = java.nio.ByteBuffer.wrap(packed).order(java.nio.ByteOrder.BIG_ENDIAN)
        val dstW = buf.int
        val dstH = buf.int
        val offX = buf.float
        val offY = buf.float
        val expectedLen = dstW * dstH * 4
        if (packed.size < 16 + expectedLen) return null
        val dstRgba = packed.copyOfRange(16, 16 + expectedLen)
        return TransformedPatch(dstW, dstH, offX, offY, dstRgba)
    }

    @JvmStatic
    external fun extractAndClearTileSelection(
        tileRgba: ByteArray,
        tileOriginX: Int,
        tileOriginY: Int,
        tileMask: ByteArray,
        patchRgba: ByteArray,
        patchW: Int,
        patchH: Int,
        patchOriginX: Int,
        patchOriginY: Int
    ): Boolean

    @JvmStatic
    external fun blitPatchToTile(
        tileRgba: ByteArray,
        tileOriginX: Int,
        tileOriginY: Int,
        patchRgba: ByteArray,
        patchW: Int,
        patchH: Int,
        patchOriginX: Int,
        patchOriginY: Int
    ): Boolean

    // Native Sparse Selection Session API ("Rust Magic")
    @JvmStatic
    external fun selectionSessionBegin(
        pointsX: FloatArray,
        pointsY: FloatArray,
        boundsLeft: Float = 0f,
        boundsTop: Float = 0f,
        boundsRight: Float = 0f,
        boundsBottom: Float = 0f
    ): Boolean

    @JvmStatic
    external fun selectionSessionCutTile(
        layerId: Long,
        tx: Int,
        ty: Int,
        tileRgba: ByteArray,
        forLiquify: Boolean = false
    ): Boolean

    @JvmStatic
    external fun selectionSessionGetPreview(layerId: Long, maxDim: Int): ByteArray?

    @JvmStatic
    external fun selectionCalculateDestBounds(
        srcLeft: Float,
        srcTop: Float,
        srcRight: Float,
        srcBottom: Float,
        scaleX: Float,
        scaleY: Float,
        rotationRad: Float,
        transX: Float,
        transY: Float,
        pivotX: Float,
        pivotY: Float,
        flipH: Boolean,
        flipV: Boolean,
        outBounds: FloatArray
    ): Boolean

    @JvmStatic
    external fun selectionSessionGetAffectedDestTiles(
        layerId: Long,
        scaleX: Float,
        scaleY: Float,
        rotationRad: Float,
        transX: Float,
        transY: Float,
        pivotX: Float,
        pivotY: Float,
        flipH: Boolean,
        flipV: Boolean
    ): IntArray?

    @JvmStatic
    external fun selectionSessionBlitToTile(
        layerId: Long,
        tx: Int,
        ty: Int,
        tileRgba: ByteArray,
        scaleX: Float,
        scaleY: Float,
        rotationRad: Float,
        transX: Float,
        transY: Float,
        pivotX: Float,
        pivotY: Float,
        flipH: Boolean,
        flipV: Boolean
    ): Boolean

    @JvmStatic
    external fun selectionSessionLiquifyBlitPatch(
        tx: Int,
        ty: Int,
        tileRgba: ByteArray,
        patchLeft: Float,
        patchTop: Float,
        patchW: Int,
        patchH: Int,
        patchRgba: ByteArray
    ): Boolean

    @JvmStatic
    external fun selectionSessionEnd()

    // ========================================================================
    // #5 HEADLESS DOCUMENT CORE & FLAT RENDER PACKET GENERATOR
    // ========================================================================

    @JvmStatic
    external fun createDocument(): Long

    @JvmStatic
    external fun destroyDocument(docPtr: Long)

    @JvmStatic
    external fun documentAddLayer(docPtr: Long, layerId: Long, name: String, opacity: Float, visible: Boolean)

    @JvmStatic
    external fun documentRemoveLayer(docPtr: Long, layerId: Long): Boolean

    @JvmStatic
    external fun documentSetLayerOpacity(docPtr: Long, layerId: Long, opacity: Float)

    @JvmStatic
    external fun documentSetLayerVisible(docPtr: Long, layerId: Long, visible: Boolean)

    @JvmStatic
    external fun documentReorderLayers(docPtr: Long, layerIds: LongArray)

    @JvmStatic
    external fun documentClearLayer(docPtr: Long, layerId: Long)

    @JvmStatic
    external fun documentSetTile(docPtr: Long, layerId: Long, tx: Int, ty: Int, sliceIndex: Int, hasContent: Boolean)

    @JvmStatic
    external fun documentSetOverviewTile(docPtr: Long, layerId: Long, otx: Int, oty: Int, sliceIndex: Int, hasContent: Boolean)

    @JvmStatic
    external fun documentGenerateRenderPacket(
        docPtr: Long,
        viewLeft: Float,
        viewTop: Float,
        viewRight: Float,
        viewBottom: Float,
        zoom: Float,
        forceOverview: Boolean,
        excludeLayerId: Long = 0L,
        excludeLeft: Float = 0f,
        excludeTop: Float = 0f,
        excludeRight: Float = 0f,
        excludeBottom: Float = 0f,
        outDirectBuf: java.nio.ByteBuffer
    ): Int

    @JvmStatic
    external fun documentGetBounds(docPtr: Long, outBounds: FloatArray): Boolean

    // ========================================================================
    // #6 BRUSH ENGINE & STROKE INTERPOLATION
    // ========================================================================

    @JvmStatic
    external fun createBrushEngine(
        size: Float,
        opacity: Float,
        colorRgb: Int,
        spacing: Float,
        hardness: Float,
        tilt: Float,
        minRadius: Float,
        isEraser: Boolean,
        brushMode: Int,
        stabilizer: Float,
        sizeCurve: FloatArray,
        opacityCurve: FloatArray,
        tiltCurve: FloatArray
    ): Long

    @JvmStatic
    external fun destroyBrushEngine(enginePtr: Long)

    @JvmStatic
    external fun brushSetProperties(
        enginePtr: Long,
        size: Float,
        opacity: Float,
        colorRgb: Int,
        spacing: Float,
        hardness: Float,
        tilt: Float,
        minRadius: Float,
        isEraser: Boolean,
        brushMode: Int,
        stabilizer: Float,
        sizeCurve: FloatArray,
        opacityCurve: FloatArray,
        tiltCurve: FloatArray
    )

    @JvmStatic
    external fun brushStartStroke(
        enginePtr: Long,
        worldX: Float,
        worldY: Float,
        pressure: Float,
        eventTimeMillis: Long,
        tilt: Float,
        outDirectBuf: java.nio.ByteBuffer,
        headIndex: Long,
        capacity: Int
    ): Int

    @JvmStatic
    external fun brushAddPoint(
        enginePtr: Long,
        worldX: Float,
        worldY: Float,
        pressure: Float,
        eventTimeMillis: Long,
        tilt: Float,
        outDirectBuf: java.nio.ByteBuffer,
        headIndex: Long,
        capacity: Int
    ): Int

    @JvmStatic
    external fun brushEndStroke(
        enginePtr: Long,
        outDirectBuf: java.nio.ByteBuffer,
        headIndex: Long,
        capacity: Int
    ): Int

    @JvmStatic
    external fun brushBinRingDabs(
        ringBuf: java.nio.ByteBuffer,
        capacity: Int,
        startHead: Long,
        count: Int,
        tileSize: Float,
        outHeaders: IntArray,
        outIndices: IntArray
    ): Int

    // ========================================================================
    // #7 HISTORY & UNDO/REDO
    // ========================================================================

    @JvmStatic
    external fun createHistoryManager(maxHistorySize: Int): Long

    @JvmStatic
    external fun destroyHistoryManager(historyPtr: Long)

    @JvmStatic
    external fun historyCanUndo(historyPtr: Long): Boolean

    @JvmStatic
    external fun historyCanRedo(historyPtr: Long): Boolean

    @JvmStatic
    external fun historyUndoCount(historyPtr: Long): Int

    @JvmStatic
    external fun historyRedoCount(historyPtr: Long): Int

    @JvmStatic
    external fun historyClear(historyPtr: Long)

    @JvmStatic
    external fun historyTotalMemoryBytes(historyPtr: Long): Long

    @JvmStatic
    external fun historyPushTileDelta(
        historyPtr: Long,
        isStartOfCommand: Boolean,
        layerId: Long,
        tx: Int,
        ty: Int,
        minX: Int,
        minY: Int,
        width: Int,
        height: Int,
        beforePatch: ByteArray?,
        afterPatch: ByteArray?
    )

    @JvmStatic
    external fun historyGetUndoDeltasCount(historyPtr: Long): Int

    @JvmStatic
    external fun historyGetUndoDeltaMetadata(
        historyPtr: Long,
        index: Int,
        outMeta: IntArray,
        outLayerId: LongArray
    ): Boolean

    @JvmStatic
    external fun historyApplyUndoTileDirect(
        historyPtr: Long,
        index: Int,
        tileDirectBuf: java.nio.ByteBuffer
    ): Boolean

    @JvmStatic
    external fun historyCommitUndo(historyPtr: Long): Boolean

    @JvmStatic
    external fun historyGetRedoDeltasCount(historyPtr: Long): Int

    @JvmStatic
    external fun historyGetRedoDeltaMetadata(
        historyPtr: Long,
        index: Int,
        outMeta: IntArray,
        outLayerId: LongArray
    ): Boolean

    @JvmStatic
    external fun historyApplyRedoTileDirect(
        historyPtr: Long,
        index: Int,
        tileDirectBuf: java.nio.ByteBuffer
    ): Boolean

    @JvmStatic
    external fun historyCommitRedo(historyPtr: Long): Boolean

    @JvmStatic
    external fun historyGetUndoCommandType(historyPtr: Long): Int

    @JvmStatic
    external fun historyGetRedoCommandType(historyPtr: Long): Int

    @JvmStatic
    external fun historyPushAddLayer(
        historyPtr: Long,
        layerId: Long,
        name: String,
        opacity: Float,
        isVisible: Boolean,
        storageIndex: Int,
        activeLayerIdBefore: Long
    )

    @JvmStatic
    external fun historyPushDeleteLayer(
        historyPtr: Long,
        layerId: Long,
        name: String,
        opacity: Float,
        isVisible: Boolean,
        storageIndex: Int,
        activeLayerIdBefore: Long
    )

    @JvmStatic
    external fun historyAppendDeleteLayerTile(
        historyPtr: Long,
        tx: Int,
        ty: Int,
        compressedTile: ByteArray
    )

    @JvmStatic
    external fun historyGetUndoDeleteLayerTilesCount(historyPtr: Long): Int

    @JvmStatic
    external fun historyGetUndoDeleteLayerTile(
        historyPtr: Long,
        index: Int,
        outCoords: IntArray
    ): ByteArray?

    @JvmStatic
    external fun historyGetUndoLayerMeta(
        historyPtr: Long,
        outIds: LongArray,
        outFloats: FloatArray,
        outInts: IntArray
    ): String?

    @JvmStatic
    external fun historyGetRedoLayerMeta(
        historyPtr: Long,
        outIds: LongArray,
        outFloats: FloatArray,
        outInts: IntArray
    ): String?

    // ========================================================================
    // #11 VIEWPORT & CAMERA TRANSFORMATION ENGINE
    // ========================================================================

    @JvmStatic
    external fun viewportCreate(screenWidth: Float, screenHeight: Float): Long

    @JvmStatic
    external fun viewportDestroy(vpPtr: Long)

    @JvmStatic
    external fun viewportUpdateScreenSize(vpPtr: Long, width: Float, height: Float)

    @JvmStatic
    external fun viewportSetPan(vpPtr: Long, panX: Float, panY: Float)

    @JvmStatic
    external fun viewportGetPan(vpPtr: Long, outPan: FloatArray)

    @JvmStatic
    external fun viewportSetZoom(vpPtr: Long, zoom: Float)

    @JvmStatic
    external fun viewportGetZoom(vpPtr: Long): Float

    @JvmStatic
    external fun viewportSetRotation(vpPtr: Long, degrees: Float)

    @JvmStatic
    external fun viewportGetRotation(vpPtr: Long): Float

    @JvmStatic
    external fun viewportSetFlipped(vpPtr: Long, flipped: Boolean)

    @JvmStatic
    external fun viewportIsFlipped(vpPtr: Long): Boolean

    @JvmStatic
    external fun viewportReset(vpPtr: Long)

    @JvmStatic
    external fun viewportScreenToWorld(vpPtr: Long, screenX: Float, screenY: Float, outCoords: FloatArray)

    @JvmStatic
    external fun viewportWorldToScreen(vpPtr: Long, worldX: Float, worldY: Float, outCoords: FloatArray)

    @JvmStatic
    external fun viewportGetVisibleBounds(vpPtr: Long, outBounds: FloatArray)

    @JvmStatic
    external fun viewportApplyGesture(
        vpPtr: Long,
        prevFocalX: Float,
        prevFocalY: Float,
        curFocalX: Float,
        curFocalY: Float,
        zoomFactor: Float,
        angleDelta: Float
    )

    @JvmStatic
    external fun viewportStepFling(
        dt: Float,
        velocities: FloatArray,
        outDeltas: FloatArray
    ): Boolean

    @JvmStatic
    external fun viewportComputeFinalMvp(
        vpPtr: Long,
        bufferW: Float,
        bufferH: Float,
        transform: FloatArray,
        outMvp: FloatArray
    )

    @JvmStatic
    external fun viewportComputeTwoFingerGesture(
        p0x: Float,
        p0y: Float,
        p1x: Float,
        p1y: Float,
        prevSpan: Float,
        prevAngle: Float,
        isZoomLocked: Boolean,
        isRotationLocked: Boolean,
        outResults: FloatArray
    )

    // ========================================================================
    // #12 SPATIAL TILE MAP & LRU BUDGET ENGINE
    // ========================================================================

    @JvmStatic
    external fun spatialMapCreate(maxResidentTiles: Int): Long

    @JvmStatic
    external fun spatialMapDestroy(mapPtr: Long)

    @JvmStatic
    external fun spatialMapSetTile(
        mapPtr: Long,
        tx: Int,
        ty: Int,
        sliceIndex: Int,
        hasContent: Boolean,
        isResident: Boolean,
        isOnDisk: Boolean,
        isDirty: Boolean,
        timestampNs: Long
    )

    @JvmStatic
    external fun spatialMapUpdateTileState(
        mapPtr: Long,
        tx: Int,
        ty: Int,
        sliceIndex: Int,
        hasContent: Boolean,
        isResident: Boolean
    )

    @JvmStatic
    external fun spatialMapRemoveTile(mapPtr: Long, tx: Int, ty: Int): Boolean

    @JvmStatic
    external fun spatialMapTouchTile(mapPtr: Long, tx: Int, ty: Int, timestampNs: Long)

    @JvmStatic
    external fun spatialMapQueryVisible(
        mapPtr: Long,
        minTx: Int,
        minTy: Int,
        maxTx: Int,
        maxTy: Int,
        timestampNs: Long,
        outKeys: LongArray
    ): Int

    @JvmStatic
    external fun spatialMapFindEvictionCandidates(
        mapPtr: Long,
        visibleKeys: LongArray,
        nowNs: Long,
        maxEvictions: Int,
        outEvictKeys: LongArray
    ): Int

    @JvmStatic
    external fun spatialMapFindEvictionCandidatesWithCount(
        mapPtr: Long,
        visibleKeys: LongArray,
        visibleCount: Int,
        nowNs: Long,
        maxEvictions: Int,
        outEvictKeys: LongArray
    ): Int

    @JvmStatic
    external fun spatialMapFindEvictionCandidatesForBounds(
        mapPtr: Long,
        minTx: Int,
        minTy: Int,
        maxTx: Int,
        maxTy: Int,
        nowNs: Long,
        maxEvictions: Int,
        outEvictKeys: LongArray
    ): Int

    @JvmStatic
    external fun spatialMapGetBounds(mapPtr: Long, outBounds: FloatArray): Boolean

    @JvmStatic
    external fun spatialMapClear(mapPtr: Long)

    // ========================================================================
    // Liquify Deformation Mesh Engine
    // ========================================================================

    @JvmStatic
    external fun liquifyMeshCreate(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        polyX: FloatArray,
        polyY: FloatArray,
        gridCols: Int,
        gridRows: Int
    ): Long

    @JvmStatic
    external fun liquifyMeshDestroy(meshPtr: Long)

    @JvmStatic
    external fun liquifyMeshReset(meshPtr: Long)

    @JvmStatic
    external fun liquifyMeshApplyStroke(
        meshPtr: Long,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radius: Float,
        strength: Float,
        mode: Int
    )

    @JvmStatic
    external fun liquifyMeshGetVertices(
        meshPtr: Long,
        outWorldX: FloatArray,
        outWorldY: FloatArray,
        outMaskWeights: FloatArray?
    )

    @JvmStatic
    external fun liquifyMeshComputeLocalWarpedVerts(
        meshPtr: Long,
        outVerts: FloatArray
    )

    @JvmStatic
    external fun selectionSimplifyAndSmooth(
        pointsX: FloatArray,
        pointsY: FloatArray,
        epsilon: Float,
        smoothIterations: Int
    ): FloatArray

    // ========================================================================
    // #13 KUBELKA-MUNK SPECTRAL PIGMENT MIXING
    // ========================================================================

    @JvmStatic
    external fun mixSpectralColor(srcRgba: Int, dstRgba: Int, weight: Float): Int

    @JvmStatic
    external fun mixSpectralPixelBufferDirect(
        srcDirectBuf: java.nio.ByteBuffer,
        dstDirectBuf: java.nio.ByteBuffer,
        weight: Float
    ): Boolean
}
