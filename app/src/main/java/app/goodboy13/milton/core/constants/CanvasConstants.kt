package app.goodboy13.milton.core.constants

/**
 * Single Source of Truth for canvas dimensions, memory budgets, and tile paging parameters.
 */
object CanvasConstants {
    /** Dimension (width and height in pixels) of a single square canvas tile. */
    const val TILE_SIZE: Int = 512

    /** Number of bytes per RGBA_8888 pixel. */
    const val BYTES_PER_PIXEL: Int = 4

    /** Uncompressed byte size of a 512x512 RGBA tile buffer (1 MB = 1,048,576 bytes). */
    const val TILE_BYTE_SIZE: Int = TILE_SIZE * TILE_SIZE * BYTES_PER_PIXEL

    /** Default maximum resident uncompressed tiles kept in RAM/VRAM cache (256 MB budget). */
    const val DEFAULT_MAX_RESIDENT_TILES: Int = 512

    /** Number of direct off-heap native 1MB ByteBuffers to pre-allocate in pool. */
    const val DEFAULT_DIRECT_BUFFER_POOL_SIZE: Int = 6

    /** Number of recycled OpenGL textures and FBOs to cache in VRAM. */
    const val DEFAULT_GL_TEXTURE_POOL_SIZE: Int = DEFAULT_MAX_RESIDENT_TILES

    // --- Tile Paging & Eviction Hyperparameters ---
    /** Hysteresis factor: excess tiles needed beyond budget before eviction triggers (budget / 8). */
    const val TILE_EVICTION_SLACK_DIVISOR: Int = 8

    /** Minimum idle time in nanoseconds before a non-visible tile becomes eligible for eviction (3 seconds). */
    const val TILE_EVICTION_GRACE_PERIOD_NS: Long = 3_000_000_000L

    /** Maximum number of tiles restored from disk swap to VRAM per frame to prevent frame drops. */
    const val MAX_TILE_RESTORES_PER_FRAME: Int = 4

    /** Maximum number of tiles paged out to disk swap per frame. */
    const val MAX_TILE_EVICTIONS_PER_FRAME: Int = 4

    // --- Selection & Transform Bounds ---
    /** Minimum Euclidean distance between lasso stroke sampled points. */
    const val LASSO_MIN_SAMPLE_DISTANCE: Float = 4.0f

    /** Minimum selection bounding box width/height to avoid accidental zero-area selections. */
    const val MIN_SELECTION_DIMENSION: Float = 8.0f

    /** Maximum preview dimension for standard selection affine transformations. */
    const val MAX_PREVIEW_DIMENSION: Int = 1024

    /** Maximum preview dimension for non-linear liquify deformation mesh. */
    const val MAX_PREVIEW_DIMENSION_LIQUIFY: Int = 4096

    /** Vertex grid dimension for 2D liquify spring mesh (128x128 = 16,641 vertices). */
    const val LIQUIFY_GRID_SIZE: Int = 128
}
