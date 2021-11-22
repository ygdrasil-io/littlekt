package com.lehaine.littlekt.io

import com.lehaine.littlekt.Application
import com.lehaine.littlekt.graphics.TextureData
import com.lehaine.littlekt.log.Logger

/**
 * @author Colton Daily
 * @date 11/6/2021
 */
class WebFileHandler(application: Application, logger: Logger) : BaseFileHandler(application, logger) {

    override fun read(filename: String): Content<String> {
        TODO("Not yet implemented")
    }

    override fun readData(filename: String): Content<ByteArray> {
        TODO("Not yet implemented")
    }

    override fun readTextureData(filename: String): Content<TextureData> {
        TODO("Not yet implemented")
    }

    override fun readSound(filename: String): Content<Sound> {
        TODO("Not yet implemented")
    }

}