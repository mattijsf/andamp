// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.ui.rememberLibraryAccess
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The music permission as This Phone's page is told it, followed out to the
 * system's settings and back.
 *
 * Nothing of the app's changes while the listener is away, so the status is
 * read again on the way back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneAccessTest {
    @get:Rule
    val compose = createComposeRule()

    private val owner = TestOwner()

    @Test
    fun `access turned on in the system's settings is seen on the way back`() {
        owner.registry.currentState = Lifecycle.State.RESUMED
        var permitted = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Text(rememberLibraryAccess(canRead = { permitted }, onGrant = {}).status.name)
            }
        }
        compose.onNodeWithText(LibraryAccess.ASKABLE.name).assertExists()

        // out to the settings screen, where access is turned on, and back
        owner.registry.currentState = Lifecycle.State.CREATED
        permitted = true
        owner.registry.currentState = Lifecycle.State.RESUMED

        compose.onNodeWithText(LibraryAccess.GRANTED.name).assertExists()
    }

    /** A permission registry whose prompt answers [answer], counting how often it is put up. */
    private class Prompt(
        private val answer: () -> Boolean,
    ) : ActivityResultRegistryOwner {
        var shown = 0

        override val activityResultRegistry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: androidx.core.app.ActivityOptionsCompat?,
                ) {
                    shown++
                    dispatchResult(requestCode, answer())
                }
            }
    }

    /** What the handle did for one press, and the press itself. */
    private class Press {
        var explained = 0
        var toSettings: (() -> Unit)? = null
        var played = 0
        lateinit var ask: () -> Unit
    }

    private fun pressWith(
        prompt: Prompt,
        rationale: () -> Boolean,
        permitted: () -> Boolean = { false },
    ): Press {
        owner.registry.currentState = Lifecycle.State.RESUMED
        val press = Press()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalActivityResultRegistryOwner provides prompt) {
                val access =
                    rememberLibraryAccess(
                        canRead = permitted,
                        onGrant = {},
                        explain = { ask ->
                            press.explained++
                            ask()
                        },
                        cannotAsk = { press.toSettings = it },
                        rationale = rationale,
                    )
                Text(access.status.name)
                press.ask = { access.ask { press.played++ } }
            }
        }
        return press
    }

    @Test
    fun `never refused, the system's prompt comes straight up`() {
        val prompt = Prompt { true }
        val press = pressWith(prompt, rationale = { false })

        compose.runOnIdle { press.ask() }
        compose.waitForIdle()

        assertEquals(1, prompt.shown)
        assertEquals("no explanation comes before the prompt", 0, press.explained)
        assertEquals(1, press.played)
    }

    @Test
    fun `refused once, it is explained before the prompt`() {
        val prompt = Prompt { false }
        val press = pressWith(prompt, rationale = { true })

        compose.runOnIdle { press.ask() }
        compose.waitForIdle()

        assertEquals(1, press.explained)
        assertEquals(1, prompt.shown)
        assertEquals("a refusal with a rationale left offers no settings", null, press.toSettings)
        compose.onNodeWithText(LibraryAccess.ASKABLE.name).assertExists()
    }

    /**
     * Refused with no rationale left: Android will not ask again. The handle
     * offers the settings screen, and the song plays when it is allowed there.
     */
    @Test
    fun `refused for good leads to settings, and the song follows the way back`() {
        var permitted = false
        val press = pressWith(Prompt { false }, rationale = { false }, permitted = { permitted })

        compose.runOnIdle { press.ask() }
        compose.waitForIdle()
        val settings = checkNotNull(press.toSettings) { "a final refusal offers the settings screen" }
        compose.onNodeWithText(LibraryAccess.BLOCKED.name).assertExists()

        // out to the settings screen, where it is allowed, and back
        runCatching { settings() } // no activity to start it from in a test
        owner.registry.currentState = Lifecycle.State.CREATED
        permitted = true
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.waitForIdle()

        assertEquals("the song plays once, on the way back", 1, press.played)
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry
    }
}
