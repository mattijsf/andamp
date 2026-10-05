// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import nl.mattix.andamp.backend.pack.PackClient
import nl.mattix.andamp.backend.pack.PackFinder
import nl.mattix.andamp.backend.pack.PackReach

/**
 * Which sources this phone has.
 *
 * The list is found at run time. Every app that answers the bind action is a source; it
 * says what it is called and what scheme its rows have, and the player shows what it
 * finds. Nothing here names a service. The contract a source implements is in
 * `docs/source-packs.md`.
 *
 * It is process-wide and attached once, from the application, because its readers (a
 * window, a view model, the widget) share one set of bindings to the packs.
 *
 * A pack joins the list once it has said what it is. A freshly installed pack is known
 * only by its package until its first binding lands; what it answered is remembered, so on
 * later launches it is listed at once. See [PackClient.card].
 */
object PackSources {
    private var app: Context? = null

    /**
     * Its own scope, because the packs' bindings outlive every window. [forget] cancels
     * it and makes a new one.
     */
    private var scope = newScope()

    /** The receiver [watchPackages] registered, kept so [forget] can unregister it. */
    private var listening: BroadcastReceiver? = null

    /** One client per pack, by package name, so a pack is bound once. */
    private val clients = mutableMapOf<String, PackClient>()

    /**
     * The sources found, as snapshot state, so a pack installed or removed while the app
     * is open updates everything that reads it.
     */
    var found by mutableStateOf(emptyList<PackSource>())
        private set

    /**
     * Called by the application, before anything reads [found]. A second call with the
     * same application does nothing; a process can be started by the widget as well as by
     * the launcher.
     */
    fun attach(context: Context) {
        val here = context.applicationContext
        if (app === here) return
        // a different application is attached only in tests, once per test; the previous
        // one's clients are bound to an application that has been torn down
        if (app != null) forget()
        app = here
        watchPackages(here)
        look()
    }

    /**
     * Looks again at what is installed and what each pack says it is. It is cheap: a
     * resolve per pack and a map lookup.
     *
     * Synchronized, because it is called from the main thread ([attach], a package
     * broadcast) and from the watch on each client, which runs on the scope's threads.
     */
    @Synchronized
    private fun look() {
        val here = app ?: return
        val installed = PackFinder.found(here)
        clients.keys.retainAll(installed.toSet())
        installed.forEach { from -> clients.getOrPut(from) { clientFor(here, from) } }
        val was = found
        found =
            installed.mapNotNull { from ->
                val client = clients[from] ?: return@mapNotNull null
                val card = client.card() ?: return@mapNotNull null
                was.firstOrNull { it.packageName == from && it.source.id == card.id }
                    ?: PackSource(from, client, card)
            }
        // this may run outside a composition (a binder callback, the widget's process), where
        // nothing applies snapshot writes, so observers are notified here
        Snapshot.sendApplyNotifications()
    }

    /**
     * Looks again, and asks every installed pack that has never said what it is to say it
     * now. A pack's first binding can fail: one installed while the player sat in the
     * background, with its process being replaced as the player bound it, answers nothing,
     * and until it answers it is not listed. [SourceOps.reconcile] calls this when the app
     * comes forward, so such a pack is listed without a reinstall.
     *
     * A pack that answered with another contract number has no card either, and is not
     * asked again: it stays bound, and a newer install of it is noticed through that.
     */
    @Synchronized
    fun reachUnanswered() {
        look()
        clients.values
            .filter { it.card() == null && it.reach.value != PackReach.Outdated && it.reach.value != PackReach.Ahead }
            .forEach { client -> scope.launch { client.account() } }
    }

    /**
     * A client for one pack. Its reach is watched, so the list is read again when the
     * binding lands and the pack's card can be read.
     */
    private fun clientFor(
        here: Context,
        from: String,
    ): PackClient {
        val client = PackClient(here, fromPackage = from, scope = scope)
        scope.launch { client.reach.collect { look() } }
        return client
    }

    /**
     * Undoes everything [attach] set up, so each test starts from nothing. The app does
     * not call it directly; [attach] does when it is handed a second application.
     */
    @Synchronized
    internal fun forget() {
        scope.cancel()
        scope = newScope()
        // the application these were made for may already be gone, so a failing unbind is
        // ignored
        clients.values.forEach { client -> runCatching { client.release() } }
        clients.clear()
        val gone = app
        listening?.let { receiver -> runCatching { gone?.unregisterReceiver(receiver) } }
        listening = null
        app = null
        found = emptyList()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("packs"))

    /**
     * Listens for packages being installed, replaced or removed, and looks again on each.
     * Installing a source is all of its setup, so the player has to notice it this way.
     */
    private fun watchPackages(here: Context) {
        val events =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
        val listener =
            object : BroadcastReceiver() {
                override fun onReceive(
                    from: Context?,
                    about: Intent?,
                ) = look()
            }
        listening = listener
        // not exported where the platform supports the flag: these are system broadcasts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            here.registerReceiver(listener, events, Context.RECEIVER_NOT_EXPORTED)
        } else {
            here.registerReceiver(listener, events)
        }
    }
}
