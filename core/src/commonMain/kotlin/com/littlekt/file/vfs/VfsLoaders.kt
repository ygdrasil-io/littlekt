package com.littlekt.file.vfs

import com.littlekt.audio.AudioClip
import com.littlekt.audio.AudioStream
import com.littlekt.file.UnsupportedFileTypeException
import com.littlekt.file.atlas.AtlasInfo
import com.littlekt.file.atlas.AtlasPage
import com.littlekt.file.ldtk.LDtkMapData
import com.littlekt.file.ldtk.LDtkMapLoader
import com.littlekt.file.tiled.TiledMapData
import com.littlekt.file.tiled.TiledMapLoader
import com.littlekt.graphics.Pixmap
import com.littlekt.graphics.Texture
import com.littlekt.graphics.g2d.TextureAtlas
import com.littlekt.graphics.g2d.TextureSlice
import com.littlekt.graphics.g2d.font.*
import com.littlekt.graphics.g2d.tilemap.ldtk.LDtkWorld
import com.littlekt.graphics.g2d.tilemap.tiled.TiledMap
import com.littlekt.math.MutableVec4i
import com.littlekt.util.internal.unquote
import io.ygdrasil.wgpu.TextureFormat
import kotlin.math.max

/**
 * Loads a [TextureAtlas] from the given path. Currently, supports only JSON atlas files.
 *
 * @return the texture atlas
 */
suspend fun VfsFile.readAtlas(): TextureAtlas {
    val data = readString()
    val info =
        when {
            data.startsWith("{") -> {
                val page = vfs.json.decodeFromString<AtlasPage>(data)
                val pages = mutableListOf(page)
                page.meta.relatedMultiPacks.forEach {
                    pages += vfs.json.decodeFromString<AtlasPage>(parent[it].readString())
                }
                AtlasInfo(page.meta, pages)
            }
            data.startsWith('\n') -> TODO("Implement text atlas format")
            data.startsWith("\r\n") -> TODO("Implement text atlas format")
            else ->
                throw UnsupportedFileTypeException("This atlas format is not supported! ($path)")
        }

    val textures =
        info.pages.associate { it.meta.image to VfsFile(vfs, it.meta.image).readTexture() }
    return TextureAtlas(textures, info)
}

/**
 * Loads a [TtfFont] from the given path.
 *
 * @return the loaded ttf font.
 */
suspend fun VfsFile.readTtfFont(chars: String = CharacterSets.LATIN_ALL): TtfFont {
    val data = read()
    return TtfFont(chars).also { it.load(data) }
}

/**
 * Reads a bitmap font.
 *
 * @param preloadedTextures instead of loading a [Texture] when parsing the bitmap font, this will
 *   use an existing [TextureSlice]. This is useful if the bitmap font texture already exists in an
 *   atlas. Each slice in the list is considered a page in the bitmap font. Disposing a [BitmapFont]
 *   that uses preloaded textures will not dispose of the textures.
 */
suspend fun VfsFile.readBitmapFont(
    preloadedTextures: List<TextureSlice> = listOf(),
): BitmapFont {
    val data = readString()
    val textures = mutableMapOf<Int, Texture>()
    var pages = 0
    preloadedTextures.forEach { slice ->
        if (!textures.containsValue(slice.texture)) {
            textures[pages++] = slice.texture
        }
    }
    if (data.startsWith("info")) {
        return readBitmapFontTxt(
            data,
            this,
            textures,
            preloadedTextures,
            preloadedTextures.isEmpty()
        )
    } else {
        TODO("Unsupported font type.")
    }
}

private suspend fun readBitmapFontTxt(
    data: String,
    fontFile: VfsFile,
    textures: MutableMap<Int, Texture>,
    preloadedTextures: List<TextureSlice>,
    loadTextures: Boolean,
): BitmapFont {
    val kernings = mutableListOf<Kerning>()
    val glyphs = mutableListOf<BitmapFont.Glyph>()
    var lineHeight = 16f
    var fontSize = 16f
    var base: Float? = null
    var pages = 1
    val lines = data.split("\n")
    val padding = MutableVec4i(0)

    val capChars =
        charArrayOf(
            'M',
            'N',
            'B',
            'D',
            'C',
            'E',
            'F',
            'K',
            'A',
            'G',
            'H',
            'I',
            'J',
            'L',
            'O',
            'P',
            'Q',
            'R',
            'S',
            'T',
            'U',
            'V',
            'W',
            'X',
            'Y',
            'Z'
        )
    var capHeightFound = false
    var capHeight = 1

    lines.forEach { rline ->
        val line = rline.trim()
        val map = linkedMapOf<String, String>()

        line.split(' ').forEach {
            val (key, value) = it.split('=') + listOf("", "")
            map[key] = value
        }

        when {
            line.startsWith("info") -> {
                fontSize = map["size"]?.toFloat() ?: 16f
                map["padding"]?.let {
                    val nums = it.split(',')
                    padding.set(
                        nums[0].toIntOrNull() ?: 0,
                        nums[1].toIntOrNull() ?: 0,
                        nums[2].toIntOrNull() ?: 0,
                        nums[3].toIntOrNull() ?: 0,
                    )
                }
            }
            line.startsWith("page") -> {
                val id = map["id"]?.toInt() ?: 0
                val file = map["file"]?.unquote() ?: error("Page without file")
                if (loadTextures) {
                    textures[id] = fontFile.parent[file].readTexture()
                }
            }
            line.startsWith("common ") -> {
                lineHeight = map["lineHeight"]?.toFloatOrNull() ?: 16f
                base = map["base"]?.toFloatOrNull()
                pages = map["pages"]?.toIntOrNull() ?: 1
            }
            line.startsWith("char ") -> {
                val page = map["page"]?.toIntOrNull() ?: 0
                val id = map["id"]?.toIntOrNull() ?: 0
                val width = map["width"]?.toIntOrNull() ?: 0
                val height = map["height"]?.toIntOrNull() ?: 0

                if (!capHeightFound) {
                    if (capChars.contains(id.toChar())) {
                        capHeight = height
                        capHeightFound = true
                    } else if (width != 0 && height != 0) {
                        capHeight = max(capHeight, height)
                    }
                }
                val slice =
                    when {
                        loadTextures -> {
                            TextureSlice(
                                textures[page] ?: textures.values.first(),
                                map["x"]?.toIntOrNull() ?: 0,
                                map["y"]?.toIntOrNull() ?: 0,
                                width,
                                height
                            )
                        }
                        preloadedTextures.isNotEmpty() -> {
                            TextureSlice(
                                preloadedTextures[page],
                                map["x"]?.toIntOrNull() ?: 0,
                                map["y"]?.toIntOrNull() ?: 0,
                                width,
                                height
                            )
                        }
                        else -> {
                            throw IllegalStateException(
                                "Unable to load any textures for ${fontFile.baseName}. If they are preloaded, make sure to pass that in 'readBitmapFont()'."
                            )
                        }
                    }
                glyphs +=
                    BitmapFont.Glyph(
                        fontSize = fontSize,
                        id = id,
                        slice = slice,
                        xoffset = map["xoffset"]?.toIntOrNull() ?: 0,
                        yoffset = -(height + (map["yoffset"]?.toIntOrNull() ?: 0)),
                        xadvance = map["xadvance"]?.toIntOrNull() ?: 0,
                        width = width,
                        height = height,
                        page = page
                    )
            }
            line.startsWith("kerning ") -> {
                kernings +=
                    Kerning(
                        first = map["first"]?.toIntOrNull() ?: 0,
                        second = map["second"]?.toIntOrNull() ?: 0,
                        amount = map["amount"]?.toIntOrNull() ?: 0
                    )
            }
        }
    }

    capHeight -= padding.x + padding.z

    return BitmapFont(
        fontSize = fontSize,
        lineHeight = lineHeight,
        base = base ?: lineHeight,
        capHeight = capHeight.toFloat(),
        padding = FontMetrics.Padding(padding.x, padding.y, padding.z, padding.w),
        textures = textures.values.toList(),
        glyphs = glyphs.associateBy { it.id },
        kernings = kernings.associateBy { Kerning.buildKey(it.first, it.second) },
        pages = pages
    )
}

/**
 * Reads the [VfsFile] as a [LDtkMapLoader]. This will read the LDtk file and create a loader to
 * allow flexible loading of [LDtkWorld] or [LDtkLevel]. This loader should be cached and reused
 * when loading separate levels.
 *
 * @param atlas an atlas the has the preloaded textures for both tilesets and image layers.
 *   **Note**: that if the [Texture] for a tileset has a border thickness set, that value must be
 *   used for [tilesetBorder]. If no border is set, then [tilesetBorder] must be marked as `0`.
 * @param tilesetBorder the border thickness of each slice when loading the tileset to prevent
 *   bleeding. This is used when slicing tileset textures from an atlas or when loading externally.
 * @return the loaded LDtk map
 */
suspend fun VfsFile.readLDtkMapLoader(
    atlas: TextureAtlas? = null,
    tilesetBorder: Int = 2
): LDtkMapLoader {
    val mapData = decodeFromString<LDtkMapData>()
    return LDtkMapLoader(this, mapData, atlas, tilesetBorder)
}

/**
 * Reads the [VfsFile] as a [TiledMap]. Any loaders and assets will be cached for reuse/reloading.
 *
 * @param atlas an atlas the has the preloaded textures for both tilesets and image layers.
 *   **Note**: that if the [Texture] for a tileset has a border thickness set, that value must be
 *   used for [tilesetBorder]. If no border is set, then [tilesetBorder] must be marked as `0`.
 * @param tilesetBorder the border thickness of each slice when loading the tileset to prevent
 *   bleeding. This is used when slicing tileset textures from an atlas or when loading externally.
 * @return the loaded Tiled map
 */
suspend fun VfsFile.readTiledMap(
    atlas: TextureAtlas? = null,
    tilesetBorder: Int = 2,
    mipmaps: Boolean = true,
): TiledMap {
    val mapData = decodeFromString<TiledMapData>()
    val loader = TiledMapLoader(parent, mapData, atlas, tilesetBorder, mipmaps)
    return loader.loadMap()
}

/** Reads Base64 encoded ByteArray for embedded images. */
internal expect suspend fun ByteArray.readPixmap(): Pixmap

/**
 * Loads an image from the path as a [Pixmap].
 *
 * @return the loaded texture
 */
expect suspend fun VfsFile.readPixmap(): Pixmap

/**
 * Loads an image from the path as a [Texture].
 *
 * @return the loaded texture
 */
expect suspend fun VfsFile.readTexture(
    preferredFormat: TextureFormat = vfs.context.graphics.preferredFormat

): Texture

/**
 * Loads audio from the path as an [AudioClip].
 *
 * @return the loaded audio clip
 */
expect suspend fun VfsFile.readAudioClip(): AudioClip

/**
 * Streams audio from the path as an [AudioStream].
 *
 * @return a new [AudioStream]
 */
expect suspend fun VfsFile.readAudioStream(): AudioStream
