// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import nl.mattix.andamp.core.packapi.IMusicSourcePack
import nl.mattix.andamp.core.packapi.IPackListener
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackApi
import nl.mattix.andamp.core.packapi.PackDescriptor
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.PackTrack
import org.robolectric.Shadows.shadowOf

/** The package name the tests install their pack under. */
internal const val PACK_PACKAGE = "nl.mattix.andamp.pack.example"

internal const val PACK_SERVICE = "$PACK_PACKAGE.PackService"

internal const val PACK_SETTINGS = "$PACK_PACKAGE.SettingsActivity"

/**
 * A pack on the far side of the binder, as a plain object.
 *
 * The generated stub attaches itself as a local interface, so binding this in a Robolectric
 * test gives the client the real calls without a second process.
 */
internal class FakePack(
    private val api: Int = PackApi.PACK_API,
    private val descriptor: PackDescriptor = describes(),
    /** Who is signed in; a test changes it to stand for signing in or out on the pack's screen. */
    var whose: PackAccount = PackAccount(signedIn = true, name = "someone"),
    private val answers: (PackQuestion) -> PackAnswer = { PackAnswer() },
) : IMusicSourcePack.Stub() {
    /** Every question asked of this pack, in the order it was asked. */
    val asked = mutableListOf<PackQuestion>()

    /** Every verb heard, by name, in the order it arrived. */
    val heard = mutableListOf<String>()

    /** How many times `apiVersion` has been called: once per binding. */
    var greetings = 0
        private set

    /** The registered listener, or null when there is none. */
    var listener: IPackListener? = null
        private set

    var queue: List<PackTrack> = emptyList()
        private set

    /** The start index of the last queue set, or -1 when none was. */
    var startedAt: Int = -1
        private set

    override fun apiVersion(): Int {
        greetings++
        return api
    }

    override fun describe(): PackDescriptor {
        heard += "describe"
        return descriptor
    }

    override fun account(): PackAccount {
        heard += "account"
        return whose
    }

    override fun listen(listener: IPackListener?) {
        heard += "listen"
        this.listener = listener
    }

    override fun stopListening(listener: IPackListener?) {
        heard += "stopListening"
        if (this.listener === listener) this.listener = null
    }

    override fun setQueue(
        tracks: MutableList<PackTrack>?,
        startIndex: Int,
    ) {
        heard += "setQueue"
        queue = tracks.orEmpty().toList()
        startedAt = startIndex
    }

    override fun enqueue(tracks: MutableList<PackTrack>?) {
        heard += "enqueue"
        queue = queue + tracks.orEmpty()
    }

    override fun patchTracks(tracks: MutableList<PackTrack>?) {
        heard += "patchTracks"
    }

    override fun play() {
        heard += "play"
    }

    override fun pause() {
        heard += "pause"
    }

    override fun stop() {
        heard += "stop"
    }

    override fun next() {
        heard += "next"
    }

    override fun previous() {
        heard += "previous"
    }

    override fun playAt(index: Int) {
        heard += "playAt $index"
    }

    override fun seekTo(positionMs: Long) {
        heard += "seekTo $positionMs"
    }

    override fun setVolume(fraction: Float) {
        heard += "setVolume $fraction"
    }

    override fun setVolumeMode(mode: Int) {
        heard += "setVolumeMode $mode"
    }

    override fun setShuffle(on: Boolean) {
        heard += "setShuffle $on"
    }

    override fun setRepeat(on: Boolean) {
        heard += "setRepeat $on"
    }

    override fun setStopAfterCurrent(on: Boolean) {
        heard += "setStopAfterCurrent $on"
    }

    override fun stopWithFadeout() {
        heard += "stopWithFadeout"
    }

    override fun teardown() {
        heard += "teardown"
    }

    override fun release() {
        heard += "release"
    }

    override fun ask(question: PackQuestion?): PackAnswer {
        val put = question ?: PackQuestion(kind = "")
        asked += put
        return answers(put)
    }

    /**
     * Supplies the read end of the next pipe. A test that wants two stretches to differ hands
     * out a different pipe per call. Null stands for a pack with nothing to give.
     */
    var audio: () -> ParcelFileDescriptor? = { null }

    /** How many pipes have been asked for. */
    var opened = 0
        private set

    override fun openAudio(): ParcelFileDescriptor? {
        opened++
        return audio()
    }
}

/**
 * The read end of a pipe that already holds [bytes] and whose write end is closed, so a reader
 * gets the bytes and then end of stream without waiting.
 */
internal fun stretch(vararg bytes: Byte): ParcelFileDescriptor {
    val pipe = ParcelFileDescriptor.createPipe()
    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
    return pipe[0]
}

/** A descriptor with every capability on by default. */
internal fun describes(
    canSeek: Boolean = true,
    canEditQueue: Boolean = true,
    canAttenuate: Boolean = true,
    canSearch: Boolean = true,
    hasPlaylists: Boolean = true,
    hasCatalogue: Boolean = true,
    handsOverAudio: Boolean = true,
): PackDescriptor =
    PackDescriptor(
        scheme = "example:",
        label = "Example",
        version = "1.2.3",
        canSeek = canSeek,
        canEditQueue = canEditQueue,
        canAttenuate = canAttenuate,
        canSearch = canSearch,
        hasPlaylists = hasPlaylists,
        hasCatalogue = hasCatalogue,
        handsOverAudio = handsOverAudio,
    )

/**
 * Registers [pack] the way an installed one answers: a service behind the bind action, a
 * settings screen behind the settings action, and a binder handed over as soon as anything
 * binds.
 */
internal fun Application.install(
    pack: IMusicSourcePack.Stub,
    action: String = PackApi.ACTION_BIND,
    withSettings: Boolean = true,
    from: String = PACK_PACKAGE,
) {
    shadowOf(packageManager).addResolveInfoForIntent(Intent(action), serviceRow(from))
    // registered again for the intent a client with a package name sends: the shadow matches
    // intents as registered, so the bare action and the one aimed at a package are separate
    shadowOf(packageManager).addResolveInfoForIntent(Intent(action).setPackage(from), serviceRow(from))
    if (withSettings) {
        shadowOf(packageManager).addResolveInfoForIntent(
            Intent(PackApi.ACTION_SETTINGS).setPackage(from),
            settingsRow(from),
        )
    }
    shadowOf(this).setComponentNameAndServiceForBindService(ComponentName(from, "$from.PackService"), pack)
    // connected directly, not posted, so that a binding does not depend on the test's clock
    shadowOf(this).setBindServiceCallsOnServiceConnectedDirectly(true)
}

/** The most recent connection the framework holds, for a test to deliver a disconnect on. */
internal fun Application.boundToPack(): ServiceConnection = shadowOf(this).boundServiceConnections.last()

private fun serviceRow(from: String): ResolveInfo =
    ResolveInfo().apply {
        serviceInfo =
            ServiceInfo().apply {
                packageName = from
                name = "$from.PackService"
                applicationInfo = ApplicationInfo().apply { packageName = from }
            }
    }

private fun settingsRow(from: String): ResolveInfo =
    ResolveInfo().apply {
        activityInfo =
            ActivityInfo().apply {
                packageName = from
                name = "$from.SettingsActivity"
                applicationInfo = ApplicationInfo().apply { packageName = from }
            }
    }
