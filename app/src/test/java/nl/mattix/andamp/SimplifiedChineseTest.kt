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
import java.io.File

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

        assertTrue("$labels", labels.containsAll(listOf("均衡器", "选项", "可视化效果", "面板", "退出")))
    }

    @Test
    fun `modern skins are worded in Chinese`() {
        assertEquals("现代面板（测试版）", words.get(R.string.prefs_modern_skins_title))
        assertEquals("颜色主题", words.get(R.string.menu_color_themes))
        assertEquals("Bento · 现代面板", words.get(R.string.skin_row_modern, "Bento"))
    }

    @Test
    fun `a skin is 面板, as Winamp's Chinese translation calls it, in every string`() {
        assertEquals("面板浏览器...", words.get(R.string.menu_skin_browser))
        assertEquals("<< 获得更多面板！ >>", words.get(R.string.menu_get_more_skins))
        val root = repositoryRoot()
        val dirs = chineseValueDirs(root)
        // a scan that found neither the app's nor Android Auto's Chinese text would pass over nothing
        val modules = dirs.map { it.relativeTo(root).invariantSeparatorsPath.substringBefore("/src/") }
        assertTrue("$modules", modules.containsAll(listOf("app", "androidauto")))
        val older =
            dirs
                .flatMap { it.listFiles().orEmpty().toList() }
                .flatMap { file ->
                    file.readLines().filter { "皮肤" in it }.map { "${file.relativeTo(root)}: ${it.trim()}" }
                }
        assertEquals(emptyList<String>(), older)
    }

    @Test
    fun `loading a skin is 加载, as in Winamp's Chinese translation`() {
        assertEquals("加载面板...", words.get(R.string.menu_load_skin))
        assertEquals("此现代面板未能加载，因此没有添加。", words.get(R.string.skin_modern_failed_body))
    }

    @Test
    fun `the offer turns modern skins on rather than opening a file`() {
        // 打开 with an object reads as opening a file, as in skin_modern_failed_title
        assertEquals("要开启现代面板吗？", words.get(R.string.skin_modern_offer_title))
        assertEquals("开启并添加", words.get(R.string.skin_modern_offer_accept))
    }

    @Test
    fun `another app offers to add a skin or a plug-in to Andamp in Chinese`() {
        assertEquals("添加到 Andamp", words.get(R.string.import_add_to_andamp))
    }

    @Test
    fun `a count has one form, with the number grouped`() {
        assertEquals("1 首曲目", words.plural(R.plurals.library_count_tracks, 1))
        assertEquals("1,234 首曲目", words.plural(R.plurals.phone_count_tracks, 1234))
    }

    @Test
    fun `placeholders may come in another order than in English`() {
        // English: "12 in Pack, in the order the engine plays them."
        assertEquals("Pack 中的 12 个预设。点按星标可将预设加入收藏。", words.plural(R.plurals.prefs_milkdrop_pack_count, 12, "Pack"))
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

    /** The checkout's top directory, wherever the test was run from. */
    private fun repositoryRoot(): File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { it.resolve("app/src/main/res").isDirectory }
            ?: error("no app/src/main/res above ${File(".").absolutePath}")

    /**
     * The `res/values-zh-rCN` directory of every source set of every module that
     * `settings.gradle.kts` includes, so that strings, plurals and string arrays are all read.
     */
    private fun chineseValueDirs(root: File): List<File> =
        Regex("""include\("(:[^"]+)"\)""")
            .findAll(root.resolve("settings.gradle.kts").readText())
            .map { root.resolve(it.groupValues[1].removePrefix(":").replace(':', '/')) }
            .flatMap { module ->
                module
                    .resolve("src")
                    .listFiles()
                    .orEmpty()
                    .asSequence()
            }.map { it.resolve("res/values-zh-rCN") }
            .filter { it.isDirectory }
            .toList()
}
