package com.lehaine.littlekt

import com.lehaine.littlekt.input.Input
import com.lehaine.littlekt.io.AssetManager
import com.lehaine.littlekt.io.FileHandler
import com.lehaine.littlekt.log.Logger

/**
 * @author Colton Daily
 * @date 11/17/2021
 */
expect class PlatformApplication(configuration: ApplicationConfiguration) : Application {
    override val configuration: ApplicationConfiguration
    override val graphics: Graphics
    override val input: Input
    override val logger: Logger
    override val assetManager: AssetManager
    override val fileHandler: FileHandler
    override fun start(gameBuilder: (app: Application) -> LittleKt)
    override fun close()
    override fun destroy()
}