package com.littlekt.examples

import com.littlekt.Context
import com.littlekt.ContextListener
import com.littlekt.file.vfs.readPixmap
import com.littlekt.graphics.Color
import com.littlekt.graphics.textureIndexedMesh

/**
 * An example showing drawing a texture with a [textureIndexedMesh].
 *
 * @author Colton Daily
 * @date 4/20/2024
 */
class TextureMeshExample(context: Context) : ContextListener(context) {
    // language=wgsl
    private val textureShader =
        """
            struct VertexOutput {
                @location(0) uv: vec2<f32>,
                @builtin(position) position: vec4<f32>,
            };
                       
            
            @vertex
            fn vs_main(
                @location(0) pos: vec3<f32>,
                @location(1) color: vec4<f32>,
                @location(2) uvs: vec2<f32>) -> VertexOutput {
                
                var output: VertexOutput;
                output.position = vec4<f32>(pos.x, pos.y, 0, 1);
                output.uv = uvs;
                
                return output;
            }
            
            @group(0) @binding(0)
            var my_texture: texture_2d<f32>;
            @group(0) @binding(1)
            var my_sampler: sampler;
            
            @fragment
            fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
                return textureSample(my_texture, my_sampler, in.uv);
            }
        """
            .trimIndent()

    override suspend fun Context.start() {
        addStatsHandler()
        val image = resourcesVfs["logo.png"].readPixmap()
        val device = graphics.device
        val mesh =
            textureIndexedMesh(device) {
                indicesAsQuad()
                addVertex {
                    position.set(-0.5f, -0.5f, 0f)
                    texCoords.set(0f, 1f)
                }
                addVertex {
                    position.set(-0.5f, 0.5f, 0f)
                    texCoords.set(0f, 0f)
                }
                addVertex {
                    position.set(0.5f, 0.5f, 0f)
                    texCoords.set(1f, 0f)
                }
                addVertex {
                    position.set(0.5f, -0.5f, 0f)
                    texCoords.set(1f, 1f)
                }
            }
        mesh.update()
        val shader = device.createShaderModule(textureShader)
        val surfaceCapabilities = graphics.surfaceCapabilities
        val preferredFormat = graphics.preferredFormat
        val texture =
            device.createTexture(
                TextureDescriptor(
                    Extent3D(image.width, image.height, 1),
                    1,
                    1,
                    TextureDimension.D2,
                    if (preferredFormat.srgb) TextureFormat.RGBA8_UNORM_SRGB
                    else TextureFormat.RGBA8_UNORM,
                    TextureUsage.COPY_DST or TextureUsage.TEXTURE
                )
            )

        val queue = device.queue
        queue.writeTexture(
            data = image.pixels.toArray(),
            destination = TextureCopyView(texture),
            layout = TextureDataLayout(image.width * 4, image.height),
            copySize = Extent3D(image.width, image.height, 1)
        )

        val sampler = device.createSampler(SamplerDescriptor())
        val textureView = texture.createView()
        val bindGroupLayout =
            device.createBindGroupLayout(
                BindGroupLayoutDescriptor(
                    listOf(
                        BindGroupLayoutEntry(0, ShaderStage.FRAGMENT, TextureBindingLayout()),
                        BindGroupLayoutEntry(1, ShaderStage.FRAGMENT, SamplerBindingLayout())
                    )
                )
            )
        val bindGroup =
            device.createBindGroup(
                desc =
                    BindGroupDescriptor(
                        bindGroupLayout,
                        listOf(BindGroupEntry(0, textureView), BindGroupEntry(1, sampler))
                    )
            )
        val pipelineLayout = device.createPipelineLayout(PipelineLayoutDescriptor(bindGroupLayout))
        val renderPipelineDesc =
            RenderPipelineDescriptor(
                layout = pipelineLayout,
                vertex =
                    VertexState(
                        module = shader,
                        entryPoint = "vs_main",
                        mesh.geometry.layout.gpuVertexBufferLayout
                    ),
                fragment =
                    FragmentState(
                        module = shader,
                        entryPoint = "fs_main",
                        target =
                            ColorTargetState(
                                format = preferredFormat,
                                blendState = BlendState.NonPreMultiplied,
                                writeMask = ColorWriteMask.ALL
                            )
                    ),
                primitive = PrimitiveState(topology = PrimitiveTopology.TRIANGLE_LIST),
                depthStencil = null,
                multisample =
                    MultisampleState(count = 1, mask = 0xFFFFFFF, alphaToCoverageEnabled = false)
            )
        val renderPipeline = device.createRenderPipeline(renderPipelineDesc)
        graphics.configureSurface(
            TextureUsage.RENDER_ATTACHMENT,
            preferredFormat,
            PresentMode.FIFO,
            surfaceCapabilities.alphaModes[0]
        )

        onUpdate {
            val surfaceTexture = graphics.surface.getCurrentTexture()
            when (val status = surfaceTexture.status) {
                TextureStatus.SUCCESS -> {
                    // all good, could check for `surfaceTexture.suboptimal` here.
                }
                TextureStatus.TIMEOUT,
                TextureStatus.OUTDATED,
                TextureStatus.LOST -> {
                    surfaceTexture.texture?.release()
                    graphics.configureSurface(
                        TextureUsage.RENDER_ATTACHMENT,
                        preferredFormat,
                        PresentMode.FIFO,
                        surfaceCapabilities.alphaModes[0]
                    )
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
                                RenderPassColorAttachmentDescriptor(
                                    view = frame,
                                    loadOp = LoadOp.CLEAR,
                                    storeOp = StoreOp.STORE,
                                    clearColor =
                                        if (preferredFormat.srgb) Color.DARK_GRAY.toLinear()
                                        else Color.DARK_GRAY
                                )
                            )
                        )
                )
            renderPassEncoder.setPipeline(renderPipeline)
            renderPassEncoder.setBindGroup(0, bindGroup)
            renderPassEncoder.setVertexBuffer(0, mesh.vbo)
            renderPassEncoder.setIndexBuffer(mesh.ibo, IndexFormat.UINT16)
            renderPassEncoder.drawIndexed(6, 1)
            renderPassEncoder.end()

            val commandBuffer = commandEncoder.finish()

            queue.submit(commandBuffer)
            graphics.surface.present()

            commandBuffer.release()
            renderPassEncoder.release()
            commandEncoder.release()
            frame.release()
            swapChainTexture.release()
        }

        onRelease {
            renderPipeline.release()
            pipelineLayout.release()
            bindGroup.release()
            bindGroupLayout.release()
            sampler.release()
            textureView.release()
            texture.release()
            mesh.release()
            texture.release()
            shader.release()
        }
    }
}
