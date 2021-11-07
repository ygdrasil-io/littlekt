package com.lehaine.littlekt.io

import com.lehaine.littlekt.Application
import com.lehaine.littlekt.Percent
import com.lehaine.littlekt.log.Logger
import com.lehaine.littlekt.render.Texture
import com.lehaine.littlekt.render.TextureImage
import kotlin.reflect.KClass

/**
 * @author Colton Daily
 * @date 11/6/2021
 */
private fun createLoaders(): Map<KClass<*>, FileLoader<*>> = mapOf(
    TextureImage::class to TextureImageLoader(),
    Texture::class to TextureLoader(),
    Sound::class to SoundLoader(),
)

class FileHandlerCommon(
    override val application: Application,
    private val platformFileHandler: PlatformFileHandler,
    private val logger: Logger,
    private val loaders: Map<KClass<*>, FileLoader<*>> = createLoaders()
) : FileHandler {

    private val assets = mutableMapOf<String, Content<*>>()

    override fun <T> create(filename: String, value: T): Content<T> {
        val content = Content<T>(filename, logger)
        assets.put(filename, content)
        content.load(value)
        return content
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T, R : Any> get(filename: String, rClazz: KClass<R>, map: (R) -> Content<T>): Content<T> {
        return get(filename, rClazz).map(map) as Content<T>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(filename: String, clazz: KClass<T>): Content<T> {
        return assets.getOrPut(filename) { load(filename, clazz) } as Content<T>
    }

    override fun read(filename: String): Content<String> = platformFileHandler.read(filename)

    override fun readData(filename: String): Content<ByteArray> = platformFileHandler.readData(filename)

    override fun readTextureImage(filename: String): Content<TextureImage> =
        platformFileHandler.readTextureImage(filename)

    override fun readSound(filename: String): Content<Sound> = platformFileHandler.readSound(filename)

    @Suppress("UNCHECKED_CAST")
    private fun <R : Any> load(filename: String, clazz: KClass<R>): Content<R> {
        logger.info("FILE_HANDLER") { "Loading '$filename' as '${clazz.simpleName}' type" }
        val loader = loaders[clazz]
        if (loader == null) {
            throw UnsupportedTypeException(clazz)
        } else {
            return loader.load(filename, this) as Content<R>
        }
    }

    override fun isFullyLoaded(): Boolean {
        return assets.all { it.value.loaded() }
    }

    override fun loadingProgress(): Percent {
        val loaded = assets.count { it.value.loaded() }
        return loaded / assets.count().toFloat()
    }
}