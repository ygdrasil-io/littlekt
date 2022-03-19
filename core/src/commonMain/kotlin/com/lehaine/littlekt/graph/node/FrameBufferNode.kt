package com.lehaine.littlekt.graph.node

import com.lehaine.littlekt.graph.SceneGraph
import com.lehaine.littlekt.graph.node.annotation.SceneGraphDslMarker
import com.lehaine.littlekt.graph.node.ui.Control
import com.lehaine.littlekt.graphics.*
import com.lehaine.littlekt.graphics.gl.ClearBufferMask
import com.lehaine.littlekt.graphics.gl.TexMagFilter
import com.lehaine.littlekt.graphics.gl.TexMinFilter
import com.lehaine.littlekt.math.Mat4
import com.lehaine.littlekt.math.MutableVec2f
import com.lehaine.littlekt.util.SingleSignal
import com.lehaine.littlekt.util.signal1v
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
inline fun Node.frameBuffer(
    callback: @SceneGraphDslMarker FrameBufferNode.() -> Unit = {},
): FrameBufferNode {
    contract { callsInPlace(callback, InvocationKind.EXACTLY_ONCE) }
    return FrameBufferNode().also(callback).addTo(this)
}

@OptIn(ExperimentalContracts::class)
inline fun SceneGraph<*>.frameBuffer(
    callback: @SceneGraphDslMarker FrameBufferNode.() -> Unit = {},
): FrameBufferNode {
    contract { callsInPlace(callback, InvocationKind.EXACTLY_ONCE) }
    return root.frameBuffer(callback)
}

/**
 * A [Node] that renders it children to a [FrameBuffer] of a specified size.
 * @author Colton Daily
 * @date 3/14/2022
 */
class FrameBufferNode : Node() {

    var width: Int = 0
    var height: Int = 0
    val fboTexture: Texture? get() = fbo?.colorBufferTexture
    val onFboChanged: SingleSignal<Texture> = signal1v()

    private val fboCamera = OrthographicCamera(width, height)
    private var lastWidth = width
    private var lastHeight = height

    private var fbo: FrameBuffer? = null

    override fun onAddedToScene() {
        super.onAddedToScene()
        if (width != 0 && height != 0) {
            resizeFbo(width, height)
        }
    }

    private fun checkForResize(newWidth: Int, newHeight: Int) {
        if (width != newWidth || height != newHeight) {
            resizeFbo(newWidth, newHeight)
        }
    }

    /**
     * Resizes the internal [FrameBuffer] to the new width and height. This will dispose
     * of the previous [FrameBuffer] and the texture.
     * @param newWidth the new width of the [FrameBuffer]
     * @param newHeight the new width of the [FrameBuffer]
     */
    fun resizeFbo(newWidth: Int, newHeight: Int) {
        if (newWidth == 0 || newHeight == 0) return
        scene?.let { scene ->
            lastWidth = newWidth
            lastHeight = newHeight
            width = newWidth
            height = newHeight
            fbo?.dispose()
            fbo = FrameBuffer(
                width,
                height,
                minFilter = TexMinFilter.NEAREST,
                magFilter = TexMagFilter.NEAREST
            ).also { it.prepare(scene.context) }
            fboCamera.viewport.set(0, 0, width, height)
            fboCamera.virtualWidth = width
            fboCamera.virtualHeight = height
            fboCamera.position.set(fboCamera.virtualWidth * 0.5f, fboCamera.virtualHeight * 0.5f, 0f)
            fboTexture?.let { onFboChanged.emit(it) }

        }
    }

    private var prevProjection: Mat4 = Mat4()

    /**
     * Begins drawing to the [FrameBuffer].
     */
    fun begin(batch: Batch) {
        checkForResize(lastWidth, lastHeight)
        val fbo = fbo ?: return
        val context = scene?.context ?: return
        val gl = context.gl
        if (width == 0 || height == 0) return
        batch.end()
        prevProjection = batch.projectionMatrix

        fboCamera.update()
        fbo.begin()
        gl.clear(ClearBufferMask.COLOR_BUFFER_BIT)
        batch.begin(fboCamera.viewProjection)
    }

    override fun propagateInternalRender(
        batch: Batch,
        camera: Camera,
        renderCallback: ((Node, Batch, Camera) -> Unit)?,
    ) {
        fbo ?: return
        if (width == 0 || height == 0) return
        begin(batch)
        nodes.forEach { it.propagateInternalRender(batch, fboCamera, renderCallback) }
        end(batch)
    }

    /**
     * Finishes drawing to the [FrameBuffer].
     */
    fun end(batch: Batch) {
        val fbo = fbo ?: return
        if (width == 0 || height == 0) return
        batch.end()
        fbo.end()
        batch.begin(prevProjection)
    }

    override fun onDestroy() {
        super.onDestroy()
        onFboChanged.clear()
    }

    private val temp = MutableVec2f()
    override fun propagateHit(hx: Float, hy: Float): Control? {
        val canvas = canvas ?: return null
        val scene = scene ?: return null
        canvas.canvasToScreenCoordinates(temp.set(hx, hy))
        fboCamera.screenToWorld(temp, scene.context, temp)
        nodes.forEachReversed {
            val target = it.propagateHit(temp.x, temp.y)
            if (target != null) {
                return target
            }
        }
        return null
    }
}