// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.EffectSpec
import nl.mattix.andamp.core.model.ParamSpec
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Adding a plug-in and taking it away again. Removing a plug-in takes its settings with
 * it, so one installed a second time comes back switched off with nothing stored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginOpsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun clearStorage() {
        listOf("dsp", "plugins").forEach {
            app
                .getSharedPreferences(it, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        java.io.File(app.filesDir, "plugins").deleteRecursively()
    }

    /**
     * A backend that offers one [PLUGIN_SPEC] for every source it was handed. It does not
     * run the Lua.
     */
    private class EffectBackend(
        scope: CoroutineScope,
    ) : PlaybackBackend by MockBackend(nl.mattix.andamp.state.FakeTracks.tracks, scope) {
        private val delegate = MockBackend(FakeTracks.tracks, scope)
        var installed: List<String> = emptyList()
            private set

        override val state get() = delegate.state
        override val capabilities: Capabilities get() = delegate.capabilities.copy(hasDsp = true)

        override val effects: List<EffectSpec>
            get() =
                nl.mattix.andamp.core.model.BuiltInEffects.all +
                    installed.map { PLUGIN_SPEC }

        override val shippedPluginIds = listOf(SHIPPED_ID)

        override fun setPlugins(sources: List<String>) {
            installed = sources
        }
    }

    private fun ops(
        backend: EffectBackend,
        download: (String) -> String = { error("nothing to download in this test") },
    ): Pair<DspOps, PluginOps> {
        val facade = PlayerFacade(backend)
        val dsp = DspOps(app, facade)
        return dsp to PluginOps(app, facade, dsp, scope, Dispatchers.Unconfined, download)
    }

    /** Runs what is queued on the main looper before anything is asserted. */
    private fun settle() = ShadowLooper.idleMainLooper()

    @Test
    fun `a plug-in that loads joins the rack`() {
        val backend = EffectBackend(scope)
        val (dsp, plugins) = ops(backend)

        plugins.install(GAIN.byteInputStream())
        settle()

        assertNull("the install reports no problem", plugins.problem)
        assertEquals(listOf("Gain"), plugins.entries.map { it.name })
        assertTrue("the backend receives the plug-in source", backend.installed.single().contains("plugin {"))
        assertNotNull("the rack holds the plug-in", dsp.rack[PLUGIN_ID])
    }

    @Test
    fun `what the plug-in says about itself is what is listed`() {
        val (_, plugins) = ops(EffectBackend(scope))

        plugins.install(GAIN.byteInputStream())
        settle()

        val entry = plugins.entries.single()
        assertEquals("2.1", entry.version)
        assertEquals("Somebody", entry.author)
        assertEquals("Turns it up.", entry.about)
        assertEquals(PLUGIN_ID, entry.pluginId)
    }

    @Test
    fun `a plug-in installed again does not bring back settings that were removed`() {
        val backend = EffectBackend(scope)
        val (dsp, plugins) = ops(backend)
        plugins.install(GAIN.byteInputStream())
        settle()
        dsp.setEnabled(PLUGIN_ID, true)
        dsp.setValue(PLUGIN_ID, "gain", 0.9f)

        plugins.remove(plugins.entries.single().id)
        settle()
        plugins.install(GAIN.byteInputStream())
        settle()

        val slot = dsp.rack[PLUGIN_ID]!!
        assertEquals("the reinstalled plug-in is switched off", false, slot.enabled)
        // no value is stored, so the lookup answers with the fallback it was given
        assertEquals("no gain value is stored", -1f, slot.params["gain", -1f], 0f)
    }

    @Test
    fun `another file claiming an installed plug-in asks first`() {
        val backend = EffectBackend(scope)
        val (_, plugins) = ops(backend)
        plugins.install(GAIN.byteInputStream())
        settle()

        plugins.install(GAIN.replace("2.1", "3.0").byteInputStream())
        settle()

        val asked = plugins.replacement
        assertNotNull("a version change asks before replacing", asked)
        assertEquals("2.1", asked!!.installed.version)
        assertEquals("3.0", asked.incoming.version)
        assertEquals("the installed version stays until the question is answered", "2.1", plugins.entries.single().version)
    }

    @Test
    fun `answering yes leaves one copy, at the new version`() {
        val backend = EffectBackend(scope)
        val (_, plugins) = ops(backend)
        plugins.install(GAIN.byteInputStream())
        settle()
        plugins.install(GAIN.replace("2.1", "3.0").byteInputStream())
        settle()

        plugins.replace()
        settle()

        assertNull(plugins.replacement)
        assertEquals(listOf("3.0"), plugins.entries.map { it.version })
        assertEquals("the backend holds one plug-in source", 1, backend.installed.size)
    }

    @Test
    fun `answering no changes nothing`() {
        val backend = EffectBackend(scope)
        val (_, plugins) = ops(backend)
        plugins.install(GAIN.byteInputStream())
        settle()
        plugins.install(GAIN.replace("2.1", "3.0").byteInputStream())
        settle()

        plugins.keep()

        assertNull(plugins.replacement)
        assertEquals(listOf("2.1"), plugins.entries.map { it.version })
    }

    @Test
    fun `the very same file again is not a replacement`() {
        val (_, plugins) = ops(EffectBackend(scope))
        plugins.install(GAIN.byteInputStream())
        settle()

        plugins.install(GAIN.byteInputStream())
        settle()

        assertNull("identical bytes ask no replacement question", plugins.replacement)
        assertEquals(1, plugins.entries.size)
    }

    @Test
    fun `a plug-in that was not here says it was added, with its name, version and author`() {
        val (_, plugins) = ops(EffectBackend(scope))

        plugins.install(GAIN.byteInputStream())
        settle()

        val added = plugins.added as PluginOps.Added.New
        assertEquals("Gain", added.entry.name)
        assertEquals("2.1", added.entry.version)
        assertEquals("Somebody", added.entry.author)
    }

    @Test
    fun `the very same file again says nothing changed`() {
        val (_, plugins) = ops(EffectBackend(scope))
        plugins.install(GAIN.byteInputStream())
        settle()
        plugins.seen()

        plugins.install(GAIN.byteInputStream())
        settle()

        assertTrue(plugins.added is PluginOps.Added.Unchanged)
        assertNull(plugins.problem)
    }

    @Test
    fun `a replacement says it updated, and from which version`() {
        val (_, plugins) = ops(EffectBackend(scope))
        plugins.install(GAIN.byteInputStream())
        settle()
        plugins.seen()
        plugins.install(GAIN.replace("2.1", "3.0").byteInputStream())
        settle()
        assertNull("no install notice shows until the question is answered", plugins.added)

        plugins.replace()
        settle()

        val updated = plugins.added as PluginOps.Added.Updated
        assertEquals("2.1", updated.previous.version)
        assertEquals("3.0", updated.entry.version)
    }

    @Test
    fun `a refusal takes away what the last install said, and reading either clears it`() {
        val (_, plugins) = ops(EffectBackend(scope))
        plugins.install(GAIN.byteInputStream())
        settle()

        plugins.install("not a plug-in".byteInputStream())
        settle()

        assertNull(plugins.added)
        assertNotNull(plugins.problem)
        plugins.seen()
        assertNull(plugins.problem)
    }

    @Test
    fun `a file that could not be opened is said the way a refusal is`() {
        val (_, plugins) = ops(EffectBackend(scope))

        plugins.unreadable("the file could not be opened")

        assertEquals("the file could not be opened", plugins.problem)
        assertTrue(plugins.entries.isEmpty())
    }

    @Test
    fun `a plug-in link offers what it points at, and installs nothing until Add`() {
        val asked = mutableListOf<String>()
        val (_, plugins) = ops(EffectBackend(scope)) { url -> GAIN.also { asked += url } }

        plugins.offerFrom("https://mattix.nl/andamp/extensions/plugins/gain.lua")
        settle()

        val offer = plugins.offer!!
        assertEquals(listOf("https://mattix.nl/andamp/extensions/plugins/gain.lua"), asked)
        assertEquals("Gain", offer.entry.name)
        assertEquals("2.1", offer.entry.version)
        assertEquals("Somebody", offer.entry.author)
        assertEquals("mattix.nl", offer.from)
        assertTrue("an offer installs nothing", plugins.entries.isEmpty())

        plugins.acceptOffer()
        settle()

        assertNull(plugins.offer)
        assertTrue(plugins.added is PluginOps.Added.New)
        assertEquals(listOf("Gain"), plugins.entries.map { it.name })
    }

    @Test
    fun `cancelling an offer installs nothing`() {
        val (_, plugins) = ops(EffectBackend(scope)) { GAIN }
        plugins.offerFrom("https://mattix.nl/andamp/extensions/plugins/gain.lua")
        settle()

        plugins.declineOffer()
        settle()

        assertNull(plugins.offer)
        assertTrue(plugins.entries.isEmpty())
    }

    @Test
    fun `a link that could not be downloaded, or holds no plug-in, says why and offers nothing`() {
        val (_, offline) = ops(EffectBackend(scope)) { throw java.io.IOException("no route to host") }
        offline.offerFrom("https://mattix.nl/andamp/extensions/plugins/gain.lua")
        settle()
        assertNull(offline.offer)
        assertTrue(offline.problem!!, offline.problem!!.contains("no route to host"))

        val (_, broken) = ops(EffectBackend(scope)) { "not a plug-in" }
        broken.offerFrom("https://mattix.nl/andamp/extensions/plugins/gain.lua")
        settle()
        assertNull(broken.offer)
        assertNotNull(broken.problem)
    }

    @Test
    fun `a freshly installed plug-in is waiting until the backend has settled`() {
        val (_, plugins) = ops(EffectBackend(scope))

        plugins.install(GAIN.byteInputStream())
        settle()

        assertTrue("a new plug-in waits on the backend", plugins.waiting)

        plugins.settled()

        assertTrue("a settled backend ends the wait", !plugins.waiting)
    }

    @Test
    fun `removing one takes the file with it`() {
        val backend = EffectBackend(scope)
        val (_, plugins) = ops(backend)
        plugins.install(GAIN.byteInputStream())
        settle()

        plugins.remove(plugins.entries.single().id)
        settle()

        assertEquals(emptyList<PluginEntry>(), plugins.entries)
        assertEquals("the backend holds no plug-in sources", emptyList<String>(), backend.installed)
    }

    @Test
    fun `a plug-in that only works at one rate is refused at install`() {
        // an install loads the script at every rate in PluginOps.RATES
        val (_, plugins) = ops(EffectBackend(scope))

        plugins.install(RATE_BOUND.byteInputStream())
        settle()

        assertTrue("the problem names the rate", plugins.problem?.contains("Hz") == true)
        assertEquals(emptyList<PluginEntry>(), plugins.entries)
    }

    @Test
    fun `a file that is not a plug-in is refused, and says why`() {
        val backend = EffectBackend(scope)
        val (_, plugins) = ops(backend)

        plugins.install("this is not lua at all !!!".byteInputStream())
        settle()

        assertTrue("the refusal gives a reason", plugins.problem?.isNotBlank() == true)
        assertEquals("no plug-in is stored", emptyList<PluginEntry>(), plugins.entries)
        assertEquals(emptyList<String>(), backend.installed)
    }

    @Test
    fun `a file too big to be a plug-in is refused, and says how big one may be`() {
        val backend = EffectBackend(scope)
        val (_, plugins) = ops(backend)

        // a valid plug-in with a comment that carries it past the cap
        plugins.install((GAIN + "\n-- " + "x".repeat(256 * 1024)).byteInputStream())
        settle()

        assertEquals("the problem states the size limit in KB", "a plug-in may not be larger than 256 KB", plugins.problem)
        assertEquals(emptyList<PluginEntry>(), plugins.entries)
        assertEquals(emptyList<String>(), backend.installed)
    }

    @Test
    fun `what was installed is offered again next launch`() {
        val first = EffectBackend(scope)
        ops(first).second.install(GAIN.byteInputStream())
        settle()

        // a second set of ops over the same storage is what a relaunch amounts to
        val next = EffectBackend(scope)
        val (dsp, plugins) = ops(next)
        plugins.start()
        settle()

        assertEquals(listOf("Gain"), plugins.entries.map { it.name })
        assertEquals(1, next.installed.size)
        assertNotNull(dsp.rack[PLUGIN_ID])
    }

    @Test
    fun `a file claiming an effect that ships is refused`() {
        val backend = EffectBackend(scope)
        val (_, plugins) = ops(backend)

        plugins.install(SHIPPED.byteInputStream())
        settle()

        assertTrue("the problem names the taken id: ${plugins.problem}", plugins.problem.orEmpty().contains(SHIPPED_ID))
        assertEquals("no plug-in is stored", emptyList<String>(), plugins.entries.map { it.name })
    }

    @Test
    fun `a link to a file claiming an effect that ships is refused before it is offered`() {
        val backend = EffectBackend(scope)
        val (_, plugins) = ops(backend, download = { SHIPPED })

        plugins.offerFrom("https://mattix.nl/andamp/extensions/plugins/preamp.lua")
        settle()

        assertNull("no offer is made", plugins.offer)
        assertTrue("the problem names the taken id: ${plugins.problem}", plugins.problem.orEmpty().contains(SHIPPED_ID))
    }

    private companion object {
        const val PLUGIN_ID = "com.example.gain"

        /** A plug-in the app ships, as the backend answers for it. */
        const val SHIPPED_ID = "nl.mattix.andamp.preamp"

        val SHIPPED =
            """
            plugin { id = "$SHIPPED_ID", name = "Preamp", version = "9.9", author = "Somebody" }

            function build(g, ctx)
              local o = {}
              for c = 0, ctx.channels - 1 do o[c] = g.input(c) end
              return o
            end
            """.trimIndent()

        val PLUGIN_SPEC =
            EffectSpec(
                id = PLUGIN_ID,
                name = "Gain",
                description = "Turns it up.",
                params = listOf(ParamSpec("gain", "Gain", default = 0.5f)),
            )

        /** Builds at 44.1 kHz and raises an error at any other rate. */
        val RATE_BOUND =
            """
            plugin { id = "com.example.picky", name = "Picky", version = "1" }

            function build(g, ctx)
              if ctx.sampleRate ~= 44100 then error("this plug-in only runs at 44100") end
              local o = {}
              for c = 0, ctx.channels - 1 do o[c] = g.input(c) end
              return o
            end
            """.trimIndent()

        /** A whole plug-in: one control, and one multiply per channel. */
        val GAIN =
            """
            plugin {
              id      = "$PLUGIN_ID",
              name    = "Gain",
              version = "2.1",
              author  = "Somebody",
              about   = "Turns it up.",
            }

            local gain = param.number { id = "gain", name = "Gain", min = 0, max = 1, default = 0.5 }

            function build(g, ctx)
              local out = {}
              for ch = 0, ctx.channels - 1 do
                out[ch] = g.mul(g.input(ch), gain)
              end
              return out
            end
            """.trimIndent()
    }
}
