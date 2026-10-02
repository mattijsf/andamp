// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * What the app does when a source is picked, signed in to or signed out of: the actions
 * behind [LibrarySources]' answers.
 */
class SourceOps(
    private val state: WinampState,
    private val sources: LibrarySources,
    private val library: LibraryOps,
    /** The sources beyond the phone, asked for each time; see [PackSources.found]. */
    private val extras: () -> List<ExtraSource> = { PackSources.found },
    /** Where each source's credential is read. */
    private val io: CoroutineContext = Dispatchers.IO,
    /** Drops a source's cached library; see [Libraries.forget]. */
    private val forgetLibrary: (MusicSource) -> Unit = Libraries::forget,
    /** Releases the player that holds a source's key; see [PlaybackRoot.signedOut]. */
    private val forgetPlayer: (MusicSource) -> Unit = PlaybackRoot::signedOut,
) {
    /**
     * The source a Media Library pick sent the listener to sign in to, until that sign-in
     * happens or Preferences is left. The library window opens when the sign-in completes.
     * A sign-in started from Preferences itself is not remembered here.
     */
    private var pendingPick: MusicSource? = null

    /**
     * Main menu > Media Library > a source: the window opens on it, moves onto it, or
     * closes when the entry is already ticked.
     *
     * A source with nobody signed in has nothing to show, so that pick opens Preferences
     * on the source's page, where the sign-in is, and the window opens once the sign-in is
     * done.
     */
    fun libraryFrom(
        source: MusicSource,
        onSignIn: () -> Unit,
    ) {
        when (sources.pick(source, state.libraryOpen)) {
            LibrarySources.Pick.Show -> {
                state.setWindowOpen(WindowStore.LIBRARY, true) { library.open() }
            }

            LibrarySources.Pick.Close -> {
                state.libraryOpen = false
            }

            LibrarySources.Pick.SignInFirst -> {
                pendingPick = source
                state.arrivalPage = sourceRoute(source)
                onSignIn()
            }

            LibrarySources.Pick.Nothing -> {
                Unit
            }
        }
    }

    /**
     * A source is signed in: from Preferences, or found by [reconcile].
     *
     * An open library window moves onto it; a closed one opens only when a Media Library
     * pick sent the listener to sign in. The player needs nothing: the next of the
     * source's rows that is reached builds its player.
     */
    fun signedIn(source: MusicSource) {
        val headedThere = pendingPick == source
        pendingPick = null
        forgetLibrary(source)
        sources.signedIn(source)
        when {
            state.libraryOpen -> library.open()
            headedThere -> state.setWindowOpen(WindowStore.LIBRARY, true) { library.open() }
            else -> Unit
        }
    }

    /**
     * A source is signing out, or was found signed out by [reconcile]: its library and
     * the player that holds its key are dropped.
     *
     * Which source the window showed is read before the sign-out, because afterwards the
     * window has already fallen back to the phone.
     */
    fun signedOut(source: MusicSource) {
        pendingPick = null
        val wasShowing = sources.showing
        sources.signedOut(source)
        forgetLibrary(source)
        forgetPlayer(source)
        if (state.libraryOpen && wasShowing == source) library.open()
    }

    /** Preferences was closed: a pending Media Library pick is dropped. */
    fun preferencesClosed() {
        pendingPick = null
    }

    /**
     * Listens to every source that can report account changes, for as long as [scope]
     * runs, so a sign-in made in the source's own app relabels its playlist rows at once.
     *
     * A report that matches the record is dropped: [signedIn] opens the library window
     * for a pending pick, and a repeated report must not reopen it.
     */
    fun follow(
        context: Context,
        scope: CoroutineScope,
    ) {
        scope.launch {
            val followed = mutableMapOf<MusicSource, Followed>()
            snapshotFlow { extras() }.collect { now -> follow(context, scope, now, followed) }
        }
    }

    /**
     * Starts following the sources that have arrived and stops following the ones that
     * have gone. The list is followed because a pack can be installed while the app is
     * open, and it joins the list only once it has said what it is.
     *
     * A source is followed by the object that answers for it, not by its id: the same
     * source handed back as a different object is another app answering under the same
     * id. [SourceLanes] keeps its players by the same rule.
     */
    private fun follow(
        context: Context,
        scope: CoroutineScope,
        now: List<ExtraSource>,
        followed: MutableMap<MusicSource, Followed>,
    ) {
        // if two apps answer for one source, the first one found is followed
        val here = now.distinctBy { it.source }.associateBy { it.source }
        followed.entries.toList().forEach { (source, was) ->
            val answering = here[source]
            if (answering === was.extra) return@forEach
            followed.remove(source)
            was.listening.cancel()
            if (answering == null) {
                stands(source, SourceStanding.ABSENT)
            } else {
                // another app answers for it: the cached library came from the one that went
                forgetLibrary(source)
            }
        }
        here.values.forEach { extra ->
            if (extra.source in followed) return@forEach
            val changes = extra.accountChanges(context)
            if (changes == null) {
                // it cannot report account changes, so it is only recorded as installed;
                // reconcile updates the account
                sources.installed(extra.source)
                return@forEach
            }
            followed[extra.source] =
                Followed(extra, scope.launch { changes.collect { standing -> stands(extra.source, standing) } })
        }
    }

    /** A followed source: the object that answers for it, and the collecting job. */
    private class Followed(
        val extra: ExtraSource,
        val listening: Job,
    )

    /**
     * Applies what a source reported to the two records the app reads: whether its rows
     * can play at all, and whether anybody is signed in. A report that changes nothing
     * does not call [signedIn] or [signedOut].
     */
    private fun stands(
        source: MusicSource,
        standing: SourceStanding,
    ) {
        val reach = sources.reach
        val here = source in reach.present
        val recorded = source in reach.signedIn
        when (standing) {
            SourceStanding.ABSENT -> {
                if (recorded) signedOut(source)
                sources.uninstalled(source)
            }

            SourceStanding.SIGNED_OUT -> {
                sources.installed(source)
                if (recorded) signedOut(source)
            }

            SourceStanding.READY -> {
                sources.installed(source)
                if (!recorded || !here) signedIn(source)
            }
        }
    }

    /**
     * Brings the record of who is signed in back in line with what is on this device.
     *
     * A sign-in or sign-out can happen with nothing listening for it, in the source's own
     * app. So when the app or Preferences comes into view, each source is asked what it
     * holds, and a difference is applied as a sign-in or a sign-out; see
     * [reconcileWhileShown].
     *
     * The credentials are read on [io]; the record is changed on the caller's context.
     */
    suspend fun reconcile(context: Context) {
        val carried = extras()
        val recorded = sources.reach.signedIn
        // a source that throws or could not be reached is left as the record has it
        val held =
            withContext(io) {
                carried
                    .filter { extra ->
                        runCatching { extra.signedIn(context) }.getOrNull() ?: (extra.source in recorded)
                    }.map { it.source }
                    .toSet()
            }
        // if the record changed while the sources were being read, the read is stale and
        // is dropped; the next call settles it
        if (sources.reach.signedIn != recorded) return
        for (source in carried.map { it.source }) {
            when {
                source in held && source !in recorded -> signedIn(source)
                source !in held && source in recorded -> signedOut(source)
                else -> Unit
            }
        }
    }

    /**
     * Runs [reconcile] each time the app is resumed, for as long as the caller waits on
     * this. A source's sign-in happens in its own app, so the answer can have changed on
     * return. It runs on resume, not on start, because a sign-in screen that covers only
     * part of the player pauses the app without stopping it.
     */
    suspend fun reconcileWhileShown(
        context: Context,
        lifecycle: Lifecycle,
    ) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { reconcile(context) }
    }
}
