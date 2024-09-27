package com.littlekt.examples

import com.littlekt.Context
import com.littlekt.ContextListener
import com.littlekt.file.vfs.readTexture
import com.littlekt.graph.node.canvasLayer
import com.littlekt.graph.node.node2d.camera2d
import com.littlekt.graph.node.node2d.node2d
import com.littlekt.graph.node.ui.*
import com.littlekt.graph.sceneGraph
import com.littlekt.graphics.Color
import com.littlekt.graphics.HAlign
import com.littlekt.graphics.VAlign
import com.littlekt.input.Key
import com.littlekt.math.geom.Angle
import com.littlekt.math.geom.degrees
import com.littlekt.math.geom.radians
import com.littlekt.util.viewport.ExtendViewport
import io.ygdrasil.wgpu.CommandEncoderDescriptor
import io.ygdrasil.wgpu.LoadOp
import io.ygdrasil.wgpu.PresentMode
import io.ygdrasil.wgpu.RenderPassDescriptor
import io.ygdrasil.wgpu.StoreOp
import io.ygdrasil.wgpu.SurfaceTextureStatus
import io.ygdrasil.wgpu.TextureUsage

/**
 * An example using a [sceneGraph]
 *
 * @author Colton Daily
 * @date 5/2/2024
 */
class HelloSceneGraphExample(context: Context) : ContextListener(context) {

    override suspend fun Context.start() {
        addStatsHandler()
        addCloseOnEsc()
        val icon = resourcesVfs["icon_16x16.png"].readTexture()
        val device = graphics.device

        val preferredFormat = graphics.preferredFormat

        graphics.configureSurface(
            setOf(TextureUsage.renderAttachment),
            preferredFormat,
            PresentMode.fifo,
            graphics.surface.supportedAlphaMode.first()
        )

        val graph =
            sceneGraph(this, ExtendViewport(960, 540)) {
                    canvasLayerContainer {
                        stretch = true
                        shrink = 2
                        anchorRight = 1f
                        anchorTop = 1f

                        canvasLayer {
                            scrollContainer {
                                minWidth = 100f
                                minHeight = 100f
                                column {
                                    repeat(10) {
                                        label { text = "hi: this is rreally lognadsfda ad$it" }
                                    }
                                }
                            }
                            node2d {
                                rotation = 45.degrees
                                onReady += { println("$name: $canvas") }
                                onUpdate += {
                                    if (input.isKeyPressed(Key.D)) {
                                        globalX += 1f
                                    } else if (input.isKeyPressed(Key.A)) {
                                        globalX -= 1f
                                    }

                                    if (input.isKeyPressed(Key.S)) {
                                        globalY -= 1f
                                    } else if (input.isKeyPressed(Key.W)) {
                                        globalY += 1f
                                    }
                                }
                                onRender += { batch, camera, shapeRenderer ->
                                    batch.draw(icon, globalX, globalY, rotation = globalRotation)
                                }
                                camera2d { active = true }
                            }

                            var rotation = Angle.ZERO
                            node2d {
                                x = 100f
                                y = 20f
                                onRender += { batch, camera, shapeRenderer ->
                                    rotation += 0.01.radians
                                    batch.draw(
                                        icon,
                                        globalX,
                                        globalY,
                                        scaleX = 2f,
                                        scaleY = 2f,
                                        rotation = rotation
                                    )
                                }
                            }
                        }
                    }
                    centerContainer {
                        anchorRight = 1f
                        anchorTop = 1f
                        button {
                            x = 200f
                            y = 300f
                            text = "center button"
                            horizontalAlign = HAlign.CENTER
                            verticalAlign = VAlign.CENTER

                            onReady += { println("$name:${canvas!!::class.simpleName} - $canvas") }
                        }
                    }
                    button {
                        x = 200f
                        y = 300f
                        text = "outsied button"
                        horizontalAlign = HAlign.CENTER
                        verticalAlign = VAlign.CENTER

                        onReady += { println("$name:${canvas!!::class.simpleName} - $canvas") }
                    }
                }
                .also { it.initialize() }

        graph.requestShowDebugInfo = true
        onResize { width, height ->
            graph.resize(width, height)
            graphics.configureSurface(
                setOf(TextureUsage.renderAttachment),
                preferredFormat,
                PresentMode.fifo,
                graphics.surface.supportedAlphaMode.first()
            )
        }

        onUpdate { dt ->
            val surfaceTexture = graphics.surface.getCurrentTexture()
            when (val status = surfaceTexture.status) {
                SurfaceTextureStatus.success -> {
                    // all good, could check for `surfaceTexture.suboptimal` here.
                }
                SurfaceTextureStatus.timeout,
                SurfaceTextureStatus.outdated,
                SurfaceTextureStatus.lost -> {
                    surfaceTexture.texture.close()
                    logger.info { "getCurrentTexture status=$status" }
                    return@onUpdate
                }
                else -> {
                    // fatal
                    logger.fatal { "getCurrentTexture status=$status" }
                    close()
                    return@onUpdate
                }
            }
            val swapChainTexture = checkNotNull(surfaceTexture.texture)
            val frame = swapChainTexture.createView()

            val commandEncoder = device.createCommandEncoder(CommandEncoderDescriptor("scenegraph command encoder"))
            val renderPassDescriptor =
                RenderPassDescriptor(
                    listOf(
                        RenderPassDescriptor.ColorAttachment(
                            view = frame,
                            loadOp = LoadOp.clear,
                            storeOp = StoreOp.store,
                            clearValue = Color.DARK_GRAY.toWebGPUColor()
                        )
                    ),
                    label = "Init render pass"
                )
            graph.update(dt)
            graph.render(commandEncoder, renderPassDescriptor)

            val commandBuffer = commandEncoder.finish()

            device.queue.submit(listOf(commandBuffer))
            graphics.surface.present()

            commandBuffer.close()
            commandEncoder.close()
            frame.close()
            swapChainTexture.close()
        }

        onRelease { graph.release() }
    }
}
