// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.VolumeMode
import nl.mattix.andamp.core.packapi.IMusicSourcePack
import nl.mattix.andamp.core.packapi.IPackListener
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackApi
import nl.mattix.andamp.core.packapi.PackDescriptor
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.PackState
import nl.mattix.andamp.core.packapi.PackTrack
import nl.mattix.andamp.core.packapi.toTrack
import nl.mattix.andamp.core.playback.PlaybackBackend

/**
 * The service side of [IMusicSourcePack], common to every pack.
 *
 * The verbs go to one [PlaybackBackend], the questions go to [answer], and what the backend
 * says goes out to every listener. A pack's own service adds what makes it one source: who
 * it is ([descriptor]), who is signed in ([whoIsHere]), its library ([answer]) and its
 * backend ([makeBackend]).
 *
 * A pack makes no sound. What it decodes it hands over through
 * [IMusicSourcePack.openAudio], and the player renders it. So this is a bound service with
 * no notification and no foreground state of its own.
 *
 * The player keeps this process alive: it binds with `BIND_INCLUDE_CAPABILITIES` while it
 * is itself in the foreground with its media notification.
 *
 * Everything the backend touches runs on the main thread. Binder calls arrive on pool
 * threads, so every verb is handed to the main dispatcher, which keeps them in the order
 * they were sent, and the state flow is collected there too.
 */
@Suppress("TooManyFunctions") // it implements every call of IMusicSourcePack
abstract class PackServiceBase : Service() {
    /** Where the backend and its collectors run: the main thread, as the class KDoc says. */
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The registered listeners. A [RemoteCallbackList] drops a listener when its process dies. */
    private val listeners = RemoteCallbackList<IPackListener>()

    /**
     * Where the backend's samples go: into the pipe the player reads; see [PackAudioOut].
     *
     * It is built before the backend and outlives every pipe, because the player asks for
     * a pipe and the backend starts decoding independently of each other.
     */
    protected val audio = PackAudioOut()

    /** The alias the manifest declares for the app list; see [PackLauncherEntry]. */
    protected abstract val launcherAlias: String

    /**
     * Whether the backend is built with the service, or at the first verb.
     *
     * Build it with the service when building it opens nothing (no session, no
     * connection), so that its state is relayed from the start. Build it at the first verb
     * when building it is costly: the player binds to read the descriptor and the account
     * before anything is played.
     */
    protected open val buildsBackendWithService: Boolean = true

    /** The one backend this process has; called once, and see [buildsBackendWithService] for when. */
    protected abstract fun makeBackend(): PlaybackBackend

    /** What this pack says it is, for [IMusicSourcePack.describe]. */
    protected abstract fun descriptor(): PackDescriptor

    /**
     * Who is signed in, read from what is kept on this phone.
     *
     * It must not ask the source: the player calls this as soon as it binds, and a server
     * that does not answer would hold that call up for a timeout.
     */
    protected abstract fun whoIsHere(): PackAccount

    /**
     * One page of one question, or null when there is nobody to ask.
     *
     * Called on a binder thread and expected to block. A null, or anything it throws, is
     * sent to the player as a failed answer.
     */
    protected abstract fun answer(question: PackQuestion): PackAnswer?

    /** What this pack keeps that belonged to the account that has just gone: its library, usually. */
    protected open fun forgetAccount() = Unit

    private val player =
        lazy {
            makeBackend().also { made -> scope.launch { PackRelay.relay(made.state, ::heard) } }
        }

    private val backend: PlaybackBackend by player

    /** The last thing said, so a player that binds mid-track is told where things stand. */
    @Volatile
    private var said = PackState()

    /** Whether the backend has been let go of, so it is let go of once; see [letGo]. */
    private var released = false

    /**
     * Restores the icon in the app list, unless the listener took it away.
     *
     * The player binds a pack as soon as it finds one, so this runs even for a pack whose
     * own screen was never opened. The manifest declares the icon off; see
     * [PackLauncherEntry].
     */
    override fun onCreate() {
        super.onCreate()
        PackLauncherEntry(this, launcherAlias).restore()
        if (buildsBackendWithService) player.value
    }

    override fun onBind(intent: Intent?): IBinder = wire

    /**
     * Returns true, so a later bind is delivered to `onRebind`, which this class leaves at
     * its default. When the last player unbinds and nothing started the service, the
     * system destroys it.
     */
    override fun onUnbind(intent: Intent?): Boolean = true

    /**
     * Not sticky.
     *
     * The only starts this service acts on are the account messages that [signedIn] and
     * [signedOut] send. A pack restarted with no player bound and no queue would have
     * nothing to do.
     */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_FORGOTTEN -> {
                dropAccount()
                // that start is the only thing keeping an unbound service running, and it
                // has been handled
                stopSelf()
            }

            ACTION_ACCOUNT -> {
                accountChanged()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * The account this pack was playing for has gone: signed out on the pack's own screen,
     * or refused by the source.
     *
     * Forgets what was kept for the account ([forgetAccount]), tells every listener at once
     * through `IPackListener.onAccount`, and stops the backend if it has been built. Call
     * it on the main thread.
     */
    protected fun dropAccount() {
        forgetAccount()
        accountChanged()
        // Main.immediate on the main thread: the stop runs here, before a stopSelf that
        // follows this call
        if (player.isInitialized()) scope.launch { backend.stop() }
    }

    override fun onDestroy() {
        listeners.kill()
        letGo()
        // closes a pipe that was handed out; the player reads the end of the stream
        audio.stop()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Releases the backend, once.
     *
     * A player that quits calls teardown and then unbinds, so this is reached twice. A
     * backend that was never built is not released.
     */
    private fun letGo() {
        if (!player.isInitialized() || released) return
        released = true
        backend.release()
    }

    /** The binder the player talks to. */
    private val wire =
        object : IMusicSourcePack.Stub() {
            override fun apiVersion(): Int = PackApi.PACK_API

            override fun describe(): PackDescriptor = descriptor()

            override fun account(): PackAccount = whoIsHere()

            override fun listen(listener: IPackListener?) {
                val arrived = listener ?: return
                listeners.register(arrived)
                // The current state is sent at once, so a player that binds mid-track can
                // show it. It is sent from the main thread like every other state, so it
                // cannot arrive after a newer one.
                scope.launch { runCatching { arrived.onState(said) } }
            }

            override fun stopListening(listener: IPackListener?) {
                listener?.let(listeners::unregister)
            }

            override fun setQueue(
                tracks: List<PackTrack>?,
                startIndex: Int,
            ) = verb { it.setQueue(tracks.orEmpty().map { track -> track.toTrack() }, startIndex) }

            override fun enqueue(tracks: List<PackTrack>?) =
                verb { it.enqueue(tracks.orEmpty().map { track -> track.toTrack() }) }

            override fun patchTracks(tracks: List<PackTrack>?) =
                verb { it.patchTracks(tracks.orEmpty().map { track -> track.toTrack() }) }

            override fun play() = verb { it.play() }

            override fun pause() = verb { it.pause() }

            override fun stop() = verb { it.stop() }

            override fun next() = verb { it.next() }

            override fun previous() = verb { it.previous() }

            override fun playAt(index: Int) = verb { it.playAt(index) }

            override fun seekTo(positionMs: Long) = verb { it.seekTo(positionMs) }

            override fun setVolume(fraction: Float) = verb { it.setVolume(fraction) }

            /**
             * Whose volume the slider moves, by the ordinal of [VolumeMode]. An ordinal
             * out of range is read as [VolumeMode.DEVICE].
             */
            override fun setVolumeMode(mode: Int) =
                verb { it.setVolumeMode(VolumeMode.entries.getOrNull(mode) ?: VolumeMode.DEVICE) }

            override fun setShuffle(on: Boolean) = verb { it.setShuffle(on) }

            override fun setRepeat(on: Boolean) = verb { it.setRepeat(on) }

            override fun setStopAfterCurrent(on: Boolean) = verb { it.setStopAfterCurrent(on) }

            override fun stopWithFadeout() = verb { it.stopWithFadeout() }

            /**
             * The player is quitting: the music stops, the backend is torn down and
             * released, and the service stops itself. A backend that was never built is not
             * built for this.
             */
            override fun teardown() {
                if (!player.isInitialized()) {
                    stopSelf()
                    return
                }
                scope.launch {
                    backend.stop()
                    backend.teardown()
                    letGo()
                    stopSelf()
                }
            }

            /**
             * This player is done with the pack: the music stops and nothing is torn down,
             * because the pack may be asked to play again.
             */
            override fun release() {
                if (player.isInitialized()) scope.launch { backend.stop() }
            }

            /**
             * One page of one question; see [answer].
             *
             * A null or an exception from [answer] is sent as a failed answer. An exception
             * thrown across a binder would not reach the player as an answer it can ask for
             * again.
             */
            override fun ask(question: PackQuestion?): PackAnswer {
                val asked = question ?: return FAILED
                return runCatching { answer(asked) }.getOrNull() ?: FAILED
            }

            /**
             * A pipe for the player to render, starting where the music is now.
             *
             * The backend is not touched: the player may ask before anything plays. When
             * the system cannot make a pipe the answer is null.
             */
            override fun openAudio(): ParcelFileDescriptor? {
                val made = runCatching { ParcelFileDescriptor.createPipe() }
                made.exceptionOrNull()?.let { Log.w(TAG, "the audio pipe could not be made", it) }
                val pipe = made.getOrNull() ?: return null
                audio.handOver(ParcelFileDescriptor.AutoCloseOutputStream(pipe[WRITE_END]))
                return pipe[READ_END]
            }
        }

    /**
     * Runs a verb on the main thread.
     *
     * The backend is fetched before the hop: one that is built at the first verb is built
     * on the binder thread that brought the verb, not on the main thread.
     */
    private fun verb(act: (PlaybackBackend) -> Unit) {
        val playing = backend
        scope.launch { act(playing) }
    }

    /** What the backend said, out to every listener. */
    private fun heard(state: PackState) {
        said = state
        val listening = listeners.beginBroadcast()
        repeat(listening) { at ->
            // a listener that throws is skipped; the list itself drops the ones whose
            // process has died
            runCatching { listeners.getBroadcastItem(at).onState(state) }
        }
        listeners.finishBroadcast()
    }

    /**
     * Tells every listener who is signed in now.
     *
     * The account is read from [whoIsHere] and not passed in, so listeners are told what
     * is stored on the phone.
     */
    private fun accountChanged() {
        val now = whoIsHere()
        val listening = listeners.beginBroadcast()
        repeat(listening) { at ->
            runCatching { listeners.getBroadcastItem(at).onAccount(now) }
        }
        listeners.finishBroadcast()
    }

    companion object {
        private const val TAG = "AndAmpPack"

        private val FAILED = PackAnswer(failed = true)

        /** What [ParcelFileDescriptor.createPipe] answers with, in its order. */
        private const val READ_END = 0
        private const val WRITE_END = 1

        /** The start action [signedOut] sends. */
        private const val ACTION_FORGOTTEN = "nl.mattix.andamp.pack.FORGOTTEN"

        /** The start action [signedIn] sends. */
        private const val ACTION_ACCOUNT = "nl.mattix.andamp.pack.ACCOUNT"

        /**
         * Tells every player listening to [service] that somebody signed in there.
         *
         * Sent as a start command, because the screen where it happens and the service a
         * player is bound to are two components of one app.
         */
        fun signedIn(
            context: Context,
            service: Class<out PackServiceBase>,
        ) {
            runCatching { context.startService(Intent(context, service).setAction(ACTION_ACCOUNT)) }
        }

        /**
         * Tells [service] that the account has gone; see [dropAccount].
         *
         * Sent as a start command, because the screen where signing out happens and the
         * service are two components of one app that need not be alive at the same time.
         * Call it from a screen in the foreground, which is what allows the service to be
         * started.
         */
        fun signedOut(
            context: Context,
            service: Class<out PackServiceBase>,
        ) {
            runCatching { context.startService(Intent(context, service).setAction(ACTION_FORGOTTEN)) }
        }
    }
}
