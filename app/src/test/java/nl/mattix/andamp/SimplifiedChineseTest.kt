// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.Words
import nl.mattix.andamp.ui.menu.mainMenu
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app on a phone set to Simplified Chinese: the strings under `values-zh-rCN` are the ones
 * read, through the same two doors as the English ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN")
class SimplifiedChineseTest {
    private val words = Words.of(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `a string is read in Chinese`() {
        assertEquals("选项", words.get(R.string.menu_options))
    }

    @Test
    fun `the main menu is worded in Chinese`() {
        val labels =
            mainMenu(testViewModel(), {}, {}, {}, {}).items.mapNotNull { item ->
                when (item) {
                    is nl.mattix.andamp.state.AmpMenuItem.Action -> item.label
                    is nl.mattix.andamp.state.AmpMenuItem.Submenu -> item.label
                    else -> null
                }
            }

        assertTrue("$labels", labels.containsAll(listOf("均衡器", "选项", "可视化效果", "皮肤", "退出")))
    }

    @Test
    fun `a count has one form, with the number grouped`() {
        assertEquals("1 首曲目", words.plural(R.plurals.library_count_tracks, 1))
        assertEquals("1,234 首曲目", words.plural(R.plurals.phone_count_tracks, 1234))
    }

    @Test
    fun `placeholders may come in another order than in English`() {
        // English: "12 in Pack, in the order the engine plays them."
        assertEquals("Pack 中的 12 个，按引擎播放的顺序排列。", words.get(R.string.prefs_milkdrop_presets_in_pack, 12, "Pack"))
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `a phone set to Traditional Chinese is not handed Simplified text`() {
        assertEquals("Options", words.get(R.string.menu_options))
    }

    // Esperanto: pick another language here if somebody translates Andamp into it
    @Test
    @Config(qualifiers = "eo")
    fun `a language with no translation gets the English`() {
        assertEquals("Options", words.get(R.string.menu_options))
    }
}
