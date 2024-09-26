package com.littlekt

/**
 * @author Colton Daily
 * @date 11/17/2021
 */
abstract class ContextConfiguration {
    abstract val title: String
    open val loadInternalResources: Boolean = true
}
