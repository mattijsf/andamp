// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.packapi.IMusicSourcePack
import nl.mattix.andamp.core.packapi.IPackListener
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackApi
import nl.mattix.andamp.core.packapi.PackDescriptor
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.PlaybackBackend
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The player's end of a music source that lives in its own APK.
 *
 * It answers whether a pack is installed, which contract it implements and who is signed into
 * it, and it hands out a player for the pack's rows and a library to browse. None of the pack's
 * code runs in this process; only the parcels in `Wire.kt` cross.
 *
 * - The pack is found by resolving [PackApi.ACTION_BIND], which a `<queries><intent>` entry in
 *   the player's manifest makes visible, so the player needs no permission to read the phone's
 *   app list.
 * - Binding is lazy. The first question binds, a pack whose process dies is noticed, and the
 *   question after that binds again.
 * - Calls into the binder run on [io], and verbs on [wire]. [installed], [identity], [settings],
 *   [remembered] and [card] are synchronous: they ask the package manager or this player's own
 *   preferences and never the pack.
 *
 * The suppression: one pack is a binding, a descriptor, an account, a player, a library and the
 * verbs that cross, and all of them go through the one connection held here.
 */
@Suppress("TooManyFunctions")
class PackClient(
    context: Context,
    private val action: String = PackApi.ACTION_BIND,
    /**
     * Which pack this client is for, by package name, or null for whichever one the phone
     * answers with. A phone may hold more than one pack (see [PackFinder]), and a client without
     * a package name could resolve to a different pack once a second one is installed.
     */
    private val fromPackage: String? = null,
    private val scope: CoroutineScope,
    /** Where the binder calls run. The tests pass the scheduler they control. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Where verbs go out, one at a time. A oneway call keeps its order only against calls sent
     * from the same thread, so verbs posted from different threads of a pool could reach the
     * pack out of order.
     */
    private val wire: CoroutineDispatcher = io.limitedParallelism(1),
) {
    /** The application context, because this outlives every window. */
    private val context: Context = context.applicationContext

    /**
     * Where [remembered] keeps what the pack last said. One file per pack when [fromPackage] is
     * given, so that two packs do not read each other's account.
     */
    private val memory =
        this.context.getSharedPreferences(
            fromPackage?.let { "pack.$action.$it" } ?: "pack.$action",
            Context.MODE_PRIVATE,
        )

    private val _reach = MutableStateFlow<PackReach>(PackReach.Absent)

    /** How far this player can get with the pack right now; see [PackReach]. */
    val reach: StateFlow<PackReach> = _reach.asStateFlow()

    /** One binding at a time, however many questions arrive at once. */
    private val gate = Mutex()

    /**
     * The listeners to register with the pack, kept across bindings.
     *
     * A registration lasts only as long as the pack's process, and that process ends when the
     * pack stops itself, is updated or is reclaimed by the system. Every fresh binding registers
     * all of them again; see [greet].
     *
     * Written from the main thread and read on binder threads, hence the copy-on-write set.
     */
    private val listening = CopyOnWriteArraySet<IPackListener>()

    @Volatile
    private var pack: IMusicSourcePack? = null

    @Volatile
    private var connection: ServiceConnection? = null

    /** The package [connection] is bound to, so that the removal of another package is ignored. */
    @Volatile
    private var boundPackage: String? = null

    /**
     * What the pack last said about itself, kept because the capabilities of a backend and a
     * browse source are read without suspending; null while nothing is bound.
     */
    @Volatile
    private var descriptor: PackDescriptor? = null

    /**
     * Set when a bound pack answers with a contract number this build does not implement.
     * Nothing is asked of it afterwards.
     *
     * The binding is kept, so that the pack's process ending (which installing a newer one
     * causes) clears this through [lost] and the next question binds again.
     */
    @Volatile
    private var refused = false

    /** The death notice linked to the bound pack's binder; one per binding, null while nothing is bound. */
    @Volatile
    private var gravestone: IBinder.DeathRecipient? = null

    /**
     * Notices a pack being installed or removed, so that [reach] follows without a question
     * having to be asked. The system delivers every package change on the phone here; each one
     * costs a resolve.
     */
    private val packages =
        object : BroadcastReceiver() {
            override fun onReceive(
                from: Context?,
                about: Intent?,
            ) {
                // A replace arrives as a remove and an add. Only the removal of the bound
                // package drops the binding.
                val gone = about?.data?.schemeSpecificPart
                if (about?.action == Intent.ACTION_PACKAGE_REMOVED && gone != null && gone == boundPackage) lost()
                scope.launch { account() }
            }
        }

    init {
        // one look, so that [reach] is set before anything has been asked
        scope.launch { account() }
        val packageEvents =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
        // not exported where the platform allows it: these are system broadcasts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.context.registerReceiver(packages, packageEvents, Context.RECEIVER_NOT_EXPORTED)
        } else {
            this.context.registerReceiver(packages, packageEvents)
        }
    }

    /**
     * Whether anything on this phone answers the bind action. A resolve only: it binds nothing
     * and starts no process.
     */
    fun installed(): Boolean = resolve() != null

    /**
     * Who answers for this source: the app's name, its package and the start of the fingerprint
     * of a key that signed it; see [PackIdentity]. The player keeps no list of approved keys and
     * shows this to the listener instead. Null when nothing answers the action.
     */
    fun identity(): PackIdentity? = resolve()?.let { PackIdentity.of(context, it.packageName) }

    /**
     * The pack's own settings screen, or null when nothing answers.
     *
     * Sign-in happens on a screen the pack draws, because this app does not run another
     * package's code. The activity is resolved inside the pack's own package, so that another
     * app answering the same action cannot stand in for it.
     *
     * The suppressed deprecation is the resolve: the flag-taking overloads are the only ones
     * that exist below API 33.
     */
    @Suppress("DEPRECATION")
    fun settings(): Intent? {
        val installed = resolve()?.packageName ?: return null
        val screen = Intent(PackApi.ACTION_SETTINGS).setPackage(installed)
        val found = context.packageManager.resolveActivity(screen, 0) ?: return null
        return screen.setComponent(ComponentName(found.activityInfo.packageName, found.activityInfo.name))
    }

    /**
     * Who is signed into the pack, asked fresh on [io]. It binds if it has to, and sets [reach].
     * `SourceOps.reconcile` calls it when the app or Preferences comes forward; the pack also
     * pushes account changes, see [heard].
     *
     * Null when there was no pack to ask or the call failed, which is different from a pack
     * that answered "nobody": a bind that times out while the pack's process starts is not a
     * sign-out.
     */
    suspend fun account(): PackAccount? {
        val reachable = bound() ?: return null
        val account = withContext(io) { reaching { reachable.account() } } ?: return null
        settle(account)
        return account
    }

    /**
     * A player for the pack's rows, holding the queue it is given.
     *
     * The pack owns the transport and runs Winamp's rules over the queue. Given an [out], the
     * pack's samples are read from its pipe and rendered through this player's own chain, which
     * holds the equalizer, the rack and the visualizer tap. Without one the pack's audio is not
     * rendered here.
     */
    fun backend(
        tracks: List<Track>,
        startIndex: Int,
        scope: CoroutineScope,
        out: AudioOut? = null,
    ): PlaybackBackend = PackBackend(this, tracks, startIndex, scope, out)

    /** The pack's library, asked a page at a time; see [PackBrowse]. */
    fun browse(): BrowseSource = PackBrowse(this)

    /** Unregisters the package receiver and drops the binding. A later question binds again. */
    fun release() {
        runCatching { context.unregisterReceiver(packages) }
        lost()
    }

    /** What the pack said about itself when it was last reached; null until then. */
    internal val known: PackDescriptor? get() = descriptor

    /**
     * [who] is registered on every binding after this one; see [listening].
     *
     * This only remembers. The caller sends the registration for the current binding as a verb,
     * so that it keeps its place among the caller's other verbs.
     */
    internal fun remember(who: IPackListener) {
        listening += who
    }

    /** [who] is not registered on any later binding. */
    internal fun forget(who: IPackListener) {
        listening -= who
    }

    /** One question. Null means the pack could not be asked. */
    internal suspend fun ask(question: PackQuestion): PackAnswer? {
        val reachable = bound() ?: return null
        return withContext(io) { reaching { reachable.ask(question) } }
    }

    /**
     * The read end of a pipe the pack writes its decoded audio into, or null when the pack
     * cannot be reached.
     *
     * One pipe is one stretch of music: the pack closes its end whenever it drops what it
     * decoded, and the reader asks again for a pipe that starts where the music then is. It
     * binds like any other question.
     *
     * Called from the render thread; see [PackAudio].
     */
    internal suspend fun openAudio(): ParcelFileDescriptor? {
        val reachable = bound() ?: return null
        return withContext(io) { reaching { reachable.openAudio() } }
    }

    /**
     * One verb, sent on [wire] if the pack can be reached and dropped if it cannot. A dropped
     * verb is not queued for a later binding.
     */
    internal suspend fun tell(verb: (IMusicSourcePack) -> Unit) {
        val reachable = bound() ?: return
        withContext(wire) { reaching { verb(reachable) } }
    }

    /**
     * The pack, bound if it is not already; null when it cannot be reached or when it
     * implements another contract.
     */
    private suspend fun bound(): IMusicSourcePack? {
        if (refused) return null
        live()?.let { return it }
        return gate.withLock { live() ?: connect() }
    }

    private fun live(): IMusicSourcePack? = pack?.takeIf { it.asBinder().isBinderAlive }

    private suspend fun connect(): IMusicSourcePack? {
        val component = resolve() ?: return absent()
        // A binder can be dead before the disconnect or the death notice has arrived, so a
        // binding may still be held here. It is let go of before another is taken.
        letGo()
        val arrived = CompletableDeferred<IBinder?>()
        val watcher = watcher(arrived)
        if (!withContext(io) { bind(component, watcher) }) {
            unbind(watcher)
            return absent()
        }
        connection = watcher
        boundPackage = component.packageName
        // a bind that is accepted and never answered times out after PATIENCE_MS
        val binder = withTimeoutOrNull(PATIENCE_MS) { arrived.await() }
        return if (binder == null) {
            lost(watcher)
            null
        } else {
            withContext(io) { greet(binder, watcher) }
        }
    }

    private fun watcher(arrived: CompletableDeferred<IBinder?>): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                arrived.complete(binder)
            }

            /**
             * The pack's process is gone. The binding is dropped, so that the next question
             * binds and greets the pack again.
             */
            override fun onServiceDisconnected(name: ComponentName?) {
                lost(this)
            }

            /** The service returned no binder. */
            override fun onNullBinding(name: ComponentName?) {
                arrived.complete(null)
            }
        }

    private fun bind(
        component: ComponentName,
        watcher: ServiceConnection,
    ): Boolean =
        try {
            // Explicit, from the resolve: an implicit service intent is refused, and naming
            // the component keeps another app from answering for the pack.
            //
            // BIND_INCLUDE_CAPABILITIES lends the pack this app's capabilities while the
            // binding is up. The pack shows no notification of its own, so without the flag
            // its process would be frozen when no window of the player is on screen.
            context.bindService(
                Intent(action).setComponent(component),
                watcher,
                Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES,
            )
        } catch (expected: SecurityException) {
            false
        }

    /**
     * The first calls on a freshly bound pack: the death notice is linked, the contract number
     * is read, and if it matches, the descriptor is read and every listener in [listening] is
     * registered.
     */
    private fun greet(
        binder: IBinder,
        watcher: ServiceConnection,
    ): IMusicSourcePack? =
        try {
            val greeted = IMusicSourcePack.Stub.asInterface(binder)
            // the notice names its own binding, so that a late one cannot drop a newer binding
            val notice = IBinder.DeathRecipient { lost(watcher) }
            binder.linkToDeath(notice, 0)
            gravestone = notice
            pack = greeted
            if (greeted.apiVersion() == PackApi.PACK_API) {
                descriptor = greeted.describe()
                listening.forEach { greeted.listen(it) }
                greeted
            } else {
                refused = true
                _reach.value = PackReach.Outdated
                null
            }
        } catch (expected: RemoteException) {
            lost(watcher)
            null
        } catch (
            // an exception thrown by the pack's own code and carried across; see [reaching]
            @Suppress("TooGenericExceptionCaught")
            thrown: RuntimeException,
        ) {
            Log.w(TAG, "a pack threw while being greeted", thrown)
            lost(watcher)
            null
        }

    /** The pack pushed an account change: somebody signed in or out on its own screen. */
    internal fun heard(account: PackAccount) = settle(account)

    /** Stores [account] and sets [reach] from it. Does nothing while no descriptor is known. */
    private fun settle(account: PackAccount) {
        val described = descriptor ?: return
        remember(account, described)
        _reach.value = if (account.signedIn) PackReach.Ready(described, account) else PackReach.SignedOut(described)
    }

    /**
     * What the pack said the last time it was asked, read from this player's own preferences.
     *
     * A binding takes a moment, and a row about the source is drawn before it lands. The answer
     * is only meaningful for a pack that is still installed; see [installed].
     */
    fun remembered(): PackAccount =
        PackAccount(
            signedIn = memory.getBoolean(SIGNED_IN, false),
            name = memory.getString(WHOSE, "").orEmpty(),
        )

    /**
     * The pack's scheme and label without waiting for a binding: from the descriptor when one
     * is known, otherwise as stored the last time the pack was reached.
     *
     * Null for a pack that has never been reached. A caller draws nothing for it and looks
     * again when [reach] changes.
     */
    fun card(): PackCard? {
        descriptor?.let { return PackCard(scheme = it.scheme, label = it.label) }
        val scheme = memory.getString(SCHEME, "").orEmpty()
        val label = memory.getString(LABEL, "").orEmpty()
        return if (scheme.isBlank() || label.isBlank()) null else PackCard(scheme, label)
    }

    private fun remember(
        account: PackAccount,
        described: PackDescriptor,
    ) {
        memory
            .edit()
            .putBoolean(SIGNED_IN, account.signedIn)
            .putString(WHOSE, account.name)
            .putString(SCHEME, described.scheme)
            .putString(LABEL, described.label)
            .apply()
    }

    /**
     * The binding is gone: it is dropped and [reach] becomes [PackReach.Absent].
     *
     * Called from binder threads as well as the caller's, and safe to call twice.
     *
     * A process that dies reports it twice, as a disconnect and as a death notice, and a new
     * binding may exist by the time the second arrives. [of] is the binding the report is about
     * and is ignored when it is not the current one; null means whatever is bound now.
     */
    private fun lost(of: ServiceConnection? = null) {
        if (of != null && of !== connection) return
        letGo()
        _reach.value = PackReach.Absent
    }

    /** Drops whatever binding is held: unlinks the death notice, unbinds the service and clears the fields. */
    private fun letGo() {
        pack?.asBinder()?.let { binder -> gravestone?.let { notice -> unlink(binder, notice) } }
        gravestone = null
        boundPackage = null
        pack = null
        descriptor = null
        refused = false
        connection?.let { watcher ->
            connection = null
            unbind(watcher)
        }
    }

    private fun unlink(
        binder: IBinder,
        notice: IBinder.DeathRecipient,
    ) {
        try {
            binder.unlinkToDeath(notice, 0)
        } catch (expected: NoSuchElementException) {
            // never linked, or the pack's process is already gone
        }
    }

    private fun unbind(watcher: ServiceConnection) {
        try {
            context.unbindService(watcher)
        } catch (expected: IllegalArgumentException) {
            // never bound, or already unbound
        }
    }

    private fun absent(): IMusicSourcePack? {
        _reach.value = PackReach.Absent
        return null
    }

    /**
     * The pack's service as the package manager finds it, restricted to [fromPackage] when one
     * was given; null when nothing answers.
     */
    @Suppress("DEPRECATION")
    private fun resolve(): ComponentName? {
        val asked = Intent(action).apply { fromPackage?.let(::setPackage) }
        val found = context.packageManager.resolveService(asked, 0) ?: return null
        return ComponentName(found.serviceInfo.packageName, found.serviceInfo.name)
    }

    /**
     * One call into the pack. A [RemoteException], which is what a pack uninstalled or killed
     * between two calls looks like, drops the binding and returns null.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> reaching(call: () -> T): T? =
        try {
            call()
        } catch (expected: RemoteException) {
            lost()
            null
        } catch (thrown: RuntimeException) {
            // A binder carries some of what the far side throws back to the caller, such as
            // an IllegalStateException or a NullPointerException. The call returns null and
            // the binding stays.
            Log.w(TAG, "a pack threw instead of answering", thrown)
            null
        }

    private companion object {
        const val TAG = "PackClient"

        /** Whether the pack held an account when it was last asked; see [remembered]. */
        const val SIGNED_IN = "signedIn"

        /** The account's name. */
        const val WHOSE = "whose"

        /** The pack's scheme and label; see [card]. */
        const val SCHEME = "scheme"

        const val LABEL = "label"

        /** How long a bind may take to answer, in milliseconds, before the pack counts as absent. */
        const val PATIENCE_MS = 10_000L
    }
}
