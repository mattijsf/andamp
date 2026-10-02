// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import nl.mattix.andamp.backend.pack.PackCard
import nl.mattix.andamp.backend.pack.PackClient
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.packapi.PackApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A source that lives in an app of its own, as the player reaches it.
 *
 * The pack is installed as two components in another package, a service for the
 * bind action and an activity for the settings action. Nothing answers the bind
 * in a test JVM, so the source stays unbound, which is also its state when the
 * app has just started.
 *
 * The source under test names an imaginary service: the pack declares its own
 * scheme and label, and the player has none compiled in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackSourceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private lateinit var subject: PackSource

    @Before
    fun installAPackThatAnswersNothing() {
        val packages = shadowOf(app.packageManager)
        val service = ComponentName(PACK, "$PACK.PackService")
        packages.addServiceIfNotPresent(service)
        packages.addIntentFilterForService(service, IntentFilter(PackApi.ACTION_BIND))
        val settings = ComponentName(PACK, "$PACK.SettingsActivity")
        packages.addActivityIfNotPresent(settings)
        packages.addIntentFilterForActivity(
            settings,
            IntentFilter(PackApi.ACTION_SETTINGS).apply { addCategory(Intent.CATEGORY_DEFAULT) },
        )
        subject =
            PackSource(
                PACK,
                PackClient(app, fromPackage = PACK, scope = CoroutineScope(Dispatchers.Unconfined)),
                // the scheme and label stored from the last time the pack was
                // reached, which a launch uses before anything is bound
                PackCard(scheme = "moose", label = "Moose Music"),
            )
    }

    /**
     * A row's source is read from the scheme of its uri. The pack source's id
     * must equal the one [SourceForRows] reads off its rows.
     */
    @Test
    fun `a row belongs to the source whose scheme it carries`() {
        val row = Track("t", "A", "T", 1_000, uri = "moose:track:track-1")

        assertEquals(subject.source, SourceForRows.of(row))
    }

    /** The id is the scheme in upper case and the label is the pack's. */
    @Test
    fun `the source takes its id and label from the pack`() {
        assertEquals("MOOSE", subject.source.id)
        assertEquals("Moose Music", subject.source.label)
    }

    /**
     * The player opens the source's own settings screen. It resolves the
     * settings action within the pack's package and aims the intent at the
     * activity that answered. No binding is needed.
     */
    @Test
    fun `its settings are the pack's own screen, found by the action a source answers to`() {
        val opens = subject.settings(app)

        assertEquals(PackApi.ACTION_SETTINGS, opens?.action)
        assertEquals(PACK, opens?.component?.packageName ?: opens?.`package`)
    }

    /**
     * Until the pack is bound, the source reports no version, no update
     * address, no skin choice, no browse source and no backend, and does not
     * look signed in.
     */
    @Test
    fun `until the pack answers, the player claims nothing on its behalf`() {
        assertFalse("the source does not look signed in", subject.looksSignedIn(app))
        assertNull("the source reports no version", subject.version)
        assertNull("the source reports no update address", subject.updates)
        assertFalse("the source offers no skin choice", subject.skinnable)
        assertNull(subject.browse(app))
        assertNull(subject.backend(app, CoroutineScope(Dispatchers.Unconfined)))
    }

    private companion object {
        /** An example package name for the pack. */
        const val PACK = "com.example.moose"
    }
}
