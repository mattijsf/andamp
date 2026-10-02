// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The single "is anybody looking" signal the expensive visuals hang off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WhileOnScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val owner = TestOwner()
    private val seen = mutableListOf<Boolean>()

    private fun show(present: () -> Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (present()) WhileOnScreen { seen += it }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a visible composition is on screen from the first report`() {
        owner.registry.currentState = Lifecycle.State.RESUMED

        show { true }

        assertEquals(listOf(true), seen)
    }

    @Test
    fun `stopping the activity reports off screen, starting it reports on again`() {
        owner.registry.currentState = Lifecycle.State.RESUMED
        show { true }

        owner.registry.currentState = Lifecycle.State.CREATED
        compose.waitForIdle()

        assertEquals(false, seen.last())

        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.waitForIdle()

        assertEquals(true, seen.last())
    }

    @Test
    fun `a window behind a dialog is still on screen`() {
        owner.registry.currentState = Lifecycle.State.RESUMED
        show { true }

        owner.registry.currentState = Lifecycle.State.STARTED // ON_PAUSE only
        compose.waitForIdle()

        assertEquals(true, seen.last())
    }

    @Test
    fun `leaving the tree reports off screen`() {
        owner.registry.currentState = Lifecycle.State.RESUMED
        val present = mutableStateOf(true)
        show { present.value }

        present.value = false
        compose.waitForIdle()

        assertEquals(false, seen.last())
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry
    }
}
