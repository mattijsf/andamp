// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.RegionTxt
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.FakeSpectrum
import nl.mattix.andamp.state.FakeWave
import nl.mattix.andamp.state.RealOscilloscope
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.online.OnlineSkin
import nl.mattix.andamp.state.online.SkinPreviews
import nl.mattix.andamp.ui.rememberBackPull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

/**
 * A skin fetched to be looked at, and the demo player drawn inside it. A skin
 * that fails to arrive leaves the museum's picture in place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // a skin is bitmaps, and they have to decode
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class SkinPreviewTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var base: Skin

    private val museumSkin =
        OnlineSkin("abc", "Something.wsz", "https://example.invalid/s.png", "https://example.invalid/s.wsz", null, false)

    @Before
    fun setUp() {
        base = SkinLoader.loadBase(app)
    }

    private fun bytes() = app.assets.open("skins/AndAmp Light.wsz").use { it.readBytes() }

    @Test
    fun `asking for a skin fetches it once and keeps it`() {
        var fetches = 0
        val previews =
            SkinPreviews(scope, fallback = { base }, io = Dispatchers.Unconfined) {
                fetches++
                bytes()
            }

        previews.want(museumSkin)
        previews.want(museumSkin)

        assertEquals(1, fetches)
        assertNotNull("the fetched skin is kept", previews.of("abc"))
    }

    @Test
    fun `asking for another skin lets go of the one before it`() {
        // a fetch for a row that has scrolled past must not hold up the one on screen
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val previews =
            SkinPreviews(scope, fallback = { base }, io = Dispatchers.IO) { url ->
                if (url.endsWith("first.wsz")) {
                    started.countDown()
                    release.await()
                }
                bytes()
            }

        previews.want(museumSkin.copy(md5 = "first", downloadUrl = "https://example.invalid/first.wsz"))
        started.await()
        previews.want(museumSkin.copy(md5 = "second"))

        // the one on screen arrives without waiting for the one that scrolled by
        val until = System.currentTimeMillis() + 5_000
        while (previews.of("second") == null && System.currentTimeMillis() < until) Thread.sleep(20)
        release.countDown()

        assertNotNull("the skin on screen arrives without waiting for the earlier fetch", previews.of("second"))
    }

    @Test
    fun `a skin that fails to fetch yields no preview`() {
        val previews =
            SkinPreviews(scope, fallback = { base }, io = Dispatchers.Unconfined) { throw IOException("no network") }

        previews.want(museumSkin)

        assertNull(previews.of("abc"))
    }

    @Test
    fun `closing the browser lets go of every skin it was holding`() {
        val previews = SkinPreviews(scope, fallback = { base }, io = Dispatchers.Unconfined) { bytes() }
        previews.want(museumSkin)
        assertNotNull(previews.of("abc"))

        previews.forgetAll()

        assertNull(previews.of("abc"))
    }

    @Test
    fun `the viewer shows the museum's picture until the skin arrives, then the player itself`() {
        var arrived by mutableStateOf<Skin?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SkinViewer(
                    pull = rememberBackPull {},
                    settled = true,
                    skin = museumSkin,
                    live = arrived,
                    installed = false,
                    busy = false,
                    onInstall = {},
                    onUninstall = {},
                    onClose = {},
                )
            }
        }
        // the preview animates for as long as it is open, so the clock never goes idle
        assertEquals(0, compose.onAllNodesWithTag("online.live").fetchSemanticsNodes().size)
        compose.onNodeWithTag("online.enlarged").assertIsDisplayed()

        arrived = base
        compose.waitUntil(APPEAR_MS) { compose.onAllNodesWithTag("online.live").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("online.live").assertIsDisplayed()
        // it replaced the picture in place: the frame it lives in is still there
        compose.onNodeWithTag("online.enlarged").assertIsDisplayed()
    }

    /** The viewer on its own, with a skin that is not installed. */
    private fun showViewer() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SkinViewer(
                    pull = rememberBackPull {},
                    settled = true,
                    skin = museumSkin,
                    live = null,
                    installed = false,
                    busy = false,
                    onInstall = {},
                    onUninstall = {},
                    onClose = {},
                )
            }
        }
    }

    /** Whether the node tagged [tag] lies wholly on the screen. */
    private fun onScreen(tag: String): Boolean {
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val it = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return it.top >= screen.top && it.bottom <= screen.bottom && it.left >= screen.left && it.right <= screen.right
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /**
     * A skin's picture is taller than it is wide. On a screen that is wider than it is tall the
     * skin stands in the left half, as tall as the screen lets it, and what is said about it is
     * beside it. Under it, as on an upright screen, there would be no room for both.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
    fun `on a tablet held sideways the skin stands beside its name and the install button`() {
        showViewer()

        assertTrue("the skin is on the screen", onScreen("online.enlarged"))
        assertTrue("the install button is on the screen", onScreen("online.install.abc"))
        compose.onNodeWithTag("online.install.abc").assertIsDisplayed()
        val skin = bounds("online.enlarged")
        val install = bounds("online.install.abc")
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("the button is to the right of the skin", install.left >= skin.right)
        assertTrue("the skin is in the left half", skin.right <= screen.width / 2)
        // 1600 px high: four skins' height is 1392, and under a column of text only three fit
        assertEquals("the skin uses the screen's height", 4 * 275, skin.width.toInt())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
    fun `on a sideways screen the skin stays clear of the button that closes the page`() {
        showViewer()

        val skin = bounds("online.enlarged")
        val close = bounds("online.close")
        assertTrue("the skin starts under the closing button", skin.top >= close.bottom)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h1280dp-xhdpi")
    fun `on a tablet held upright the skin is on top and the install button under it`() {
        showViewer()

        assertTrue("the skin is on the screen", onScreen("online.enlarged"))
        assertTrue("the install button is on the screen", onScreen("online.install.abc"))
        assertTrue(bounds("online.install.abc").top >= bounds("online.enlarged").bottom)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-xxhdpi")
    fun `on a phone held sideways the skin and the install button are side by side too`() {
        showViewer()

        assertTrue("the skin is on the screen", onScreen("online.enlarged"))
        assertTrue("the install button is on the screen", onScreen("online.install.abc"))
        assertTrue(bounds("online.install.abc").left >= bounds("online.enlarged").right)
    }

    /** A phone held upright has room for the widest skin its width allows, and keeps it. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
    fun `on a phone held upright the skin is still as wide as whole pixels allow`() {
        showViewer()

        val skin = compose.onNodeWithTag("online.enlarged").fetchSemanticsNode().boundsInRoot
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(wholePlayerWidth(screen.width.toInt()), skin.width.toInt())
        assertTrue(onScreen("online.install.abc"))
    }

    @Test
    // a tall screen: a shift only shows where the page does not overflow
    @Config(sdk = [35], qualifiers = "w411dp-h1600dp-mdpi")
    fun `the note the artist packed is shown, and neither moves nor resizes the skin`() {
        // a readme runs from one line to several hundred, and the skin above it
        // has to be drawn at the same size either way
        var arrived by mutableStateOf<Skin?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SkinViewer(
                    pull = rememberBackPull {},
                    settled = true,
                    skin = museumSkin,
                    live = arrived,
                    installed = false,
                    busy = false,
                    onInstall = {},
                    onUninstall = {},
                    onClose = {},
                )
            }
        }
        val stage = compose.onNodeWithTag("online.enlarged").fetchSemanticsNode()
        val withoutNote = stage.size
        val stoodAt = stage.positionInRoot

        arrived = skinNoted("Hexagon Amp\nby A. N. Other, 1999")
        compose.waitUntil(APPEAR_MS) { compose.onAllNodesWithTag("online.live").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("online.readme").assertIsDisplayed()

        val after = compose.onNodeWithTag("online.enlarged").fetchSemanticsNode()
        assertEquals("the skin keeps its size with a note under it", withoutNote, after.size)
        assertEquals("the skin keeps its position when the note arrives", stoodAt, after.positionInRoot)
    }

    @Test
    fun `the preview draws the same signal as the app's fake spectrum and wave`() {
        // run for many frames: two frames are not enough to tell two signals apart
        val preview = SkinDemo.state()
        val shared = WinampState()
        var millis = 0L
        repeat(FRAMES) {
            SkinDemo.frame(preview, millis)
            FakeSpectrum.step(shared, millis / 1000.0)
            FakeWave.step(shared, millis / 1000.0)
            millis += FRAME_MS
        }

        assertArrayEquals("the preview draws the app's spectrum bars", shared.visLevels, preview.visLevels, 0f)
        assertArrayEquals("the preview draws the app's spectrum peaks", shared.visPeaks, preview.visPeaks, 0f)
        assertArrayEquals("the preview draws the app's wave trace", shared.visWave, preview.visWave)
    }

    @Test
    fun `stopping clears the window and pausing holds the last frame`() {
        val state = SkinDemo.state()
        SkinDemo.frame(state, 1_000)
        val held = state.visLevels.toList()

        state.transport = Transport.Paused
        SkinDemo.frame(state, 2_000)

        assertEquals("pause holds the last frame", held, state.visLevels.toList())

        state.transport = Transport.Stopped
        SkinDemo.frame(state, 3_000)

        assertTrue("stop clears the bars", state.visLevels.all { it == 0f })
        assertTrue("stop flattens the trace", state.visWave.all { it == RealOscilloscope.CENTER_ROW })
    }

    /** The bundled skin, with a note packed alongside it. */
    private fun skinNoted(note: String): Skin {
        val members = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory) members[entry.name.substringAfterLast('/')] = zip.readBytes()
            }
        }
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            (members + ("readme.txt" to note.toByteArray(Charsets.ISO_8859_1))).forEach { (name, data) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return SkinLoader.load(out.toByteArray().inputStream(), fallback = base, name = "noted")
    }

    @Test
    fun `the player only draws once the skin has stopped moving`() {
        // the player draws in whole virtual pixels and a flight scales through
        // every fraction in between, so the picture travels and the player
        // draws once it has landed
        var landed by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SkinViewer(
                    pull = rememberBackPull {},
                    settled = landed,
                    skin = museumSkin,
                    live = base,
                    installed = false,
                    busy = false,
                    onInstall = {},
                    onUninstall = {},
                    onClose = {},
                )
            }
        }

        assertEquals(
            "the player does not draw while the skin is in flight",
            0,
            compose.onAllNodesWithTag("online.live").fetchSemanticsNodes().size,
        )

        landed = true
        compose.waitUntil(APPEAR_MS) { compose.onAllNodesWithTag("online.live").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("online.live").assertIsDisplayed()
    }

    private companion object {
        /** Long enough for the fade to finish. */
        const val APPEAR_MS = 5_000L

        /** About two seconds of frames. */
        const val FRAMES = 120
        const val FRAME_MS = 16L
    }

    @Test
    fun `every control changes the demo state`() {
        val state = SkinDemo.state()
        val player = SkinDemo.Player(state)

        player.stop()
        assertEquals(Transport.Stopped, state.transport)
        player.play()
        assertEquals(Transport.Playing, state.transport)

        player.next()
        assertEquals(1, state.currentIndex)
        player.previous()
        assertEquals(0, state.currentIndex)

        player.setVolume(0.5f)
        assertEquals(50, state.volume)
        player.setBand(2, 60)
        assertEquals(60, state.eqBands[2])
        player.resetBands()
        assertTrue("reset centers every band", state.eqBands.all { it == nl.mattix.andamp.state.EqOps.CENTER })

        player.selectTrack(2)
        assertEquals(setOf(2), state.selectedRows)
    }

    @Test
    fun `stopping stops the clock, and playing starts it again`() {
        val state = SkinDemo.state()
        val player = SkinDemo.Player(state)
        SkinDemo.frame(state, 9_000)
        val whilePlaying = state.currentTimeSec

        player.stop()
        SkinDemo.frame(state, 15_000)
        assertEquals("stop resets the clock to zero", 0, state.currentTimeSec)

        player.play()
        SkinDemo.frame(state, 21_000)
        assertTrue("play starts the clock again", state.currentTimeSec != whilePlaying)
    }

    @Test
    fun `withRegions gives a skin a main-window region, and a skin without one has none`() {
        // the bundled skin ships a region of its own, so the skin without one
        // is made here
        val plain = SkinLoader.loadBase(app).withRegions(RegionTxt.parse(null))
        val cut =
            plain.withRegions(
                RegionTxt.parse(
                    """
                    [Normal]
                    NumPoints=4
                    PointList=0,0,137,0,137,116,0,116
                    """.trimIndent(),
                ),
            )

        assertTrue("a skin with no region has no shape", plain.regions[RegionTxt.Window.MAIN].isNullOrEmpty())
        assertNotNull("a skin with a region has a main-window shape", cut.regions[RegionTxt.Window.MAIN])
    }

    @Test
    fun `the demo stands in the state the museum photographed`() {
        // the picture the preview fades out of was taken in webamp's
        // screenshotInitialState: the same four tracks, lengths and bitrate,
        // the third one selected, three seconds in, and that EQ curve
        val state = SkinDemo.state()

        assertEquals(listOf(5, 191, 240, 300), state.playlist.map { (it.durationMs / 1000).toInt() })
        assertEquals("Llama Whipping Intro", state.playlist.first().title)
        assertEquals(listOf(128), state.playlist.map { it.bitrateKbps }.distinct())
        assertEquals(listOf(44), state.playlist.map { it.sampleRateKhz }.distinct())
        assertEquals(setOf(2), state.selectedRows)
        assertEquals(3, state.currentTimeSec)
        // 52, 74, 83, 91, 80, 54, 23, 19, 34, 75 and a preamp of 56 on
        // webamp's nought-to-a-hundred, in the sixty-three Winamp's art draws
        assertEquals(listOf(33, 47, 52, 57, 50, 34, 14, 12, 21, 47), state.eqBands.toList())
        assertEquals(35, state.preamp)
    }

    @Test
    fun `the demo is playing and its frames move`() {
        val state = SkinDemo.state()

        assertEquals(Transport.Playing, state.transport)
        assertTrue("the demo has songs", state.playlist.isNotEmpty())

        SkinDemo.frame(state, millis = 4_000)
        val moved = state.visLevels.toList()
        SkinDemo.frame(state, millis = 4_500)

        assertTrue("the bars move between frames", moved != state.visLevels.toList())
        assertTrue("the clock runs", state.currentTimeSec > 0)
    }
}
