// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.presets

import nl.mattix.andamp.visualizer.avs.AvsOutline
import nl.mattix.andamp.visualizer.avs.AvsPresetCheck
import java.io.File

/**
 * What the preset selector's page about one preset shows: where the file is, what it is made
 * of, and what it asks for. Read from the file itself; what the engine could not show comes
 * from elsewhere.
 */
data class PresetDetails(
    val path: String,
    /** The name the lists show it by; see [PresetTitle]. */
    val name: String,
    /** The part of a Milkdrop name before " - ", which is by custom the author. */
    val author: String?,
    val pack: String?,
    /** The folder inside the pack; null at the pack's top. */
    val folder: String?,
    val fingerprint: PresetFingerprint?,
    val milk: Milk? = null,
    val avs: AvsOutline? = null,
) {
    /** A `.milk` file's contents, as far as the page shows them. */
    data class Milk(
        /** `MILKDROP_PRESET_VERSION`: 201 and up are MilkDrop 2 presets. */
        val version: Int?,
        /** `PSVERSION`, `PSVERSION_WARP`, `PSVERSION_COMP`: 0 is none, 2 and 3 are pixel shader models. */
        val shaderModel: Int?,
        val warpModel: Int?,
        val compModel: Int?,
        /** A few settings by their key in the file, in a fixed order, the ones the file sets. */
        val settings: List<Pair<String, String>>,
        val customWaves: Int,
        val customShapes: Int,
        /** Lines of each shader; 0 is none, and the engine's own is used. */
        val warpLines: Int,
        val compLines: Int,
        /** Lines of equations run once per frame and once per mesh point. */
        val perFrameLines: Int,
        val perPixelLines: Int,
        val textures: List<Texture>,
        /** The q variables the equations and shaders use, which carry values between them. */
        val qVariables: List<Int>,
    )

    /** A picture a shader samples, by the name it uses. */
    data class Texture(
        val name: String,
        /** Whether a file of that name is in the pack; null for one the engine makes. */
        val found: Boolean?,
    )

    companion object {
        /** Reads the preset at [path], placing it in one of [packs]; reads the whole file. */
        fun read(
            path: String,
            packs: List<InstalledPack>,
        ): PresetDetails {
            val file = File(path)
            val pack = packs.firstOrNull { path.startsWith(it.dir.absolutePath + File.separator) }
            val folder =
                pack
                    ?.let { file.parentFile?.relativeTo(it.dir)?.invariantSeparatorsPath }
                    ?.takeIf { it.isNotEmpty() }
            val milk = file.extension.equals("milk", ignoreCase = true)
            return PresetDetails(
                path = path,
                name = PresetTitle.of(path),
                author =
                    file.nameWithoutExtension
                        .substringBefore(" - ", "")
                        .trim()
                        .takeIf { milk && it.isNotEmpty() },
                pack = pack?.name,
                folder = folder,
                fingerprint = runCatching { PresetFingerprint.of(file) }.getOrNull(),
                milk = if (milk) runCatching { readMilk(file.readLines(), pack?.textureDirs.orEmpty()) }.getOrNull() else null,
                avs = if (milk) null else AvsPresetCheck.outline(path),
            )
        }

        /** The settings shown, by their key in the file. */
        val SETTINGS = listOf("fDecay", "fGammaAdj", "nWaveMode", "bAdditiveWaves", "bDarken", "fVideoEchoAlpha", "fRating")

        internal fun readMilk(
            lines: List<String>,
            textureDirs: List<File>,
        ): Milk {
            val values = LinkedHashMap<String, String>()
            lines.forEach { line ->
                val at = line.indexOf('=')
                if (at > 0) values[line.substring(0, at).trim()] = line.substring(at + 1)
            }

            fun count(prefix: String) =
                values.keys.count { key ->
                    key.startsWith(prefix) &&
                        key.substring(prefix.length).all(Char::isDigit)
                }

            fun enabled(kind: String) =
                values.count { (key, value) -> ENABLED.matchEntire(key)?.groupValues?.get(1) == kind && value.trim() == "1" }
            val shaders = values.filterKeys { SHADER_LINE.matches(it) }.values
            val code = values.filterKeys { CODE_LINE.matches(it) }.values + shaders
            val pictures = textureFiles(textureDirs)
            return Milk(
                version = values["MILKDROP_PRESET_VERSION"]?.trim()?.toIntOrNull(),
                shaderModel = values["PSVERSION"]?.trim()?.toIntOrNull(),
                warpModel = values["PSVERSION_WARP"]?.trim()?.toIntOrNull(),
                compModel = values["PSVERSION_COMP"]?.trim()?.toIntOrNull(),
                settings = SETTINGS.mapNotNull { key -> values[key]?.trim()?.let { key to it } },
                customWaves = enabled("wave"),
                customShapes = enabled("shape"),
                warpLines = count("warp_"),
                compLines = count("comp_"),
                perFrameLines = count("per_frame_"),
                perPixelLines = count("per_pixel_"),
                textures =
                    shaders
                        .flatMap { SAMPLER.findAll(it).map { match -> plainSampler(match.groupValues[1]) } }
                        .distinct()
                        .filter { it !in ENGINE_SAMPLERS }
                        .map { name -> Texture(name, if (name.startsWith("rand")) null else name.lowercase() in pictures) },
                qVariables =
                    code
                        .flatMap { Q_VARIABLE.findAll(it).map { match -> match.groupValues[1].toInt() } }
                        .filter { it in 1..Q_COUNT }
                        .distinct()
                        .sorted(),
            )
        }

        /** The picture files' names without extension, lower case, in [dirs]. */
        private fun textureFiles(dirs: List<File>): Set<String> =
            dirs
                .flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in PICTURES } }
                .mapTo(HashSet()) { it.nameWithoutExtension.lowercase() }

        /** A sampler's texture: `sampler_fw_clouds` samples `clouds`, filtered and wrapped. */
        private fun plainSampler(name: String): String = MODE_PREFIX.find(name)?.let { name.substring(it.value.length) } ?: name

        private val ENABLED = Regex("""(wave|shape)code_\d+_enabled""")
        private val SHADER_LINE = Regex("""(warp|comp)_\d+""")
        private val CODE_LINE =
            Regex("""(per_frame|per_frame_init|per_pixel)_\d+|(wave|shape)_\d+_(per_frame|per_point|init)\d*""")
        private val SAMPLER = Regex("""\bsampler_([A-Za-z0-9_]+)""")
        private val MODE_PREFIX = Regex("""^(fw|fc|pw|pc)_""")
        private val Q_VARIABLE = Regex("""\bq(\d{1,2})\b""")
        private const val Q_COUNT = 64

        /** What the engine draws into or makes itself: the frame, its blurs and noise. */
        private val ENGINE_SAMPLERS =
            setOf(
                "main",
                "blur1",
                "blur2",
                "blur3",
                "noise_lq",
                "noise_lq_lite",
                "noise_mq",
                "noise_hq",
                "noisevol_lq",
                "noisevol_hq",
            )

        private val PICTURES = setOf("jpg", "jpeg", "png", "tga", "bmp", "dds")
    }
}
