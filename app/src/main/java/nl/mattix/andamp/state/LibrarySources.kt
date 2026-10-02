// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which source the library window shows, and whether it can show it.
 *
 * The window's source is picked from the main menu and is independent of playback. Every
 * source beyond the phone needs an account on this device before it has anything to show.
 * Held as snapshot state, so the menu's tick and the window's shelves follow a pick or a
 * sign-in.
 */
class LibrarySources(
    private val store: LibrarySourceStore,
    present: List<MusicSource>,
    signedIn: Set<MusicSource>,
    /** Told what can be reached whenever that changes, and once at construction. */
    private val onReach: (SourceReach) -> Unit = {},
) {
    private var signed by mutableStateOf(signedIn)

    /**
     * What this install can reach; see [MusicSource.present]. It changes while the app
     * runs, because a source's app can be installed or uninstalled.
     */
    var present by mutableStateOf(present)
        private set

    /** The sources this install has, and which of them have an account here. */
    val reach: SourceReach get() = SourceReach(present, signed)

    init {
        onReach(reach)
    }

    /**
     * The source the window shows: the one picked, or the phone when the picked one cannot
     * be shown (signed out, or its app gone). The pick is kept for when it can.
     */
    val showing: MusicSource get() = present.firstOrNull { it.id == store.id }?.takeIf(::usable) ?: MusicSource.LOCAL

    /** Main menu > Media Library > [wanted], with the window [open] or not. */
    fun pick(
        wanted: MusicSource,
        open: Boolean,
    ): Pick =
        when {
            wanted in present && reach.absence(wanted) == SourceAbsence.SIGNED_OUT -> {
                Pick.SignInFirst
            }

            !usable(wanted) -> {
                Pick.Nothing
            }

            // the entry is ticked, and a ticked window entry closes its window
            open && wanted == showing -> {
                Pick.Close
            }

            else -> {
                store.id = wanted.id
                Pick.Show
            }
        }

    /** The app behind [source] is on this phone; it is added after the ones already found. */
    fun installed(source: MusicSource) {
        if (source in present) return
        present = present + source
        onReach(reach)
    }

    /** The app behind [source] has gone; it is also removed from the signed-in set. */
    fun uninstalled(source: MusicSource) {
        if (source !in present) return
        present = present - source
        signed = signed - source
        onReach(reach)
    }

    /** Signed in to [source]: it also becomes the library window's pick. */
    fun signedIn(source: MusicSource) {
        signed = signed + source
        store.id = source.id
        onReach(reach)
    }

    /** Signed out of [source]. */
    fun signedOut(source: MusicSource) {
        signed = signed - source
        onReach(reach)
    }

    private fun usable(source: MusicSource) = source in present && reach.absence(source) == null

    /** What a pick asks of the window. */
    enum class Pick {
        /** Open on the picked source, or move an open window onto it. */
        Show,

        /** It is already showing that source: close, as a ticked entry does. */
        Close,

        /** Nothing to show until there is an account; Preferences has the sign-in. */
        SignInFirst,

        /** A source this install does not have: nothing happens. */
        Nothing,
    }
}
