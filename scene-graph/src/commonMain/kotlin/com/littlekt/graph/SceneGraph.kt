package com.littlekt.graph

import com.littlekt.Context
import com.littlekt.Releasable
import com.littlekt.graph.node.*
import com.littlekt.graph.node.annotation.SceneGraphDslMarker
import com.littlekt.graph.node.render.Material
import com.littlekt.graph.node.resource.InputEvent
import com.littlekt.graph.node.ui.Control
import com.littlekt.graphics.BlendStates
import com.littlekt.graphics.OrthographicCamera
import com.littlekt.graphics.g2d.Batch
import com.littlekt.graphics.g2d.SpriteBatch
import com.littlekt.graphics.g2d.TextureSlice
import com.littlekt.graphics.g2d.shape.ShapeRenderer
import com.littlekt.input.*
import com.littlekt.math.MutableVec2f
import com.littlekt.resources.Textures
import com.littlekt.util.datastructure.Pool
import com.littlekt.util.datastructure.fastForEach
import com.littlekt.util.milliseconds
import com.littlekt.util.seconds
import com.littlekt.util.viewport.ScreenViewport
import com.littlekt.util.viewport.Viewport
import io.ygdrasil.wgpu.CommandEncoder
import io.ygdrasil.wgpu.RenderPassDescriptor
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Create a new scene graph with a [callback] with the [SceneGraph] in context.
 *
 * @param context the current context
 * @param viewport the viewport that the camera of the scene graph will own
 * @param batch an option sprite batch. If omitted, the scene graph will create and manage its own.
 * @param callback the callback that is invoked with a [SceneGraph] context in order to initialize
 *   any values and create nodes
 * @return the newly created [SceneGraph]
 */
@OptIn(ExperimentalContracts::class)
inline fun sceneGraph(
    context: Context,
    viewport: Viewport = ScreenViewport(context.graphics.width, context.graphics.height),
    batch: Batch? = null,
    controller: InputMapController<String>? = null,
    whitePixel: TextureSlice = Textures.white,
    callback: @SceneGraphDslMarker SceneGraph<String>.() -> Unit = {},
): SceneGraph<String> {
    contract { callsInPlace(callback, InvocationKind.EXACTLY_ONCE) }
    val signals =
        SceneGraph.UiInputSignals(
            "ui_accept",
            "ui_select",
            "ui_cancel",
            "ui_focus_next",
            "ui_focus_prev",
            "ui_left",
            "ui_right",
            "ui_up",
            "ui_down",
            "ui_home",
            "ui_end"
        )
    return SceneGraph(
            context,
            viewport,
            batch,
            signals,
            controller ?: createDefaultSceneGraphController(context.input, signals),
            whitePixel
        )
        .also(callback)
}

/**
 * Create a new scene graph with a [callback] with the [SceneGraph] in context.
 *
 * @param context the current context
 * @param viewport the viewport that the camera of the scene graph will own
 * @param batch an option sprite batch. If omitted, the scene graph will create and manage its own.
 * @param callback the callback that is invoked with a [SceneGraph] context in order to initialize
 *   any values and create nodes
 * @return the newly created [SceneGraph]
 */
@OptIn(ExperimentalContracts::class)
inline fun <InputSignal> sceneGraph(
    context: Context,
    viewport: Viewport = ScreenViewport(context.graphics.width, context.graphics.height),
    batch: Batch? = null,
    uiInputSignals: SceneGraph.UiInputSignals<InputSignal> = SceneGraph.UiInputSignals(),
    controller: InputMapController<InputSignal> = InputMapController(context.input),
    whitePixel: TextureSlice = Textures.white,
    callback: @SceneGraphDslMarker SceneGraph<InputSignal>.() -> Unit = {},
): SceneGraph<InputSignal> {
    contract { callsInPlace(callback, InvocationKind.EXACTLY_ONCE) }
    return SceneGraph(context, viewport, batch, uiInputSignals, controller, whitePixel)
        .also(callback)
}

/**
 * Adds the default UI bindings to the given [InputMapController] for the mapped [uiInputSignals].
 *
 * @param uiInputSignals the mapped [SceneGraph.UiInputSignals] to [InputSignal]
 */
fun <InputSignal> InputMapController<InputSignal>.addDefaultUiInput(
    uiInputSignals: SceneGraph.UiInputSignals<InputSignal>
) {
    uiInputSignals.uiAccept?.let {
        addBinding(it, keys = listOf(Key.SPACE, Key.ENTER), buttons = listOf(GameButton.XBOX_A))
    }
    uiInputSignals.uiSelect?.let {
        addBinding(it, keys = listOf(Key.SPACE), buttons = listOf(GameButton.XBOX_Y))
    }
    uiInputSignals.uiCancel?.let {
        addBinding(it, keys = listOf(Key.ESCAPE), buttons = listOf(GameButton.XBOX_B))
    }
    uiInputSignals.uiFocusNext?.let { addBinding(it, keys = listOf(Key.TAB)) }
    uiInputSignals.uiFocusPrev?.let {
        addBinding(
            it,
            keys = listOf(Key.TAB),
            keyModifiers = listOf(InputMapController.KeyModifier.SHIFT)
        )
    }
    uiInputSignals.uiUp?.let {
        addBinding(it, keys = listOf(Key.ARROW_UP), buttons = listOf(GameButton.UP))
    }
    uiInputSignals.uiDown?.let {
        addBinding(it, keys = listOf(Key.ARROW_DOWN), buttons = listOf(GameButton.DOWN))
    }
    uiInputSignals.uiLeft?.let {
        addBinding(it, keys = listOf(Key.ARROW_LEFT), buttons = listOf(GameButton.LEFT))
    }
    uiInputSignals.uiRight?.let {
        addBinding(it, keys = listOf(Key.ARROW_RIGHT), buttons = listOf(GameButton.RIGHT))
    }
    uiInputSignals.uiHome?.let { addBinding(it, keys = listOf(Key.HOME)) }
    uiInputSignals.uiEnd?.let { addBinding(it, keys = listOf(Key.END)) }
}

/**
 * @param input the context [Input]
 * @param uiInputSignals the mapped [SceneGraph.UiInputSignals] to [InputSignal]
 * @return a newly created [InputMapController] using the specified [InputSignal] type and adds the
 *   default UI input bindings via [addDefaultUiInput].
 */
fun <InputSignal> createDefaultSceneGraphController(
    input: Input,
    uiInputSignals: SceneGraph.UiInputSignals<InputSignal>,
): InputMapController<InputSignal> =
    InputMapController<InputSignal>(input).also { it.addDefaultUiInput(uiInputSignals) }

/**
 * A class for creating a scene graph of nodes.
 *
 * @param context the current context
 * @param viewport the viewport that the camera of the scene graph will own
 * @param batch an option sprite batch. If omitted, the scene graph will create and manage its own.
 * @param uiInputSignals the input signals mapped to the UI input of type [InputType].
 * @param controller the input map controller for the scene graph
 * @param whitePixel a white 1x1 pixel [TextureSlice] that is used for rendering with
 *   [ShapeRenderer].
 * @author Colton Daily
 * @date 1/1/2022
 */
open class SceneGraph<InputType>(
    val context: Context,
    viewport: Viewport = ScreenViewport(context.graphics.width, context.graphics.height),
    batch: Batch? = null,
    val uiInputSignals: UiInputSignals<InputType> = UiInputSignals(),
    val controller: InputMapController<InputType> =
        createDefaultSceneGraphController(context.input, uiInputSignals),
    whitePixel: TextureSlice = Textures.white,
) : InputMapProcessor<InputType>, Releasable {

    private var ownsBatch = true
    /**
     * The [Batch] being used by the scene graph. This batch may be managed by the scene graph or
     * could be optional passed in via the constructor.
     */
    val batch: Batch =
        batch?.also { ownsBatch = false }
            ?: SpriteBatch(
                context.graphics.device,
                context.graphics,
                context.graphics.preferredFormat
            )

    /** The [ShapeRenderer] managed by the scene graph. This uses the [batch] for drawing. */
    val shapeRenderer: ShapeRenderer = ShapeRenderer(this.batch, whitePixel)

    /**
     * The root [CanvasLayer] that is used for rendering all the children in the graph. Do not add
     * children directly to this node. Instead, add children to the [root] node.
     */
    val sceneCanvas: CanvasLayer by lazy {
        CanvasLayer().apply {
            name = "Scene Viewport"
            this.viewport = viewport
            resizeAutomatically = false
        }
    }

    /** The root node that should be used to add any children nodes to. */
    val root: Node by lazy { Node().apply { name = "Root" }.addTo(sceneCanvas) }

    /** The virtual width of the [sceneCanvas]. */
    val width: Float
        get() = sceneCanvas.virtualWidth

    /** The virtual height of hte [sceneCanvas]. */
    val height: Float
        get() = sceneCanvas.virtualHeight

    /** The target FPS for [tmod]. */
    var targetFPS = 60

    /**
     * The time modifier based off of [targetFPS].
     *
     * If [targetFPS] is set to `60` and the application is running at `120` FPS then this value
     * will be `0.5f` This can be used instead of [dt] to handle frame independent logic.
     */
    var tmod: Float = 1f
        private set

    /** Pixel Per Unit. Changing this value affects [ppuInv]. Defaults to `1`. */
    open var ppu = 1f

    /**
     * The inverse of [ppu]. Can be used to scale nodes correctly when using a [ppu] that isn't `1`.
     */
    val ppuInv
        get() = 1f / ppu

    /** The current delta time. */
    var dt: Duration = Duration.ZERO
        private set

    /**
     * The fixed progression lerp ratio for fixed updates. This is used for rendering nodes that use
     * [Node.fixedUpdate] for movement / physics logic.
     */
    val fixedProgressionRatio: Float
        get() = _fixedProgressionRatio

    /** The interval for [Node.fixedUpdate] to fire. Defaults to `30` times per second. */
    var fixedTimesPerSecond: Int = 30
        set(value) {
            field = value
            time = (1f / value).seconds
        }

    /**
     * When `true`, nodes will handle rendering debug related info such as node bounds. When `false`
     * nodes will not be rendered.
     *
     * This will take into effect after the current frame finishes.
     */
    var requestShowDebugInfo = false
        set(value) {
            if (field == value) return
            field = value
            debugInfoDirty = true
        }

    private var debugInfoDirty = false

    /**
     * This is the true current value if debugging is rendering. To change this value see
     * [requestShowDebugInfo].
     */
    var showDebugInfo: Boolean = false
        private set

    private var accum = 0.milliseconds
    private var _fixedProgressionRatio = 1f
    private var time = (1f / fixedTimesPerSecond).seconds

    /** Holds the current [Material] of the last rendered [Node] if no changes were made) */
    var currentMaterial: Material? = null

    /** The current frame count. */
    val frame: Int
        get() = frameCount

    /** The current [CommandEncoder]. This is set when [render] is invoked. */
    var commandEncoder: CommandEncoder? = null
        private set

    private var frameCount = 0

    // scene input related fields
    private var mouseScreenX: Float = 0f
    private var mouseScreenY: Float = 0f
    private var mouseOverControl: Control? = null
    private var keyboardFocus: Control? = null
    private val touchFocusPool = Pool(reset = { it.reset() }, preallocate = 1) { TouchFocus() }
    private val inputEventPool =
        Pool(reset = { it.reset() }, preallocate = 10) { InputEvent<InputType>() }
    private val touchFocuses = ArrayList<TouchFocus>(4)
    private val pointerScreenX = FloatArray(20)
    private val pointerScreenY = FloatArray(20)
    private val pointerOverControls = arrayOfNulls<Control>(20)
    private val pointerTouched = BooleanArray(20)

    private val tempVec = MutableVec2f()

    private var initialized = false

    private val unhandledInputQueue = ArrayDeque<InputEvent<InputType>>(20)
    private var dirty = true

    /**
     * Resizes the internal graph's [OrthographicCamera] and [CanvasLayer].
     *
     * @param centerCamera if true will center the graphs internal camera after resizing the
     *   viewport
     */
    open fun resize(width: Int, height: Int, centerCamera: Boolean = false) {
        dirty = true
        sceneCanvas.propagateResize(width, height, centerCamera)
    }

    /**
     * Initializes the root [Node] and [InputProcessor]. This must be called before an [update] or
     * [render] calls.
     */
    open suspend fun initialize() {
        controller.addInputMapProcessor(this)
        context.input.addInputProcessor(this)
        sceneCanvas.scene = this
        root.initialize()
        onStart()
        initialized = true
    }

    /**
     * Renders the entire tree.
     *
     * @param commandEncoder the [CommandEncoder] in order to create [RenderPassEncoder].
     * @param renderPassDescriptor the main [RenderPassDescriptor] to be used in creation of
     *   [RenderPassEncoder].
     */
    open fun render(commandEncoder: CommandEncoder, renderPassDescriptor: RenderPassDescriptor) {
        if (!initialized) {
            error("You need to call 'initialize()'once before doing any rendering or updating!")
        }

        this.commandEncoder = commandEncoder
        sceneCanvas.canvasRenderPassDescriptor = renderPassDescriptor
        sceneCanvas.render(batch, shapeRenderer) { node, _, _, _, _ -> checkNodeMaterial(node) }
        flush()

        if (debugInfoDirty) {
            showDebugInfo = requestShowDebugInfo
            debugInfoDirty = false
        }
    }

    private fun checkNodeMaterial(node: Node) {
        if (node !is CanvasItem) return

        // check for Material changes
        if (node.material != currentMaterial) {
            currentMaterial = node.material
            currentMaterial?.let { mat ->
                batch.setBlendState(mat.blendMode)
                mat.shader?.let {
                    mat.onPreRender()
                    batch.shader = it
                }
            }
        }
    }

    protected fun flush() {
        batch.setBlendState(BlendStates.NonPreMultiplied)
        batch.useDefaultShader()

        commandEncoder = null

        if (ownsBatch && batch.drawing) {
            batch.end()
        }
    }

    /**
     * Lifecycle method. This is called whenever the [SceneGraph] is set before [initialize] is
     * called. Any nodes added to this [Node] context won't be added until the next frame update.
     */
    open suspend fun Node.initialize() = Unit

    /** Lifecycle method. This is called when this scene becomes the active scene. */
    open fun onStart() = Unit

    /** Open method that is triggered whenever a [Control] node receives an input event. */
    open fun uiInput(control: Control, event: InputEvent<InputType>) {}

    /** Request a [Control] to receive keyboard focus. */
    fun requestFocus(control: Control) {
        if (keyboardFocus == control) return
        if (!control.enabled) return
        val oldFocus = keyboardFocus
        keyboardFocus = control
        oldFocus?._onFocusLost()
        control._onFocus()
    }

    /** Releases any current keyboard focus. */
    fun releaseFocus() {
        val control = keyboardFocus
        keyboardFocus = null
        control?._onFocusLost()
    }

    /** Checks if the [Control] has the current keyboard focus. */
    fun hasFocus(control: Control) = keyboardFocus == control

    /** Updates all the nodes in the tree. */
    open fun update(dt: Duration) {
        if (!initialized)
            error("You need to call 'initialize()' once before doing any rendering or updating!")
        this.dt = dt
        tmod = dt.seconds * targetFPS
        pointerOverControls.forEachIndexed { index, overLast ->
            if (!pointerTouched[index]) {
                if (overLast != null) {
                    pointerOverControls[index] = null
                    screenToSceneCoordinates(
                        tempVec.set(pointerScreenX[index], pointerScreenY[index])
                    )
                    val event =
                        inputEventPool.alloc().apply {
                            sceneX = tempVec.x
                            sceneY = tempVec.y
                            overLast.canvas?.screenToCanvasCoordinates(tempVec)
                            canvasX = tempVec.x
                            canvasY = tempVec.y
                            overLast.toLocal(tempVec, tempVec)
                            localX = tempVec.x
                            localY = tempVec.y
                            type = InputEvent.Type.MOUSE_EXIT
                            pointer = Pointer.cache[index]
                        }
                    overLast.callUiInput(event)
                    uiInput(overLast, event)
                    inputEventPool.free(event)
                }
                return@forEachIndexed
            }
            pointerOverControls[index] =
                fireEnterAndExit(
                    overLast,
                    pointerScreenX[index],
                    pointerScreenY[index],
                    Pointer.cache[index]
                )
        }

        when (context.platform) {
            Context.Platform.DESKTOP,
            Context.Platform.WEB -> {
                mouseOverControl =
                    fireEnterAndExit(mouseOverControl, mouseScreenX, mouseScreenY, Pointer.POINTER1)
            }
            else -> {
                // do nothing
            }
        }

        unhandledInputQueue.forEach {
            callUnhandledInput(it)
            inputEventPool.free(it)
        }
        unhandledInputQueue.clear()

        accum += dt
        while (accum >= time) {
            accum -= time
            if (
                root.enabled && (root.updateInterval == 1 || frameCount % root.updateInterval == 0)
            ) {
                root.propagateFixedUpdate()
            }
        }

        _fixedProgressionRatio = accum.milliseconds / time.milliseconds

        if (root.enabled && (root.updateInterval == 1 || frameCount % root.updateInterval == 0)) {
            root.propagatePreUpdate()
            root.propagateUpdate()
            root.propagatePostUpdate()
        }
        frameCount++
    }

    override fun touchDown(screenX: Float, screenY: Float, pointer: Pointer): Boolean {
        if (!isInsideViewport(screenX.toInt(), screenY.toInt())) return false
        if (controller.touchDown(screenX, screenY, pointer)) return true

        pointerTouched[pointer.ordinal] = true
        pointerScreenX[pointer.ordinal] = screenX
        pointerScreenY[pointer.ordinal] = screenY

        screenToSceneCoordinates(tempVec.set(screenX, screenY))

        val sceneX = tempVec.x
        val sceneY = tempVec.y

        val event =
            inputEventPool.alloc().apply {
                type = InputEvent.Type.TOUCH_DOWN
                this.sceneX = sceneX
                this.sceneY = sceneY
                this.pointer = pointer
            }

        // InputEvents go: input -> ui input -> unhandled input
        if (callInput(event)) {
            inputEventPool.free(event)
            return true
        }

        val target = callHitTest(tempVec.x, tempVec.y)
        target?.let {
            if (pointer == Pointer.MOUSE_LEFT && it.focusMode != Control.FocusMode.NONE) {
                it.grabFocus()
                keyboardFocus = it
            }
            it.callUiInput(event)
            uiInput(it, event)
            addTouchFocus(it, pointer)
        }
        if (event.handled) {
            inputEventPool.free(event)
            return true
        }

        unhandledInputQueue += event
        return false
    }

    override fun touchUp(screenX: Float, screenY: Float, pointer: Pointer): Boolean {
        if (controller.touchUp(screenX, screenY, pointer)) return true
        pointerTouched[pointer.ordinal] = false
        pointerScreenX[pointer.ordinal] = screenX
        pointerScreenY[pointer.ordinal] = screenY

        screenToSceneCoordinates(tempVec.set(screenX, screenY))

        val sceneX = tempVec.x
        val sceneY = tempVec.y

        val event =
            inputEventPool.alloc().apply {
                type = InputEvent.Type.TOUCH_UP
                this.sceneX = sceneX
                this.sceneY = sceneY
                this.pointer = pointer
            }

        // InputEvents go: input -> ui input -> unhandled input
        if (callInput(event)) {
            inputEventPool.free(event)
            return true
        }

        touchFocuses.fastForEach { focus ->
            if (focus.pointer != pointer) {
                return@fastForEach
            }
            if (!touchFocuses.remove(focus)) { // focus already gone
                return@fastForEach
            }
            focus.target?.let {
                it.toLocal(sceneX, sceneY, tempVec)
                event.apply {
                    localX = tempVec.x
                    localY = tempVec.y
                }
                it.callUiInput(event)
                uiInput(it, event)
            }
            touchFocusPool.free(focus)
        }

        if (event.handled) {
            inputEventPool.free(event)
            return true
        }

        unhandledInputQueue += event
        return false
    }

    override fun touchDragged(screenX: Float, screenY: Float, pointer: Pointer): Boolean {
        if (controller.touchDragged(screenX, screenY, pointer)) return true

        pointerScreenX[pointer.ordinal] = screenX
        pointerScreenY[pointer.ordinal] = screenY
        mouseScreenX = screenX
        mouseScreenY = screenY

        if (touchFocuses.isEmpty()) {
            return false
        }

        screenToSceneCoordinates(tempVec.set(screenX, screenY))

        val sceneX = tempVec.x
        val sceneY = tempVec.y

        val event =
            inputEventPool.alloc().apply {
                type = InputEvent.Type.TOUCH_DRAGGED
                this.sceneX = sceneX
                this.sceneY = sceneY
                this.pointer = pointer
            }

        // InputEvents go: input -> ui input -> unhandled input
        if (callInput(event)) {
            inputEventPool.free(event)
            return true
        }

        touchFocuses.fastForEach { focus ->
            if (focus.pointer != pointer) {
                return@fastForEach
            }
            if (!touchFocuses.contains(focus)) { // focus already gone
                return@fastForEach
            }
            focus.target?.let {
                it.toLocal(sceneX, sceneY, tempVec)
                event.apply {
                    localX = tempVec.x
                    localY = tempVec.y
                }
                it.callUiInput(event)
                uiInput(it, event)
            }
        }

        if (event.handled) {
            inputEventPool.free(event)
            return true
        }

        unhandledInputQueue += event
        return false
    }

    override fun mouseMoved(screenX: Float, screenY: Float): Boolean {
        if (controller.mouseMoved(screenX, screenY)) return true

        mouseScreenX = screenX
        mouseScreenY = screenY

        screenToSceneCoordinates(tempVec.set(screenX, screenY))

        val sceneX = tempVec.x
        val sceneY = tempVec.y

        val event =
            inputEventPool.alloc().apply {
                type = InputEvent.Type.MOUSE_HOVER
                this.sceneX = sceneX
                this.sceneY = sceneY
            }

        // InputEvents go: input -> ui input -> unhandled input
        if (callInput(event)) {
            inputEventPool.free(event)
            return true
        }

        mouseOverControl?.let {
            it.toLocal(sceneX, sceneY, tempVec)
            event.apply {
                localX = tempVec.x
                localY = tempVec.y
            }
            it.callUiInput(event)
            uiInput(it, event)
        }

        if (event.handled) {
            inputEventPool.free(event)
            return true
        }

        unhandledInputQueue += event
        return false
    }

    override fun onActionDown(inputType: InputType): Boolean {
        val event =
            inputEventPool.alloc().apply {
                type = InputEvent.Type.ACTION_DOWN
                this.inputType = inputType
            }

        // InputEvents go: input -> ui input -> unhandled input
        if (callInput(event)) {
            inputEventPool.free(event)
            return true
        }

        keyboardFocus?.let {
            it.callUiInput(event)
            uiInput(it, event)
            var handled = event.handled

            if (!handled) {
                var next: Control? = null
                when (inputType) {
                    uiInputSignals.uiFocusNext -> {
                        next = it.findNextValidFocus()
                        handled = true
                    }
                    uiInputSignals.uiFocusPrev -> {
                        next = it.findPreviousValidFocus()
                        handled = true
                    }
                    uiInputSignals.uiUp -> {
                        next = it.getFocusNeighbor(Control.Side.TOP)
                        handled = true
                    }
                    uiInputSignals.uiRight -> {
                        next = it.getFocusNeighbor(Control.Side.RIGHT)
                        handled = true
                    }
                    uiInputSignals.uiDown -> {
                        next = it.getFocusNeighbor(Control.Side.BOTTOM)
                        handled = true
                    }
                    uiInputSignals.uiLeft -> {
                        next = it.getFocusNeighbor(Control.Side.LEFT)
                        handled = true
                    }
                    else -> Unit
                }

                next?.grabFocus()
            }
            if (handled) {
                inputEventPool.free(event)
                return true
            }
        }

        unhandledInputQueue += event
        return false
    }

    override fun onActionUp(inputType: InputType): Boolean {
        val event =
            inputEventPool.alloc().apply {
                type = InputEvent.Type.ACTION_UP
                this.inputType = inputType
            }

        // InputEvents go: input -> ui input -> unhandled input
        if (callInput(event)) {
            inputEventPool.free(event)
            return true
        }

        keyboardFocus?.let {
            it.callUiInput(event)
            uiInput(it, event)
            val handled = event.handled
            if (handled) {
                inputEventPool.free(event)
                return true
            }
        }

        unhandledInputQueue += event
        return false
    }

    override fun onActionRepeat(inputType: InputType): Boolean {
        val event =
            inputEventPool.alloc().apply {
                type = InputEvent.Type.ACTION_REPEAT
                this.inputType = inputType
            }

        // InputEvents go: input -> ui input -> unhandled input
        if (callInput(event)) {
            inputEventPool.free(event)
            return true
        }

        keyboardFocus?.let {
            it.callUiInput(event)
            uiInput(it, event)
            val handled = event.handled
            if (handled) {
                inputEventPool.free(event)
                return true
            }
        }
        unhandledInputQueue += event
        return false
    }

    override fun keyDown(key: Key): Boolean {
        if (controller.keyDown(key)) return true

        val event =
            inputEventPool.alloc().apply {
                type = InputEvent.Type.KEY_DOWN
                this.key = key
            }
        // InputEvents go: input -> ui input -> unhandled input
        if (callInput(event)) {
            inputEventPool.free(event)
            return true
        }

        keyboardFocus?.let {
            it.callUiInput(event)
            uiInput(it, event)
            val handled = event.handled
            if (handled) {
                inputEventPool.free(event)
                return true
            }
        }

        unhandledInputQueue += event
        return false
    }

    override fun keyUp(key: Key): Boolean {
        if (controller.keyUp(key)) return true

        val event =
            inputEventPool.alloc().apply {
                type = InputEvent.Type.KEY_UP
                this.key = key
            }

        // InputEvents go: input -> ui input -> unhandled input
        if (callInput(event)) {
            inputEventPool.free(event)
            return true
        }

        keyboardFocus?.let {
            it.callUiInput(event)
            uiInput(it, event)
            val handled = event.handled
            if (handled) {
                inputEventPool.free(event)
                return true
            }
        }
        unhandledInputQueue += event
        return false
    }

    override fun keyRepeat(key: Key): Boolean {
        if (controller.keyRepeat(key)) return true

        val event =
            inputEventPool.alloc().apply {
                type = InputEvent.Type.KEY_REPEAT
                this.key = key
            }
        // InputEvents go: input -> ui input -> unhandled input
        if (callInput(event)) {
            inputEventPool.free(event)
            return true
        }
        keyboardFocus?.let {
            it.callUiInput(event)
            uiInput(it, event)
            val handled = event.handled
            if (handled) {
                inputEventPool.free(event)
                return true
            }
        }
        unhandledInputQueue += event
        return false
    }

    override fun charTyped(character: Char): Boolean {
        if (controller.charTyped(character)) return true

        val event =
            inputEventPool.alloc().apply {
                type = InputEvent.Type.CHAR_TYPED
                char = character
            }
        // InputEvents go: input -> ui input -> unhandled input
        if (callInput(event)) {
            inputEventPool.free(event)
            return true
        }
        keyboardFocus?.let {
            it.callUiInput(event)
            uiInput(it, event)
            val handled = event.handled
            if (handled) {
                inputEventPool.free(event)
                return true
            }
        }
        unhandledInputQueue += event
        return false
    }

    override fun scrolled(amountX: Float, amountY: Float): Boolean {
        if (controller.scrolled(amountX, amountY)) return true

        val event =
            inputEventPool.alloc().apply {
                type = InputEvent.Type.SCROLLED
                scrollAmountX = amountX
                scrollAmountY = amountY
            }
        // InputEvents go: input -> ui input -> unhandled input
        if (callInput(event)) {
            inputEventPool.free(event)
            return true
        }
        mouseOverControl?.let {
            it.callUiInput(event)
            uiInput(it, event)
            val handled = event.handled
            if (handled) {
                inputEventPool.free(event)
                return true
            }
        }
        unhandledInputQueue += event
        return false
    }

    override fun gamepadButtonPressed(button: GameButton, pressure: Float, gamepad: Int): Boolean {
        return controller.gamepadButtonPressed(button, pressure, gamepad)
    }

    override fun gamepadButtonReleased(button: GameButton, gamepad: Int): Boolean {
        return controller.gamepadButtonReleased(button, gamepad)
    }

    override fun gamepadJoystickMoved(
        stick: GameStick,
        xAxis: Float,
        yAxis: Float,
        gamepad: Int
    ): Boolean {
        return controller.gamepadJoystickMoved(stick, xAxis, yAxis, gamepad)
    }

    override fun gamepadTriggerChanged(button: GameButton, pressure: Float, gamepad: Int): Boolean {
        return controller.gamepadTriggerChanged(button, pressure, gamepad)
    }

    private fun fireEnterAndExit(
        overLast: Control?,
        screenX: Float,
        screenY: Float,
        pointer: Pointer
    ): Control? {
        screenToSceneCoordinates(tempVec.set(screenX, screenY))

        val sceneX = tempVec.x
        val sceneY = tempVec.y
        val over = callHitTest(tempVec.x, tempVec.y)
        if (over == overLast) return overLast

        if (overLast != null) {
            val event =
                inputEventPool.alloc().apply {
                    overLast.canvas?.screenToCanvasCoordinates(tempVec.set(screenX, screenY))
                    this.sceneX = sceneX
                    this.sceneY = sceneY
                    this.canvasX = tempVec.x
                    this.canvasY = tempVec.y
                    this.pointer = pointer
                    overLast.toLocal(sceneX, sceneY, tempVec)
                    localX = tempVec.x
                    localY = tempVec.y
                    type = InputEvent.Type.MOUSE_EXIT
                }
            overLast.let {
                it.callUiInput(event)
                uiInput(it, event)
            }
            inputEventPool.free(event)
        }

        if (over != null) {
            val event =
                inputEventPool.alloc().apply {
                    over.canvas?.screenToCanvasCoordinates(tempVec.set(screenX, screenY))
                    this.sceneX = sceneX
                    this.sceneY = sceneY
                    this.canvasX = tempVec.x
                    this.canvasY = tempVec.y
                    this.pointer = pointer
                    over.toLocal(sceneX, sceneY, tempVec)
                    localX = tempVec.x
                    localY = tempVec.y
                    type = InputEvent.Type.MOUSE_ENTER
                }
            over.let {
                it.callUiInput(event)
                uiInput(it, event)
            }
            inputEventPool.free(event)
        }
        return over
    }

    private fun addTouchFocus(target: Control, pointer: Pointer) {
        touchFocusPool
            .alloc()
            .apply {
                this.target = target
                this.pointer = pointer
            }
            .also { touchFocuses.add(it) }
    }

    private fun callHitTest(hx: Float, hy: Float): Control? {
        root.nodes.forEachReversed {
            val target = it.propagateHit(hx, hy)
            if (target != null) {
                return target
            }
        }
        return null
    }

    private fun callInput(event: InputEvent<InputType>): Boolean {
        root.nodes.forEachReversed {
            if (it.propagateInput(event)) {
                return true
            }
        }
        return false
    }

    private fun callUnhandledInput(event: InputEvent<InputType>): Boolean {
        root.nodes.forEachReversed {
            if (it.propagateUnhandledInput(event)) {
                return true
            }
        }
        return false
    }

    fun screenToSceneCoordinates(inOut: MutableVec2f) = sceneCanvas.screenToCanvasCoordinates(inOut)

    fun sceneToScreenCoordinates(inOut: MutableVec2f) = sceneCanvas.canvasToScreenCoordinates(inOut)

    private fun isInsideViewport(x: Int, y: Int): Boolean {
        val x0 = sceneCanvas.x
        val x1 = x0 + sceneCanvas.width
        val y0 = sceneCanvas.y
        val y1 = y0 + sceneCanvas.height
        val screenY = context.graphics.height - 1 - y
        return x in x0 until x1 && screenY in y0 until y1
    }

    /** Adds the new to the [root]. */
    operator fun plusAssign(node: Node) {
        root.addChild(node)
    }

    /** Removes the node from the [root]. */
    operator fun minusAssign(node: Node) {
        root.removeChild(node)
    }

    /**
     * Lifecycle method. Do any necessary unloading / disposing here. This is called when this scene
     * is removed from the active slot.
     */
    override fun release() {
        sceneCanvas.destroy()
        if (ownsBatch) {
            batch.release()
        }

        controller.removeInputMapProcessor(this)
        context.input.removeInputProcessor(this)
    }

    /**
     * Input signals uses internally by [SceneGraph] for handling UI related inputs.
     *
     * @param uiAccept a signal to confirm a focused [Control] (e.g. `Key.ENTER` or `Key.SPACE` on a
     *   button).
     * @param uiSelect a signal to select a [Control] in a list (e.g.`Key.SPACE`)
     * @param uiCancel a signal to cancel a pending input or window (e.g. `Key.ESCAPE`).
     * @param uiFocusNext a signal to focus the next focusable [Control] in the graph (e.g.
     *   `Key.TAB`)
     * @param uiFocusPrev a signal to focus the previous focusable [Control] in the graph (e.g.
     *   `Key.SHIFT && Key.TAB`)
     * @param uiLeft a signal to focus a focusable [Control] to the left of the current focused
     *   [Control] (e.g. `Key.ARROW_LEFT`)
     * @param uiRight a signal to focus a focusable [Control] to the right of the current focused
     *   [Control] (e.g. `Key.ARROW_RIGHT`)
     * @param uiUp a signal to focus a focusable [Control] upwards from the current focused
     *   [Control] (e.g. `Key.ARROW_UP`)
     * @param uiDown a signal to focus a focusable [Control] downwards from the current focused
     *   [Control] (e.g. `Key.ARROW_DOWN`)
     * @param uiHome a signal to go to the start position of a [Control] (e.g. `Key.HOME`)
     * @param uiEnd a signal to go to the end position of a [Control] (e.g. `Key.END`)
     */
    data class UiInputSignals<InputType>(
        val uiAccept: InputType? = null,
        val uiSelect: InputType? = null,
        val uiCancel: InputType? = null,
        val uiFocusNext: InputType? = null,
        val uiFocusPrev: InputType? = null,
        val uiLeft: InputType? = null,
        val uiRight: InputType? = null,
        val uiUp: InputType? = null,
        val uiDown: InputType? = null,
        val uiHome: InputType? = null,
        val uiEnd: InputType? = null,
    )

    private data class TouchFocus(
        var target: Control? = null,
        var pointer: Pointer = Pointer.POINTER1,
    ) {
        fun reset() {
            target = null
        }
    }
}
