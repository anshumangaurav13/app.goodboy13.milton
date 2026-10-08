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
    external fun decompressTile(compressedBytes: ByteArray): ByteArray

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
    external fun swapReadTile(swapDir: String, layerId: Long, tx: Int, ty: Int, outBytes: ByteArray): Boolean

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
    external fun createSubTilePatch(tileBuf: ByteArray, minX: Int, minY: Int, width: Int, height: Int): ByteArray

    @JvmStatic
    external fun applySubTilePatch(tileBuf: ByteArray, patchCompressed: ByteArray, minX: Int, minY: Int, width: Int, height: Int): Boolean

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
}
