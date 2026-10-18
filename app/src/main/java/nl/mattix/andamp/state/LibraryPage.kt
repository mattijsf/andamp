// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track

/** A level of the media library's drill-down, replaced as one piece when its fetch lands. */
internal class LibraryPage(
    val header: String,
    val status: String,
    val rows: List<LibraryOps.Row>,
    /** Drill actions, indexed like [rows]; empty at the leaf. */
    val open: List<() -> Unit> = emptyList(),
    /**
     * The tracks each row stands for, indexed like [rows], for a row's menu, which
     * acts on a row without opening it. For an artist or an album this is a fetch.
     * Empty where the leaf's own [tracks] answer it.
     */
    val tracksOf: List<suspend () -> List<Track>> = emptyList(),
    /**
     * The saved list each row stands for, indexed like [rows], on the page of the
     * lists this app keeps as files; empty on every other page. Only these rows can
     * be deleted.
     */
    val saved: List<String> = emptyList(),
    /** The saved list whose tracks this page shows; null on every other page. */
    val savedName: String? = null,
    /** The playable tracks, when this page is a leaf. */
    val tracks: List<Track>? = null,
    /** The text an empty page shows. */
    val empty: String? = null,
    /**
     * Asks again, on a page for a fetch the source could not answer; null on every
     * other page. When it is set, a tap anywhere on the page, or on its own tab,
     * retries.
     */
    val retry: (() -> Unit)? = null,
    /** The source stopped part way through this page's question; see [LibraryShelves]. */
    val incomplete: Boolean = false,
)
