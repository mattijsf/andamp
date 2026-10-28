// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.Words
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app on a phone set to each language it is translated into: the right directory is read,
 * a count takes the form the language has for it, and a Preferences row's line is worded in
 * that language. `SimplifiedChineseTest` does the same for Chinese.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslatedLanguagesTest {
    private val words: Words get() = Words.of(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `English, the original`() {
        assertEquals("Options", words.get(R.string.menu_options))
        assertEquals("1 TRACK", words.plural(R.plurals.library_count_tracks, 1))
        assertEquals("2 TRACKS", words.plural(R.plurals.library_count_tracks, 2))
        assertEquals("Visualizer, refresh rate, size, taps and skin", words.get(R.string.prefs_widget_summary))
        assertEquals("Modern skins (beta)", words.get(R.string.prefs_modern_skins_title))
        assertEquals("Color themes", words.get(R.string.menu_color_themes))
        assertEquals("Add to Andamp", words.get(R.string.import_add_to_andamp))
    }

    @Test
    @Config(qualifiers = "nl")
    fun `Dutch is read from its directory`() {
        assertEquals("Opties", words.get(R.string.menu_options))
        assertEquals("Voorkeuren", words.get(R.string.prefs_title))
        assertEquals("1 NUMMER", words.plural(R.plurals.library_count_tracks, 1))
        assertEquals("2 NUMMERS", words.plural(R.plurals.library_count_tracks, 2))
        assertEquals("Visualisatie, verversingssnelheid, grootte, tikken en skin", words.get(R.string.prefs_widget_summary))
        assertEquals("Moderne skins (bèta)", words.get(R.string.prefs_modern_skins_title))
        assertEquals("Kleurthema’s", words.get(R.string.menu_color_themes))
        assertEquals("Toevoegen aan Andamp", words.get(R.string.import_add_to_andamp))
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
        assertEquals("Visualisierung, Aktualisierungsrate, Größe, Tippen und Skin", words.get(R.string.prefs_widget_summary))
        assertEquals("Moderne Skins (Beta)", words.get(R.string.prefs_modern_skins_title))
        assertEquals("Farbschemen", words.get(R.string.menu_color_themes))
        assertEquals("Zu Andamp hinzufügen", words.get(R.string.import_add_to_andamp))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `French is read from its directory`() {
        assertEquals("Égaliseur", words.get(R.string.menu_equalizer))
        assertEquals("Préférences", words.get(R.string.prefs_title))
        assertEquals("1 MORCEAU", words.plural(R.plurals.library_count_tracks, 1))
        assertEquals("2 MORCEAUX", words.plural(R.plurals.library_count_tracks, 2))
        assertEquals("Visualisation, actualisation, taille, appuis et skin", words.get(R.string.prefs_widget_summary))
        assertEquals("Skins modernes (bêta)", words.get(R.string.prefs_modern_skins_title))
        assertEquals("Thèmes", words.get(R.string.menu_color_themes))
        assertEquals("Ajouter à Andamp", words.get(R.string.import_add_to_andamp))
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `French as written in Canada is French`() {
        assertEquals("Préférences", words.get(R.string.prefs_title))
    }
}
