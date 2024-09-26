package com.littlekt.graphics

import kotlin.jvm.JvmInline

/**
 * A value class used to designate a usage for a [VertexAttributeView] in a shader. Custom attributes
 * may be created.
 *
 * @see POSITION
 * @see COLOR
 * @see NORMAL
 * @see TEX_COORDS
 * @see GENERIC
 * @see WEIGHT
 * @see TANGENT
 * @see BINORMAL
 * @see JOINT
 * @author Colton Daily
 * @date 4/10/2024
 */
@JvmInline
value class VertexAttrUsage(val usage: Int) {
    companion object {
        /** Used for positioning. */
        val POSITION = VertexAttrUsage(1)

        /** Used for color. */
        val COLOR = VertexAttrUsage(2)

        /** Used for normals. */
        val NORMAL = VertexAttrUsage(4)

        /** Used for UV coords. */
        val TEX_COORDS = VertexAttrUsage(8)

        /** A catch-all generic attribute. */
        val GENERIC = VertexAttrUsage(16)

        /** Used for vertex weights. */
        val WEIGHT = VertexAttrUsage(32)

        /** Used for tangents. */
        val TANGENT = VertexAttrUsage(64)

        /** Used for binormals. */
        val BINORMAL = VertexAttrUsage(128)

        /** Used for joints. */
        val JOINT = VertexAttrUsage(256)
    }
}
