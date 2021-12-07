package com.lehaine.littlekt.graphics

import com.lehaine.littlekt.Context
import com.lehaine.littlekt.Disposable
import com.lehaine.littlekt.file.createUint8Buffer
import com.lehaine.littlekt.graphics.gl.*

/**
 * @author Colton Daily
 * @date 11/6/2021
 */
class Texture(
    val textureData: TextureData
) : Preparable, Disposable {
    private var gl: GL? = null
    private var isPrepared = false

    val width: Int get() = textureData.width
    val height: Int get() = textureData.height
    var glTexture: GlTexture? = null

    override val prepared: Boolean
        get() = isPrepared

    /**
     * Sets the [TexMagFilter] for this texture for magnification. This will bind the texture if the texture has been loaded.
     */
    var magFilter = TexMagFilter.NEAREST
        set(value) {
            field = value
            gl?.let {
                bind()
                it.texParameteri(
                    TextureTarget._2D,
                    TexParameter.MAG_FILTER,
                    value.glFlag
                )
            }
        }

    /**
     * Sets the [TexMinFilter] for this texture for minification. This will bind the texture if the texture has been loaded.
     */
    var minFilter = TexMinFilter.NEAREST
        set(value) {
            field = value
            gl?.let {
                bind()
                it.texParameteri(
                    TextureTarget._2D,
                    TexParameter.MIN_FILTER,
                    value.glFlag
                )
            }
        }

    /**
     * Sets the [TexWrap] for this texture on the **S** axis. This will bind the texture if the texture has been loaded.
     */
    var uWrap = TexWrap.CLAMP_TO_EDGE
        set(value) {
            field = value
            gl?.let {
                bind()
                it.texParameteri(TextureTarget._2D, TexParameter.WRAP_S, value.glFlag)
            }
        }

    /**
     * Sets the [TexWrap] for this texture on the **V** axis. This will bind the texture if the texture has been loaded.
     */
    var vWrap = TexWrap.CLAMP_TO_EDGE
        set(value) {
            field = value
            gl?.let {
                bind()
                it.texParameteri(TextureTarget._2D, TexParameter.WRAP_T, value.glFlag)
            }
        }

    /**
     * Prepares the texture for the [Context]. Sets this Textures [GL] context to the passed in application.
     * @param context the application that will be used as the [GL] context for this texture.
     */
    override fun prepare(context: Context) {
        this.gl = context.graphics.gl
        val gl = context.graphics.gl
        if (!textureData.isPrepared) {
            textureData.prepare()
        }
        val texture = glTexture ?: gl.createTexture()

        gl.bindTexture(GL.TEXTURE_2D, texture)

        gl.texParameteri(
            TextureTarget._2D,
            TexParameter.MAG_FILTER,
            magFilter.glFlag
        )
        gl.texParameteri(
            TextureTarget._2D,
            TexParameter.MIN_FILTER,
            minFilter.glFlag
        )

        gl.texParameteri(TextureTarget._2D, TexParameter.WRAP_S, uWrap.glFlag)
        gl.texParameteri(TextureTarget._2D, TexParameter.WRAP_T, vWrap.glFlag)

        textureData.uploadImageData(context, TextureTarget._2D, textureData)

        glTexture = texture
        isPrepared = true
    }

    /**
     * Binds this texture to the given texture unit. Sets the currently active texture via [GL.activeTexture]
     * @param unit the unit (defaults to 0)
     */
    fun bind(unit: Int = 0) {
        val gl = gl
        val textureReference = glTexture
        check(isPrepared && gl != null && textureReference != null) { "Texture has not been prepared yet! Ensure you called the prepare() method." }
        gl.activeTexture(GL.TEXTURE0 + unit)
        gl.bindTexture(TextureTarget._2D, textureReference)

    }

    override fun dispose() {
        val gl = gl ?: return
        val glTexture = glTexture ?: return

        gl.deleteTexture(glTexture)
    }

    companion object {
        val DEFAULT = Texture(
            PixmapTextureData(
                Pixmap(
                    2, 2,
                    createUint8Buffer(
                        byteArrayOf(
                            0xFF.toByte(), 0x00.toByte(), 0x00.toByte(), 0xFF.toByte(),
                            0x00.toByte(), 0xFF.toByte(), 0x00.toByte(), 0xFF.toByte(),
                            0x00.toByte(), 0x00.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                            0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(), 0xFF.toByte()
                        )
                    )
                ),
                true
            )
        )
    }
}


fun Texture.slice(sliceWidth: Int, sliceHeight: Int) = TextureSlice(this).slice(sliceWidth, sliceHeight)