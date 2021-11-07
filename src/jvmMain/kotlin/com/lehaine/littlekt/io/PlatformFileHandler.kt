package com.lehaine.littlekt.io

import com.lehaine.littlekt.GL
import com.lehaine.littlekt.log.Logger
import com.lehaine.littlekt.render.TextureImage
import de.matthiasmann.twl.utils.PNGDecoder
import java.io.File
import java.nio.Buffer
import java.nio.ByteBuffer
import fr.delthas.javamp3.Sound as Mp3Sound

/**
 * @author Colton Daily
 * @date 11/6/2021
 */
actual class PlatformFileHandler(actual val logger: Logger) {

    actual fun read(filename: String): Content<String> {
        val content = Content<String>(filename, logger)

        // Check if the resource is embedded in the jar
        val fromJar = PlatformFileHandler::class.java.getResourceAsStream("/$filename")
        val text = fromJar?.readBytes()?.let { String(it) } ?: File(filename).readText()
        content.load(text)
        return content
    }

    actual fun readData(filename: String): Content<ByteArray> {
        val content = Content<ByteArray>(filename, logger)
        // Check if the resource is embedded in the jar
        val fromJar = PlatformFileHandler::class.java.getResource("/$filename")
        val bytes = fromJar?.readBytes() ?: File(filename).readBytes()
        content.load(bytes)
        return content
    }

    actual fun readTextureImage(filename: String): Content<TextureImage> {
        val content = Content<TextureImage>(filename, logger)

        // lock first in the jar before checking on the filesystem.
        val stream = PlatformFileHandler::class.java.getResource("/$filename")?.openStream()
            ?: File(filename).inputStream()

        val decoder = PNGDecoder(stream)

        // create a byte buffer big enough to store RGBA values
        val buffer =
            ByteBuffer.allocateDirect(4 * decoder.width * decoder.height)

        // decode
        decoder.decode(buffer, decoder.width * 4, PNGDecoder.Format.RGBA)

        // flip the buffer so its ready to read
        (buffer as Buffer).flip()

        content.load(
            TextureImage(
                width = decoder.width,
                height = decoder.height,
                glFormat = GL.RGBA,
                glType = GL.UNSIGNED_BYTE,
                pixels = buffer
            )
        )
        return content
    }

    actual fun readSound(filename: String): Content<Sound> {
        val (source, channels, frequency) = if (filename.endsWith(".mp3")) {
            val mp3Stream = Mp3Sound(File(filename).inputStream())
            val source = mp3Stream.readBytes().also { mp3Stream.close() }
            val channels = if (mp3Stream.isStereo) {
                2
            } else {
                1
            }
            Triple(source, channels, mp3Stream.samplingFrequency)
        } else {
            TODO("NON MP3 SOUNDS NOT YET IMPLEMENTED")
        }
        val buffer = ByteBuffer.allocateDirect(source.size)
        buffer.put(source)
        (buffer as Buffer).position(0)
        val content = Content<Sound>(filename, logger)
        content.load(Sound(buffer.asShortBuffer(), channels, frequency))
        return content
    }
}