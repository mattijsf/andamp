// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.Words
import nl.mattix.andamp.ui.prefs.WidgetPrefs
import nl.mattix.andamp.ui.prefs.summary
import nl.mattix.andamp.widget.WidgetRefresh
import nl.mattix.andamp.widget.WidgetSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app on a phone set to each language it is translated into: the right directory is read,
 * a count takes the form the language has for it, and a line built from parts reads as the
 * language writes it. `SimplifiedChineseTest` does the same for Chinese.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslatedLanguagesTest {
    private val words: Words get() = Words.of(ApplicationProvider.getApplicationContext<Context>())

    private val widget = WidgetPrefs(WidgetSettings(mode = VisMode.Analyzer, refresh = WidgetRefresh.MEDIUM))

    @Test
    fun `English, the original`() {
        assertEquals("Options", words.get(R.string.menu_options))
        assertEquals("1 TRACK", words.plural(R.plurals.library_count_tracks, 1))
        assertEquals("2 TRACKS", words.plural(R.plurals.library_count_tracks, 2))
        assertEquals("analyzer, medium refresh rate", widget.summary(words))
        assertEquals("Modern skins (beta)", words.get(R.string.prefs_modern_skins_title))
        assertEquals("Color themes", words.get(R.string.menu_color_themes))
    }

    @Test
    @Config(qualifiers = "nl")
    fun `Dutch is read from its directory`() {
        assertEquals("Opties", words.get(R.string.menu_options))
        assertEquals("Voorkeuren", words.get(R.string.prefs_title))
        assertEquals("1 NUMMER", words.plural(R.plurals.library_count_tracks, 1))
        assertEquals("2 NUMMERS", words.plural(R.plurals.library_count_tracks, 2))
        assertEquals("Analyse, verversing: gemiddeld", widget.summary(words))
        assertEquals("Moderne skins (bèta)", words.get(R.string.prefs_modern_skins_title))
        assertEquals("Kleurthema’s", words.get(R.string.menu_color_themes))
    }

    @Test
    @Config(qualifiers = "nl-rBE")
    fun `Dutch as written in Belgium is Dutch`() {
        assertEquals("Opties", words.get(R.string.menu_options))
    }

    @Test
    @Config(qualifiers = "de")
    fun `German is read from its directory`() {
        assertEquals("Optionen", words.get(R.string.menu_options))
        assertEquals("Einstellungen", words.get(R.string.prefs_title))
        assertEquals("1 TITEL", words.plural(R.plurals.library_count_tracks, 1))
        // a noun keeps its capital inside the line, which is why the line gets its words from resources for that place
        assertEquals("Analysator, Aktualisierungsrate: mittel", widget.summary(words))
        assertEquals("Moderne Skins (Beta)", words.get(R.string.prefs_modern_skins_title))
        assertEquals("Farbschemen", words.get(R.string.menu_color_themes))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `French is read from its directory`() {
        assertEquals("Égaliseur", words.get(R.string.menu_equalizer))
        assertEquals("Préférences", words.get(R.string.prefs_title))
        assertEquals("1 MORCEAU", words.plural(R.plurals.library_count_tracks, 1))
        assertEquals("2 MORCEAUX", words.plural(R.plurals.library_count_tracks, 2))
        // the adjective agrees with "fréquence"
        assertEquals("Analyseur, fréquence d’actualisation moyenne", widget.summary(words))
        assertEquals("Skins modernes (bêta)", words.get(R.string.prefs_modern_skins_title))
        assertEquals("Thèmes", words.get(R.string.menu_color_themes))
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `French as written in Canada is French`() {
        assertEquals("Préférences", words.get(R.string.prefs_title))
    }
}
