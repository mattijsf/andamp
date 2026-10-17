// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.presets

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.VisPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresetFavoritesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun pack(
        name: String,
        vararg files: String,
    ): InstalledPack {
        val dir = File(temp.root, name)
        files.forEach { File(dir, it).apply { parentFile?.mkdirs() }.writeText("x") }
        return InstalledPack(name = name, dir = dir, presetCount = files.size, textureDirs = emptyList())
    }

    @Test
    fun `a favorite is filed by its pack and its place in it`() {
        val packs = listOf(pack("Cream", "Nested/a.milk"))

        val favorite = Favorite.of(VisPlugin.Milkdrop, File(temp.root, "Cream/Nested/a.milk"), packs)

        assertEquals(Favorite(VisPlugin.Milkdrop, "Cream", "Nested/a.milk"), favorite)
        assertEquals(File(temp.root, "Cream/Nested/a.milk"), favorite?.file(packs))
    }

    @Test
    fun `a file outside every pack is no favorite`() {
        assertNull(Favorite.of(VisPlugin.Avs, File(temp.root, "loose.avs"), listOf(pack("p", "a.avs"))))
    }

    @Test
    fun `a favorite ends with its pack, or with its file`() {
        val kept = pack("kept", "a.milk", "b.milk")
        val favorites =
            listOf(
                Favorite(VisPlugin.Milkdrop, "kept", "a.milk"),
                Favorite(VisPlugin.Milkdrop, "gone", "x.milk"),
                Favorite(VisPlugin.Milkdrop, "kept", "deleted.milk"),
            )

        assertEquals(listOf(favorites.first()), favorites.stillThere(listOf(kept)))
    }

    @Test
    fun `favorites come back from storage as they were saved`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val favorites =
            listOf(
                Favorite(VisPlugin.Avs, "AndAmp", "Rings.avs"),
                Favorite(VisPlugin.Milkdrop, "Cream of the Crop", "Dancer/Jc - Crystal Shards.milk"),
            )

        FavoriteStore(context).save(favorites)

        assertEquals(favorites, FavoriteStore(context).load())
    }
}
