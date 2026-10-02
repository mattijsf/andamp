// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.packapi.PackApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Which sources a phone has, when it has more than one: two source apps are two
 * sources, each under the id and label its own app gave.
 *
 * No binding happens in a test JVM, so each pack's scheme and label are seeded
 * in the preferences file the client stores them in after reaching the pack. A
 * launch reads the same file before anything is bound.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackSourcesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun startFromNothing() = PackSources.forget()

    @After
    fun leaveNothingBehind() = PackSources.forget()

    @Test
    fun `a phone with no source app has only the phone`() {
        PackSources.attach(app)

        assertTrue(PackSources.found.isEmpty())
        assertEquals(listOf(MusicSource.LOCAL), MusicSource.present())
    }

    @Test
    fun `two source apps are two sources, each named by itself`() {
        install(MOOSE, scheme = "moose", label = "Moose Music")
        install(HERON, scheme = "heron", label = "Heron")
        PackSources.attach(app)

        assertEquals(listOf("HERON", "MOOSE"), PackSources.found.map { it.source.id })
        assertEquals(listOf("Heron", "Moose Music"), PackSources.found.map { it.source.label })
    }

    /** [MusicSource.present] is the list the rest of the app reads. */
    @Test
    fun `an installed source is listed after the phone`() {
        install(MOOSE, scheme = "moose", label = "Moose Music")
        PackSources.attach(app)

        assertEquals(listOf(MusicSource.LOCAL, MusicSource("MOOSE", "Moose Music")), MusicSource.present())
        assertEquals(1, PackSources.found.size)
    }

    /**
     * A scheme written with a trailing colon gives the same id as one written
     * without. A playlist records the id, so the two spellings must not differ.
     */
    @Test
    fun `a scheme written either way is the same id`() {
        install(MOOSE, scheme = "moose:", label = "Moose Music")
        PackSources.attach(app)

        assertEquals(listOf("MOOSE"), PackSources.found.map { it.source.id })
    }

    /** A pack with no stored scheme and label has no name to show, so it is not listed. */
    @Test
    fun `a source app that has never answered is not listed`() {
        install(MOOSE, scheme = "", label = "")
        PackSources.attach(app)

        assertTrue(PackSources.found.isEmpty())
    }

    /**
     * Puts a source app on this phone: a service behind the bind action, and
     * the scheme and label stored for it.
     */
    private fun install(
        from: String,
        scheme: String,
        label: String,
    ) {
        val packages = shadowOf(app.packageManager)
        val service = ComponentName(from, "$from.PackService")
        packages.addServiceIfNotPresent(service)
        packages.addIntentFilterForService(service, IntentFilter(PackApi.ACTION_BIND))
        app
            .getSharedPreferences("pack.${PackApi.ACTION_BIND}.$from", Context.MODE_PRIVATE)
            .edit()
            .putString("scheme", scheme)
            .putString("label", label)
            .commit()
    }

    private companion object {
        /** Two example packages. The tests install MOOSE first; the list is sorted by package name. */
        const val MOOSE = "com.example.moose"
        const val HERON = "com.example.heron"
    }
}
