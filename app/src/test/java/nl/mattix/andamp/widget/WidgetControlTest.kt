// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.TransportStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A flag pressed on the widget with nothing playing.
 *
 * Shuffle and repeat say what should happen next, so unlike a position or a
 * volume they mean something when no player is attached, and the press is
 * kept until one is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetControlTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    /**
     * WidgetControl is a process-wide object, so a player left attached by an
     * earlier test is detached first.
     */
    @Before
    fun withNoPlayer() {
        WidgetControl.detach()
        // likewise a cold-start source left behind by an earlier test
        WidgetControl.coldStart = { null }
    }

    @Test
    fun `a press with no player is kept where the app looks at launch`() {
        TransportStore(app).saveSettings(TransportStore(app).load().copy(shuffle = false, repeat = false))

        WidgetControl.setShuffle(app, true)

        assertTrue("the next launch shuffles", TransportStore(app).load().shuffle)
    }

    @Test
    fun `the snapshot shows the flag at once`() {
        WidgetSnapshot.write(app, WidgetSnapshot(repeat = false))

        WidgetControl.setRepeat(app, true)

        assertTrue("the snapshot shows repeat on", WidgetSnapshot.read(app).repeat)
    }

    @Test
    fun `setting one flag leaves the other alone`() {
        TransportStore(app).saveSettings(TransportStore(app).load().copy(shuffle = true, repeat = false))

        WidgetControl.setRepeat(app, true)

        val stored = TransportStore(app).load()
        assertEquals("shuffle stays on", true, stored.shuffle)
        assertEquals(true, stored.repeat)
    }

    @Test
    fun `a press on a stale snapshot leaves it stopped`() {
        // writing re-stamps the snapshot's age, so it is read as expired first;
        // otherwise a press would renew "Playing" with nothing playing
        WidgetSnapshot.write(app, WidgetSnapshot(transport = Transport.Playing, writtenAt = 0))
        // Robolectric's clock starts near zero, where nothing is old yet
        org.robolectric.shadows.ShadowSystemClock
            .advanceBy(java.time.Duration.ofMillis(WidgetSnapshot.STALE_AFTER_MS + 1))

        WidgetControl.setShuffle(app, true)

        assertEquals(Transport.Stopped, WidgetSnapshot.read(app).transport)
        assertTrue(WidgetSnapshot.read(app).shuffle)
    }

    @Test
    fun `a transport press with a player attached calls the facade and starts no service`() {
        // a media key event would reach one backend's own player through its
        // session and leave the app's state behind. A verb on the facade is
        // the one the player's own button presses, whichever backend is behind it
        val facade = PlayerFacade(MockBackend(FakeTracks.tracks, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)))
        WidgetControl.attach(facade)

        WidgetControl.press(app, WidgetButton.PLAY, target = false)
        assertEquals(Transport.Playing, facade.state.value.transport)

        WidgetControl.press(app, WidgetButton.STOP, target = false)

        assertEquals(Transport.Stopped, facade.state.value.transport)
        assertNull(
            "a press with a player attached starts no service",
            org.robolectric.Shadows
                .shadowOf(app as android.app.Application)
                .nextStartedService,
        )
    }

    @Test
    fun `a transport press with no player builds one`() {
        // after a force-quit the process starts for the broadcast with no
        // player and no session, and has to build one
        val built = PlayerFacade(MockBackend(FakeTracks.tracks, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)))
        var asked = 0
        WidgetControl.coldStart = {
            asked++
            built
        }

        WidgetControl.press(app, WidgetButton.PLAY, target = false)

        assertEquals("the press asks the cold start once", 1, asked)
        assertEquals(Transport.Playing, built.state.value.transport)
    }

    @Test
    fun `a press whose cold start fails does not throw`() {
        // a widget press arrives in a broadcast receiver and must not take the
        // process down
        WidgetControl.coldStart = { error("the cold start fails") }

        WidgetControl.press(app, WidgetButton.PLAY, target = false)
    }

    @Test
    fun `stopping the mirror lets go of the player, and starting it takes hold again`() {
        // a stopped mirror lets go of the player, and starting it again takes hold
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val facade = PlayerFacade(MockBackend(FakeTracks.tracks, scope))
        val ops =
            nl.mattix.andamp.widget
                .WidgetOps(app, facade, scope)

        ops.start()
        WidgetControl.press(app, WidgetButton.PLAY, target = false)
        assertEquals(Transport.Playing, facade.state.value.transport)

        ops.stop()
        facade.stop()
        WidgetControl.press(app, WidgetButton.PLAY, target = false)
        assertEquals("a stopped mirror lets go of the player", Transport.Stopped, facade.state.value.transport)

        ops.start()
        WidgetControl.press(app, WidgetButton.PLAY, target = false)
        assertEquals("starting again takes hold of the player", Transport.Playing, facade.state.value.transport)

        scope.cancel()
    }
}
