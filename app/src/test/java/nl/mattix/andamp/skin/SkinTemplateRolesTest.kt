// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The color math agrees with the build that wrote the template.
 *
 * `build.py template` writes every role resolved against the palette the theme
 * shipped. With the same palette bound here, each of the 110 roles has to land
 * on the same hex. No bitmap is decoded, so a divergence in a blend or a tone
 * shows up as a wrong number with a role's name on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkinTemplateRolesTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun hex(c: Int) = "#%06X".format(c)

    private fun check(
        theme: String,
        repair: Boolean,
    ) {
        val template = SkinDist.template(app, theme)
        val shipped = SkinDist.roles(theme)
        val resolved = template.colors(SkinDist.baselineScheme(theme), repair = repair)

        val wrong =
            template.roleNames.withIndex().filter { (i, role) -> resolved[i] != shipped.getValue(role) }.map { (i, role) ->
                "$role: got ${hex(resolved[i])}, build wrote ${hex(shipped.getValue(role))}"
            }
        assertEquals(
            "$theme, repair=$repair, resolves every role as the build did:\n" + wrong.joinToString("\n"),
            emptyList<String>(),
            wrong,
        )
    }

    @Test
    fun `the dark template resolved against its own palette is what the build wrote`() = check("dark", repair = false)

    @Test
    fun `the light template resolved against its own palette is what the build wrote`() = check("light", repair = false)

    /** The shipped palettes clear every pair, so repair must have nothing to do. */
    @Test
    fun `repair leaves a palette that already passes alone`() {
        check("dark", repair = true)
        check("light", repair = true)
    }

    @Test
    fun `the template names every role the build resolved, and no other`() {
        SkinDist.THEMES.forEach { theme ->
            assertEquals(
                "$theme names every role the build resolved",
                SkinDist.roles(theme).keys.sorted(),
                SkinDist.template(app, theme).roleNames.sorted(),
            )
        }
    }

    @Test
    fun `the inputs are the Material roles the template reads`() {
        val template = SkinDist.template(app, "dark")
        val inputs = template.inputs(SkinDist.baselineScheme("dark"))
        val shipped = SkinDist.roles("dark")

        assertEquals(SkinTemplate.M3_ROLES.size, inputs.size)
        SkinTemplate.M3_ROLES.forEachIndexed { i, role ->
            assertTrue("$role is in the build's table", role in shipped)
            assertEquals("$role is the input the build used", shipped.getValue(role), inputs[i])
        }
    }
}
