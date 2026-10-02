package app.goodboy13.milton

import app.goodboy13.milton.core.brush.BrushDab
import app.goodboy13.milton.core.gl.CanvasEngine
import app.goodboy13.milton.core.gl.GlCommandQueue
import app.goodboy13.milton.core.gl.GlRenderCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasEngineTest {

    @Test
    fun testGlCommandQueue() {
        val queue = GlCommandQueue()
        assertTrue(queue.isEmpty())

        queue.enqueue(GlRenderCommand.Undo)
        queue.enqueue(GlRenderCommand.Redo)
        assertFalse(queue.isEmpty())

        val first = queue.poll()
        assertTrue(first is GlRenderCommand.Undo)

        val second = queue.poll()
        assertTrue(second is GlRenderCommand.Redo)

        assertNull(queue.poll())
        assertTrue(queue.isEmpty())
    }

    @Test
    fun testGlCommandQueueClear() {
        val queue = GlCommandQueue()
        queue.enqueue(GlRenderCommand.Undo)
        queue.enqueue(GlRenderCommand.TrimBudget)
        queue.clear()

        assertTrue(queue.isEmpty())
        assertNull(queue.poll())
    }

    @Test
    fun testCanvasEngineInitialState() {
        val engine = CanvasEngine()
        assertFalse(engine.undoRedoState.value.canUndo)
        assertFalse(engine.undoRedoState.value.canRedo)
        assertTrue(engine.commandQueue.isEmpty())
    }

    @Test
    fun testEnqueueCommands() {
        val engine = CanvasEngine()
        val dabs = listOf(
            BrushDab(x = 100f, y = 200f, radius = 5f, alpha = 1.0f, colorRgb = 0xFF0000, hardness = 0.8f, brushMode = 1, pressure = 0.5f)
        )
        engine.enqueueCommand(GlRenderCommand.SubmitDabs(dabs, isStrokeFinished = true))
        engine.enqueueCommand(GlRenderCommand.PickColor(50f, 50f))

        assertFalse(engine.commandQueue.isEmpty())
        val cmd1 = engine.commandQueue.poll()
        assertTrue(cmd1 is GlRenderCommand.SubmitDabs)
        assertEquals(1, (cmd1 as GlRenderCommand.SubmitDabs).dabs.size)
        assertTrue(cmd1.isStrokeFinished)

        val cmd2 = engine.commandQueue.poll()
        assertTrue(cmd2 is GlRenderCommand.PickColor)
        assertEquals(50f, (cmd2 as GlRenderCommand.PickColor).worldX, 0.001f)
    }

    @Test
    fun testCustomTaskExecutionCommand() {
        val queue = GlCommandQueue()
        var executed = false
        queue.enqueue(GlRenderCommand.ExecuteTask { executed = true })

        val cmd = queue.poll() as? GlRenderCommand.ExecuteTask
        assertNotNull(cmd)
        cmd?.action?.invoke()
        assertTrue(executed)
    }
}
