package com.littlekt.examples

import com.littlekt.Context
import com.littlekt.ContextListener
import com.littlekt.file.vfs.readTiledMap
import com.littlekt.graphics.Color
import com.littlekt.graphics.g2d.SpriteCache
import com.littlekt.graphics.webgpu.*
import com.littlekt.util.viewport.ExtendViewport

/**
 * Load and render a Tiled map using a [SpriteCache].
 *
 * @author Colton Daily
 * @date 5/1/2024
 */
class TiledTileMapCacheExample(context: Context) : ContextListener(context) {

    override suspend fun Context.start() {
        addStatsHandler()
        addCloseOnEsc()
        val device = graphics.device

        val map = resourcesVfs["tiled/ortho-tiled-world.tmj"].readTiledMap()
        val surfaceCapabilities = graphics.surfaceCapabilities
        val preferredFormat = graphics.preferredFormat

        graphics.configureSurface(
            setOf(TextureUsage.renderAttachment),
            preferredFormat,
            PresentMode.fifo,
            graphics.surface.supportedAlphaMode.first()
        )

        val cache = SpriteCache(device, preferredFormat)
        map.addToCache(cache, 0f, 0f, 1 / 8f)
        val viewport = ExtendViewport(30, 16)
        val camera = viewport.camera
        var bgColor = map.backgroundColor ?: Color.DARK_GRAY
        if (preferredFormat.srgb) {
            bgColor = bgColor.toLinear()
        }

        onResize { width, height ->
            viewport.update(width, height)
            graphics.configureSurface(
                setOf(TextureUsage.renderAttachment),
                preferredFormat,
                PresentMode.fifo,
                graphics.surface.supportedAlphaMode.first()
            )
        }

        addWASDMovement(camera, 0.05f)
        onUpdate {
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

            val commandEncoder = device.createCommandEncoder()
            val renderPassEncoder =
                commandEncoder.beginRenderPass(
                    desc =
                        RenderPassDescriptor(
                            listOf(
                                RenderPassDescriptor.ColorAttachment(
                                    view = frame,
                                    loadOp = LoadOp.clear,
                                    storeOp = StoreOp.store,
                                    clearColor = bgColor
                                )
                            )
                        )
                )
            camera.update()
            map.updateCachedAnimationTiles(cache)
            cache.render(renderPassEncoder, camera.viewProjection)
            renderPassEncoder.end()

            val commandBuffer = commandEncoder.finish()

            device.queue.submit(commandBuffer)
            graphics.surface.present()

            commandBuffer.release()
            renderPassEncoder.release()
            commandEncoder.release()
            frame.release()
            swapChainTexture.release()
        }

        onRelease {
            cache.release()
            map.release()
        }
    }
}
