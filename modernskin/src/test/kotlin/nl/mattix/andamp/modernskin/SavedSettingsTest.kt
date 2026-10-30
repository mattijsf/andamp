// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.ConfigAttributeObj
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SavedSettings
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a skin keeps between runs moves in and out as one value: [SkinRuntime.restoreSettings]
 * before the skin loads, [SkinRuntime.savedSettings] whenever the host saves.
 */
class SavedSettingsTest {
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="40"/></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val rt = SkinRuntime(files, TestHost(files), FakePlayer())

    private val saved =
        SavedSettings(
            attributes = mapOf("Animate Config Drawer" to "0"),
            strings = mapOf("bento/component" to "Video"),
            ints = mapOf("bento/width" to 412),
        )

    @Test
    fun `a new skin keeps nothing`() {
        assertEquals(SavedSettings(), rt.savedSettings())
    }

    @Test
    fun `what is put back before load comes out again, and counts as no change`() {
        rt.restoreSettings(saved)
        rt.load()
        assertEquals(saved, rt.savedSettings())
        assertEquals(0, rt.settingsVersion)
    }

    @Test
    fun `a stored attribute wins over the default a script makes it with`() {
        rt.restoreSettings(saved)
        rt.load()
        val item = checkNotNull(rt.configItem("Options", "{0F7A6C2E-0000-4C8F-9C2A-000000000001}"))
        val made = item.makiCall("newattribute", listOf(Value.Str("Animate Config Drawer"), Value.Str("1")))
        assertEquals("0", ((made as Value.Obj).o as ConfigAttributeObj).data)
    }

    @Test
    fun `a saved value is a copy that later changes leave as it was`() {
        rt.restoreSettings(saved)
        rt.load()
        val before = rt.savedSettings()
        rt.storePrivate(rt.privateStrings, "bento/component", "Playlist")

        assertEquals("Video", before.strings["bento/component"])
        assertEquals("Playlist", rt.savedSettings().strings["bento/component"])
        assertTrue("a script's change counts", rt.settingsVersion > 0)
    }
}
