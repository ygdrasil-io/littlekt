package com.lehaine.littlekt

import com.lehaine.littlekt.file.FileHandler
import com.lehaine.littlekt.file.WebFileHandler
import com.lehaine.littlekt.graphics.Texture
import com.lehaine.littlekt.input.Input
import com.lehaine.littlekt.input.JsInput
import com.lehaine.littlekt.log.Logger
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * @author Colton Daily
 * @date 10/4/2021
 */
class WebGLContext(override val configuration: JsConfiguration) :
    Context {

    val canvas = document.getElementById(configuration.canvasId) as HTMLCanvasElement

    override val stats: AppStats = AppStats()
    override val graphics: Graphics = WebGLGraphics(canvas, stats.engineStats)
    override val input: Input = JsInput(canvas)
    override val logger: Logger = Logger(configuration.title)
    override val fileHandler: FileHandler =
        WebFileHandler(this, logger, configuration.rootPath)
    override val platform: Context.Platform = Context.Platform.JS

    private lateinit var listener: ContextListener
    private var lastFrame = 0.0
    private var closed = false

    override fun start(build: (app: Context) -> ContextListener) {
        graphics as WebGLGraphics
        input as JsInput

        graphics._width = canvas.clientWidth
        graphics._height = canvas.clientHeight

        listener = build(this)

        Texture.DEFAULT.prepare(listener.context)
        window.requestAnimationFrame(::render)
    }

    @Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
    @OptIn(ExperimentalTime::class)
    private fun render(now: Double) {
        if (canvas.clientWidth != graphics.width ||
            canvas.clientHeight != graphics.height
        ) {
            graphics as WebGLGraphics
            graphics._width = canvas.clientWidth
            graphics._height = canvas.clientHeight
            canvas.width = canvas.clientWidth
            canvas.height = canvas.clientHeight
            listener.resize(graphics.width, graphics.height)
        }
        stats.engineStats.resetPerFrameCounts()
        input as JsInput
        val dt = ((now - lastFrame) / 1000.0).seconds
        lastFrame = now

        input.update()
        stats.update(dt)
        listener.render(dt)
        input.reset()

        if (closed) {
            destroy()
        } else {
            window.requestAnimationFrame(::render)
        }
    }

    override fun close() {
        closed = true
    }

    override fun destroy() {
        listener.dispose()
    }

}