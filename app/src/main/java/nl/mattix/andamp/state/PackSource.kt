// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import nl.mattix.andamp.backend.media3.PcmAudioOut
import nl.mattix.andamp.backend.pack.PackCard
import nl.mattix.andamp.backend.pack.PackClient
import nl.mattix.andamp.backend.pack.PackReach
import nl.mattix.andamp.backend.pack.descriptor
import nl.mattix.andamp.core.packapi.PackDescriptor
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.ui.prefs.PackPage
import nl.mattix.andamp.ui.prefs.packSummary

/**
 * A source that lives in an app of its own, as this player reaches it.
 *
 * One of these stands for one installed pack; which sources exist follows from what the
 * listener installed (see [PackSources]). Its id and label come from the pack's own
 * descriptor ([PackCard]): a pack for a service called Example is listed as "Example" and
 * plays `example:` rows. The id matches the scheme of its rows (see
 * [MusicSource.foreign]), which a saved playlist also records, so a row read back from
 * disk finds its source.
 *
 * `TooManyFunctions` is suppressed because [ExtraSource] has this many members.
 */
@Suppress("TooManyFunctions")
class PackSource(
    /** The app this source is, by package name; see [PackSources]. */
    val packageName: String,
    private val client: PackClient,
    card: PackCard,
) : ExtraSource {
    override val source = MusicSource(card.id, card.label)

    /** The client's last known state of the pack; nothing is known until the first bind returns. */
    private val reach: PackReach get() = client.reach.value

    /**
     * The pack's descriptor, or null while there is none to read. It does not depend on an
     * account: the version, the update address and whether a skin may be chosen are known
     * before sign-in.
     */
    private val descriptor: PackDescriptor? get() = reach.descriptor

    override val version: String? get() = descriptor?.version

    override val updates: String? get() = descriptor?.updates?.takeIf { it.isNotBlank() }

    override val skinnable: Boolean get() = descriptor?.skinnable ?: false

    /**
     * Whether the pack holds an account, asked across the binder; null when the pack could
     * not be reached. It blocks, so [SourceOps.reconcile] calls it off the main thread.
     */
    override fun signedIn(context: Context): Boolean? = runBlocking { client.account()?.signedIn }

    /**
     * The non-blocking form: true when the pack is ready, or when it is installed and the
     * client remembers it as signed in. The remembered answer covers a cold start, before
     * the first bind has returned.
     */
    override fun looksSignedIn(context: Context): Boolean =
        reach is PackReach.Ready || (client.installed() && client.remembered().signedIn)

    /** The pack's standing as its reach changes, so its rows are relabeled when a sign-in happens in the pack. */
    override fun accountChanges(context: Context): Flow<SourceStanding> =
        client.reach
            .map { standingOf(it) }
            .distinctUntilChanged()

    /** Whether the pack is installed, asked without binding it. */
    override fun reachable(context: Context): Boolean = client.installed()

    /** Where this pack is handed out, as its descriptor says. */
    override fun home(context: Context): String? = descriptor?.home?.takeIf { it.isNotBlank() }

    /**
     * A player for its rows, or null while the pack is not ready (missing, too old, or
     * signed out); the playlist then skips its rows.
     *
     * The pack decodes and this player renders. The output is Media3's, the same chain a
     * local file goes through, so the equalizer, the balance, the effect rack and the
     * visualizer act on these rows too. What crosses from the pack is PCM frames.
     *
     * The output gets the application context, because a caller's context can be an
     * activity that ends before the music does.
     */
    override fun backend(
        context: Context,
        scope: kotlinx.coroutines.CoroutineScope,
    ): PlaybackBackend? {
        if (reach !is PackReach.Ready) return null
        return client.backend(emptyList(), 0, scope, PcmAudioOut(context.applicationContext))
    }

    /** The listener's library inside the pack, or null while the pack is not ready. */
    override fun browse(context: Context): BrowseSource? = if (reach is PackReach.Ready) client.browse() else null

    @Composable
    override fun Summary(signedIn: Boolean): String {
        val now by client.reach.collectAsState()
        return packSummary(now, signedIn)
    }

    /**
     * Whose app is playing this source, in one line: what the app calls itself, its
     * package, and the start of its signing fingerprint. Nothing is refused on the basis
     * of it; it is shown so the listener can see who answers.
     */
    override fun provenance(context: Context): String? {
        val who = client.identity() ?: return null
        val signed =
            who.signer
                .takeIf { it.isNotBlank() }
                ?.let { ", signed $it…" }
                .orEmpty()
        return "Played by ${who.label} (${who.packageName}$signed)"
    }

    /**
     * The pack's own settings screen, resolved by intent action because the class is in
     * another APK; the player never loads the pack's code. Null when nothing answers, and
     * also for a pack built for another contract version: [Page] is drawn then, and says
     * where a newer one is.
     */
    override fun settings(context: Context): Intent? = if (reach is PackReach.Outdated) null else client.settings()

    /** What Preferences shows when the pack has no screen to open; see [PackPage]. */
    @Composable
    override fun Page(
        onSignedIn: () -> Unit,
        onSignedOut: () -> Unit,
    ) {
        val now by client.reach.collectAsState()
        PackPage(source.label, now)
    }
}

/** A pack's reach as a standing. A pack too old to talk to counts as absent, since nothing of it can be used. */
private fun standingOf(reach: PackReach): SourceStanding =
    when (reach) {
        is PackReach.Ready -> SourceStanding.READY
        is PackReach.SignedOut -> SourceStanding.SIGNED_OUT
        else -> SourceStanding.ABSENT
    }
