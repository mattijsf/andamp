// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.Intent
import nl.mattix.andamp.core.model.Track

/**
 * What to tell the listener about a source in the playlist that cannot play, and the
 * action that fixes it.
 *
 * A row that cannot play is labeled on itself (`[Missing source: …]`) and is skipped, as
 * Winamp skips a missing file. A prompt is shown only when a press finds nothing to play:
 *
 * - a source whose app is not installed leads to the page it is handed out on, which the
 *   playlist records ([PlaylistCodec.Saved.sources]);
 * - a source that is installed and signed out leads to its own settings, where the sign-in
 *   is.
 *
 * [of] prompts once per source per run of the app; [forRow] prompts every time, for a row
 * the listener pressed. What has been said is kept in memory only, so it is said again in
 * the next run.
 */
class SourcePrompts(
    /** Where each source's app is handed out, by source id; see [PlaylistCodec.Saved.sources]. */
    private val pages: () -> Map<String, String>,
    /** Where to send the listener when the playlist records no page; see `MORE_SOURCES_URL`. */
    private val elsewhere: String,
    private val open: (String) -> Unit,
    private val settings: (MusicSource) -> Unit,
) {
    private val said = mutableSetOf<MusicSource>()

    /**
     * The prompt for the first source in [queue] that cannot play and has not been
     * prompted about, or null when there is none or [reach] is unknown. The caller shows
     * it.
     */
    fun of(
        queue: List<Track>,
        reach: SourceReach?,
        onDone: () -> Unit,
    ): AmpPrompt? {
        val known = reach ?: return null
        val source = queue.map { SourceForRows.of(it) }.firstOrNull { it !in said && known.absence(it) != null } ?: return null
        said += source
        return words(source, known, onDone)
    }

    /**
     * The prompt for a row the listener pressed, or null when its source can play.
     * Answered every time, unlike [of]. The player still skips the row.
     */
    fun forRow(
        track: Track,
        reach: SourceReach?,
        onDone: () -> Unit,
    ): AmpPrompt? {
        val known = reach ?: return null
        return words(SourceForRows.of(track), known, onDone)
    }

    /**
     * Names what is missing, with a button that leads to where it is fixed. The text gives
     * no row counts and does not use the word "pack".
     */
    private fun words(
        source: MusicSource,
        reach: SourceReach,
        onDone: () -> Unit,
    ): AmpPrompt? {
        val named = reach.named(source).label
        return when (reach.absence(source)) {
            SourceAbsence.MISSING -> {
                AmpPrompt(
                    title = "$named is not set up",
                    body = "Set up $named to play its tracks in Andamp. Until then they are skipped.",
                    confirmLabel = "Set up $named",
                    dismissLabel = "Close",
                    // the page the playlist records, or the app's own list of sources when
                    // it records none
                    onConfirm = {
                        onDone()
                        open(pages()[source.id] ?: elsewhere)
                    },
                )
            }

            SourceAbsence.SIGNED_OUT -> {
                AmpPrompt(
                    title = "$named is signed out",
                    body = "Sign in to play your $named tracks in Andamp. Until then they are skipped.",
                    // the sign-in is on the source's own screen
                    confirmLabel = "Sign in",
                    dismissLabel = "Close",
                    onConfirm = {
                        onDone()
                        settings(source)
                    },
                )
            }

            null -> {
                null
            }
        }
    }
}

/**
 * Starts something outside this app: a page in a browser, or a source's own screen.
 *
 * It is called with a non-activity context, so the intent carries
 * `FLAG_ACTIVITY_NEW_TASK`. A failure to start (no browser, an app that has gone) is
 * ignored.
 */
internal fun Context.openOutside(what: Intent) {
    runCatching { startActivity(what.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
