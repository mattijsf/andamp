// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.EqOps
import nl.mattix.andamp.state.InMemoryEqPresetStore
import nl.mattix.andamp.state.RecordingEqBackend
import nl.mattix.andamp.state.WinampState
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
        assertEquals("Color Themes", words.get(R.string.menu_color_themes))
        assertEquals("Add to Andamp", words.get(R.string.import_add_to_andamp))
        assertEquals("Auto-load preset", words.get(R.string.eq_menu_auto_load_load))
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
    @Config(qualifiers = "nl")
    fun `the Dutch menus use the words of Winamp's Dutch translation`() {
        assertEquals("Spring naar bestand...", words.get(R.string.menu_jump_to_file))
        assertEquals("Spring naar tijd", words.get(R.string.prompt_jump_to_time_title))
        assertEquals("Springen", words.get(R.string.prompt_jump_to_time_confirm))
        assertEquals("Altijd bovenop", words.get(R.string.menu_always_on_top))
        assertEquals("Shuffle", words.get(R.string.menu_shuffle))
        assertEquals("Lijst willekeurig maken", words.get(R.string.menu_randomize_list))
        assertEquals(
            "2 favorieten, uit elk pakket. Volgende en Shuffle blijven daarbinnen.",
            words.plural(R.plurals.prefs_milkdrop_favorites_count, 2),
        )
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
    @Config(qualifiers = "de")
    fun `the German menus use the words of Winamp's German translation`() {
        assertEquals("Springe zu Zeitmarke...", words.get(R.string.menu_jump_to_time))
        assertEquals("Zufallsmodus", words.get(R.string.menu_shuffle))
        assertEquals("Liste mischen", words.get(R.string.menu_randomize_list))
        assertEquals("Titelinformationen anzeigen", words.get(R.string.menu_view_file_info))
        assertEquals("Automatische Voreinstellung", words.get(R.string.eq_auto_load_preset))
        assertEquals("Automatische Voreinstellung...", words.get(R.string.eq_menu_auto_load_delete))
        assertEquals("Voreinstellungen automatisch laden", words.get(R.string.eq_menu_auto_load_load))
        assertEquals(
            "2 Favoriten, aus jedem Paket. Weiter und Zufallsmodus bleiben darin.",
            words.plural(R.plurals.prefs_milkdrop_favorites_count, 2),
        )
    }

    @Test
    @Config(qualifiers = "de")
    fun `the German equalizer menu words each auto-load entry as Winamp does`() {
        val ops = EqOps(WinampState(), PlayerFacade(RecordingEqBackend()), InMemoryEqPresetStore(), words)
        val labels =
            ops.presetsMenu().items.filterIsInstance<AmpMenuItem.Submenu>().associate { sub ->
                sub.label to sub.items.filterIsInstance<AmpMenuItem.Action>().map { it.label }
            }

        assertEquals(listOf("Voreinstellung...", "Voreinstellungen automatisch laden", "Standard"), labels["Laden"])
        assertEquals(listOf("Voreinstellung...", "Automatische Voreinstellung", "Standard"), labels["Speichern"])
        assertEquals(listOf("Voreinstellung...", "Automatische Voreinstellung..."), labels["Löschen"])
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
    @Config(qualifiers = "fr")
    fun `the French menus use the words of Winamp's French translation`() {
        assertEquals("Mettre à la suite", words.get(R.string.menu_enqueue))
        assertEquals("+3 À LA SUITE", words.get(R.string.library_queued, 3))
        assertEquals("Liste de lecture", words.get(R.string.menu_playlist_editor))
        assertEquals("Toujours visible", words.get(R.string.menu_always_on_top))
        assertEquals("Lecture en boucle", words.get(R.string.menu_repeat))
        assertEquals("Aller au morceau...", words.get(R.string.menu_jump_to_file))
        assertEquals("Aller à un moment précis...", words.get(R.string.menu_jump_to_time))
        assertEquals("Valider", words.get(R.string.prompt_jump_to_time_confirm))
        assertEquals("Liste des skins...", words.get(R.string.menu_skin_browser))
        assertEquals("Plug-ins", words.get(R.string.prefs_page_plugins))
        assertEquals("Informations sur le fichier", words.get(R.string.menu_view_file_info))
        assertEquals("Informations sur le fichier", words.get(R.string.menu_file_info))
        assertEquals(
            "2 favoris, de n’importe quel pack. Suivant et Lecture aléatoire restent parmi eux.",
            words.plural(R.plurals.prefs_milkdrop_favorites_count, 2),
        )
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `French as written in Canada is French`() {
        assertEquals("Préférences", words.get(R.string.prefs_title))
    }
}
