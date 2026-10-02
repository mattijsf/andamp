// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.PlaybackBackend

/**
 * A source beyond the phone, as the pack that brings it declares it. It holds what the app
 * needs to know about such a source without naming one: [PackSource] implements it over a
 * pack's binding, and the pack answers with its own scheme and label.
 */
interface ExtraSource {
    val source: MusicSource

    /** Which version of it is installed, as its update file writes versions; null when it cannot say. */
    val version: String? get() = null

    /**
     * Where its `update.json` is, or null when it is not updated from anywhere. A source is
     * installed and updated separately from the app; see [SourceUpdates] for the file.
     */
    val updates: String? get() = null

    /**
     * Whether a skin can be chosen for it, to be worn while its tracks play; see
     * [SourceSkins]. False means the setting is unsupported: its page does not offer one,
     * and its tracks wear the listener's own skin.
     */
    val skinnable: Boolean get() = true

    /**
     * Whether this device holds a credential for it, or null when the source could not be
     * asked.
     *
     * Null does not mean signed out: a source in an app of its own can be slow to start or
     * busy. [SourceOps.reconcile] leaves the record as it is on null.
     */
    fun signedIn(context: Context): Boolean?

    /**
     * A cheap form of [signedIn], for use while a window is drawn or a view model is built.
     * [signedIn] is the authoritative answer, which [SourceOps.reconcile] asks off the main
     * thread.
     */
    fun looksSignedIn(context: Context): Boolean = signedIn(context) == true

    /**
     * How far this source is from playing, as that changes.
     *
     * Signing in happens in the source's own app. A source that can report it returns a
     * flow here, and its rows in the playlist are relabeled when it emits. Null is a source
     * that cannot; [SourceOps.reconcile] asks it when the app or Preferences comes forward.
     */
    fun accountChanges(context: Context): Flow<SourceStanding>? = null

    /**
     * Whether anything on this phone can play its rows, asked cheaply: a source in an app
     * of its own can only while that app is installed. The answer decides whether a row
     * that cannot play is labeled missing or signed out.
     */
    fun reachable(context: Context): Boolean = true

    /**
     * The page this source's app is handed out on, or null when there is none.
     *
     * It is written into the saved playlist beside the rows that need it, so the address
     * survives the app being uninstalled or the playlist being shared.
     */
    fun home(context: Context): String? = null

    /**
     * Whose code this source is, in one line, for the page that offers it: what its app
     * calls itself, the package it is installed under, and part of its signing fingerprint.
     * Null when there is nothing to disclose.
     */
    fun provenance(context: Context): String? = null

    /** A player for its rows, or null while nobody is signed in. */
    fun backend(
        context: Context,
        scope: CoroutineScope,
    ): PlaybackBackend?

    /** Its library, or null while nobody is signed in. */
    fun browse(context: Context): BrowseSource?

    /** Its row's subtitle on the main Preferences page. */
    @Composable
    fun Summary(signedIn: Boolean): String

    /**
     * Its own settings, as an activity it owns and this app only opens. A composable cannot
     * cross a process boundary and the app never runs another package's code, so a source
     * in its own APK offers its settings this way. The source's page in Preferences then
     * holds the skin choice, the update check and one row that opens this.
     *
     * Sign-in and sign-out happen inside that screen, so [SourceOps.reconcile] updates the
     * record when the app or Preferences comes back into view. Null when there is no such
     * screen.
     */
    fun settings(context: Context): Intent? = null

    /**
     * Its own page under Preferences > Music sources, drawn by the source itself, for a
     * source that can draw in this process. The updates section comes after it, drawn by
     * the app from [updates]. A source that offers a [settings] screen leaves this empty.
     */
    @Composable
    fun Page(
        onSignedIn: () -> Unit,
        onSignedOut: () -> Unit,
    ) = Unit
}
