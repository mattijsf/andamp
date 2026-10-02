// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * What the skin build in `skin/` publishes, for tests that hold the Kotlin
 * side to it.
 *
 * `skin/dist/andamp-<theme>-roles.json` is the role table resolved against the
 * palette that theme shipped, written by `build.py template`. The templates
 * and the `.wsz` files are read from the app's own assets, where the phone
 * reads them.
 *
 * `skin/dist` is committed, so these tests fail without it and do not skip.
 */
object SkinDist {
    /** The build's output, reached from the module directory the tests run in. */
    fun file(name: String): File = File(System.getProperty("user.dir") ?: ".", "../skin/dist/$name")

    /** Every role of [theme] as the build resolved it, packed 0xRRGGBB. */
    fun roles(theme: String): Map<String, Int> {
        val json = JSONObject(file("andamp-$theme-roles.json").readText())
        return json.keys().asSequence().associateWith { json.getString(it).removePrefix("#").toInt(16) }
    }

    /**
     * The Material 3 scheme the shipped [theme] was built from, so the template
     * can be bound to it here and compared with what the build wrote.
     */
    fun baselineScheme(theme: String): ColorScheme {
        val roles = roles(theme)
        return scheme(dark = theme != "light") { role -> roles.getValue(role) }
    }

    /**
     * A [ColorScheme] with the 36 roles the template reads set from [colour].
     * The rest keep Material's defaults for the scheme's own darkness, which the
     * template never looks at.
     */
    fun scheme(
        dark: Boolean,
        colour: (String) -> Int,
    ): ColorScheme {
        fun c(role: String) = Color(argb(colour(role)))
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = c("primary"),
            onPrimary = c("on_primary"),
            primaryContainer = c("primary_container"),
            onPrimaryContainer = c("on_primary_container"),
            inversePrimary = c("inverse_primary"),
            primaryFixed = c("primary_fixed"),
            primaryFixedDim = c("primary_fixed_dim"),
            onPrimaryFixed = c("on_primary_fixed"),
            onPrimaryFixedVariant = c("on_primary_fixed_variant"),
            secondary = c("secondary"),
            onSecondary = c("on_secondary"),
            secondaryContainer = c("secondary_container"),
            onSecondaryContainer = c("on_secondary_container"),
            tertiary = c("tertiary"),
            onTertiary = c("on_tertiary"),
            tertiaryContainer = c("tertiary_container"),
            onTertiaryContainer = c("on_tertiary_container"),
            error = c("error"),
            onError = c("on_error"),
            errorContainer = c("error_container"),
            onErrorContainer = c("on_error_container"),
            surface = c("surface"),
            onSurface = c("on_surface"),
            surfaceVariant = c("surface_variant"),
            onSurfaceVariant = c("on_surface_variant"),
            surfaceDim = c("surface_dim"),
            surfaceBright = c("surface_bright"),
            surfaceContainerLowest = c("surface_container_lowest"),
            surfaceContainerLow = c("surface_container_low"),
            surfaceContainer = c("surface_container"),
            surfaceContainerHigh = c("surface_container_high"),
            surfaceContainerHighest = c("surface_container_highest"),
            outline = c("outline"),
            outlineVariant = c("outline_variant"),
            inverseSurface = c("inverse_surface"),
            inverseOnSurface = c("inverse_on_surface"),
        )
    }

    /** The template the app ships for [theme]. */
    fun template(
        context: Context,
        theme: String,
    ): SkinTemplate = context.assets.open("skins/andamp-$theme-template.zip").use(SkinTemplate::load)

    /** The members of the `.wsz` the app ships for [theme], by file name. */
    fun wsz(
        context: Context,
        theme: String,
    ): Map<String, ByteArray> =
        ZipInputStream(context.assets.open("skins/AndAmp ${theme.replaceFirstChar { it.uppercase() }}.wsz")).use {
            it.members()
        }

    private fun ZipInputStream.members(): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        while (true) {
            val entry = nextEntry ?: break
            if (!entry.isDirectory) out[entry.name] = readBytes()
        }
        return out
    }

    val THEMES = listOf("dark", "light")
}
