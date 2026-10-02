package app.goodboy13.milton.core.gl

import app.goodboy13.milton.core.brush.BrushDab
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Sealed hierarchy of structured render commands dispatched across thread boundaries
 * from the UI thread / background workers to the OpenGL rendering engine.
 */
sealed interface GlRenderCommand {

    /**
     * Submit brush dabs to be stamped into tile FBOs.
     * [isStrokeFinished] indicates the stylus / touch contact lifted.
     */
    data class SubmitDabs(
        val dabs: List<BrushDab>,
        val isStrokeFinished: Boolean = false
    ) : GlRenderCommand

    /**
     * Request an undo step on the active layer stack.
     */
    object Undo : GlRenderCommand

    /**
     * Request a redo step on the active layer stack.
     */
    object Redo : GlRenderCommand

    /**
     * Request color sampling at canvas world coordinates.
     */
    data class PickColor(
        val worldX: Float,
        val worldY: Float
    ) : GlRenderCommand

    /**
     * Request VRAM tile memory budget trimming against currently visible camera bounds.
     */
    object TrimBudget : GlRenderCommand

    /**
     * Delete a layer from the stack with full tile snapshotting for history.
     */
    data class DeleteLayer(
        val layerId: Long,
        val onDone: () -> Unit = {}
    ) : GlRenderCommand

    /**
     * Clear all tile contents on a layer with full tile snapshotting for history.
     */
    data class ClearLayer(
        val layerId: Long,
        val onDone: () -> Unit = {}
    ) : GlRenderCommand

    /**
     * Arbitrary task to be executed on the GL thread.
     */
    data class ExecuteTask(
        val action: () -> Unit
    ) : GlRenderCommand
}

/**
 * Thread-safe multi-producer single-consumer queue for [GlRenderCommand]s.
 */
class GlCommandQueue {
    private val queue = ConcurrentLinkedQueue<GlRenderCommand>()

    fun enqueue(command: GlRenderCommand) {
        queue.add(command)
    }

    fun poll(): GlRenderCommand? = queue.poll()

    fun isEmpty(): Boolean = queue.isEmpty()

    fun clear() {
        queue.clear()
    }
}
