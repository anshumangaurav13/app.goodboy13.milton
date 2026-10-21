package app.goodboy13.milton.core.telemetry

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Immutable snapshot of Milton engine rendering performance, VRAM consumption,
 * and draw-call telemetry for the Carmack HUD and Lagometer.
 */
data class TelemetrySnapshot(
    val fps: Float = 120.0f,
    val frameTimeMs: Float = 8.33f,
    val frontBufferTimeMs: Float = 1.2f,
    val multiBufferTimeMs: Float = 4.5f,
    val touchLatencyMs: Float = 12.0f,
    val residentTilesCount: Int = 0,
    val maxResidentTiles: Int = 256,
    val vramUsageMb: Float = 0f,
    val diskSwapTilesCount: Int = 0,
    val drawCalls: Int = 1,
    val dabsPerSecond: Int = 0,
    val activeLODLevel: Int = 0, // 0 = 512px Full Res, 1 = 1024px Overview
    val lagometerHistory: FloatArray = FloatArray(TelemetryManager.LAGOMETER_SAMPLE_COUNT) { 8.33f }
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TelemetrySnapshot) return false
        return fps == other.fps &&
            frameTimeMs == other.frameTimeMs &&
            frontBufferTimeMs == other.frontBufferTimeMs &&
            multiBufferTimeMs == other.multiBufferTimeMs &&
            touchLatencyMs == other.touchLatencyMs &&
            residentTilesCount == other.residentTilesCount &&
            drawCalls == other.drawCalls &&
            activeLODLevel == other.activeLODLevel
    }

    override fun hashCode(): Int {
        var result = fps.hashCode()
        result = 31 * result + frameTimeMs.hashCode()
        result = 31 * result + residentTilesCount
        return result
    }
}

/**
 * Low-overhead in-engine telemetry tracker inspired by John Carmack's Quake engine
 * lagometer and Leonardo's telemetry instrumentation.
 *
 * Collects zero-allocation performance metrics directly from the GL render thread and
 * publishes throttled reactive snapshots for the Compose UI HUD.
 */
object TelemetryManager {

    const val LAGOMETER_SAMPLE_COUNT = 72

    // Developer CVars (modifiable live from the HUD)
    @Volatile var cvarShowTileBorders: Boolean = false
    @Volatile var cvarForceOverviewLod: Boolean = false
    @Volatile var cvarUseTextureArrayBlit: Boolean = true

    // Internal timing metrics
    private val lagometerRing = FloatArray(LAGOMETER_SAMPLE_COUNT) { 8.33f }
    private var ringHeadIndex: Int = 0

    private val recentDeltaTimes = FloatArray(30) { 8.33f }
    private var deltaHeadIndex: Int = 0
    private var lastMultiBufferUptimeNs: Long = 0L

    @Volatile private var latestFrontBufferMs: Float = 0f
    @Volatile private var latestMultiBufferMs: Float = 0f
    @Volatile private var latestTouchLatencyMs: Float = 0f
    @Volatile private var latestDrawCalls: Int = 1
    @Volatile private var latestResidentCount: Int = 0
    @Volatile private var latestDiskCount: Int = 0
    @Volatile private var latestLodLevel: Int = 0

    private val dabCounter = AtomicInteger(0)
    private var lastDabRateCheckTimeMs: Long = SystemClock.uptimeMillis()
    @Volatile private var currentDabsPerSecond: Int = 0

    private var lastSnapshotPublishTimeMs: Long = 0L
    private const val SNAPSHOT_THROTTLE_MS = 66L // ~15 FPS UI updates

    private val _snapshot = MutableStateFlow(TelemetrySnapshot())
    val snapshot: StateFlow<TelemetrySnapshot> = _snapshot.asStateFlow()

    /**
     * Records a front-buffered low-latency stroke render pass duration.
     * Called strictly on the GL render thread.
     */
    fun recordFrontBufferFrame(durationMs: Float) {
        latestFrontBufferMs = durationMs
    }

    /**
     * Records estimated touch-to-photon latency from stylus event to render submission.
     */
    fun recordTouchLatency(latencyMs: Float) {
        latestTouchLatencyMs = latencyMs.coerceIn(1f, 150f)
    }

    /**
     * Records brush dabs stamped for rate estimation.
     */
    fun recordDabs(count: Int) {
        dabCounter.addAndGet(count)
    }

    /**
     * Records a multi-buffered frame render completion and updates the rolling lagometer.
     * Called strictly on the GL render thread.
     */
    fun recordMultiBufferFrame(
        durationMs: Float,
        drawCalls: Int,
        residentCount: Int,
        diskCount: Int,
        lodLevel: Int
    ) {
        val nowNs = System.nanoTime()
        val nowMs = SystemClock.uptimeMillis()

        latestMultiBufferMs = durationMs
        latestDrawCalls = drawCalls
        latestResidentCount = residentCount
        latestDiskCount = diskCount
        latestLodLevel = lodLevel

        // Update lagometer ring
        lagometerRing[ringHeadIndex] = durationMs.coerceIn(0.5f, 50f)
        ringHeadIndex = (ringHeadIndex + 1) % LAGOMETER_SAMPLE_COUNT

        // Inter-frame delta and FPS
        if (lastMultiBufferUptimeNs > 0L) {
            val deltaMs = (nowNs - lastMultiBufferUptimeNs) / 1_000_000f
            if (deltaMs in 1f..200f) {
                recentDeltaTimes[deltaHeadIndex] = deltaMs
                deltaHeadIndex = (deltaHeadIndex + 1) % recentDeltaTimes.size
            }
        }
        lastMultiBufferUptimeNs = nowNs

        // Update dabs/sec rate once per second
        val dabElapsed = nowMs - lastDabRateCheckTimeMs
        if (dabElapsed >= 1000L) {
            val dabs = dabCounter.getAndSet(0)
            currentDabsPerSecond = (dabs * 1000L / dabElapsed).toInt()
            lastDabRateCheckTimeMs = nowMs
        }

        // Throttle publishing to Compose StateFlow to keep main thread light
        if (nowMs - lastSnapshotPublishTimeMs >= SNAPSHOT_THROTTLE_MS) {
            lastSnapshotPublishTimeMs = nowMs
            publishSnapshot()
        }
    }

    private fun publishSnapshot() {
        var sumDelta = 0f
        for (d in recentDeltaTimes) {
            sumDelta += d
        }
        val avgDelta = (sumDelta / recentDeltaTimes.size).coerceAtLeast(1f)
        val calculatedFps = (1000f / avgDelta).coerceIn(1f, 240f)

        // Copy lagometer history in chronological order
        val historyCopy = FloatArray(LAGOMETER_SAMPLE_COUNT)
        for (i in 0 until LAGOMETER_SAMPLE_COUNT) {
            val srcIdx = (ringHeadIndex + i) % LAGOMETER_SAMPLE_COUNT
            historyCopy[i] = lagometerRing[srcIdx]
        }

        val vramMb = latestResidentCount * 1.0f // Each 512x512 RGBA8 tile is 1.0 MB

        _snapshot.value = TelemetrySnapshot(
            fps = calculatedFps,
            frameTimeMs = latestMultiBufferMs,
            frontBufferTimeMs = latestFrontBufferMs,
            multiBufferTimeMs = latestMultiBufferMs,
            touchLatencyMs = latestTouchLatencyMs,
            residentTilesCount = latestResidentCount,
            maxResidentTiles = 256,
            vramUsageMb = vramMb,
            diskSwapTilesCount = latestDiskCount,
            drawCalls = latestDrawCalls,
            dabsPerSecond = currentDabsPerSecond,
            activeLODLevel = latestLodLevel,
            lagometerHistory = historyCopy
        )
    }
}
