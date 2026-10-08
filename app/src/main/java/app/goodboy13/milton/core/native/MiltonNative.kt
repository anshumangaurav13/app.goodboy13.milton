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
}
