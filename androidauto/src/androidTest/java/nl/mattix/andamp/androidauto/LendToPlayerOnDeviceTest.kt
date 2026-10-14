// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.androidauto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.mattix.andamp.core.remotewindow.IRemoteWindow
import nl.mattix.andamp.core.remotewindow.IRemoteWindowWatcher
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The loan without a car: a surface of this test's own is lent to the player installed on the
 * device, and what the player draws on it is read back.
 *
 * The pictures are also written to this app's files directory, to be looked at. The test is
 * skipped on a device without the player. Each test starts with the window drawn sharp, not
 * filling the screen, and switches D off first when it is on. The list is only tried on a
 * player that holds more songs than fit the screen: with fewer there is nothing to scroll to
 * and no other row to pick.
 *
 * Method names are plain identifiers: a name with spaces is illegal in DEX below version 040.
 */
@RunWith(AndroidJUnit4::class)
class LendToPlayerOnDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reading = HandlerThread("lent-surface").apply { start() }
    private val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3)
    private val latest = AtomicReference<Bitmap?>()
    private val connected = CountDownLatch(1)
    private var player: IRemoteWindow? = null

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                player = IRemoteWindow.Stub.asInterface(binder)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                player = null
            }
        }

    @Before
    fun bind() {
        val id = RemoteWindowLink.playerOf(context.packageName)
        assumeTrue("the player is installed", installed(id))
        reader.setOnImageAvailableListener({ from -> keep(from) }, Handler(reading.looper))
        val toPlayer = Intent(RemoteWindowLink.ACTION).setPackage(id)
        assertTrue("the player's service is found", context.bindService(toPlayer, connection, Context.BIND_AUTO_CREATE))
        assertTrue("the player answers", connected.await(WAIT_S, TimeUnit.SECONDS))
    }

    @After
    fun letGo() {
        player?.hide()
        runCatching { context.unbindService(connection) }
        // the reading thread is stopped before the reader goes: a picture still being copied
        // out of a closed reader is read from memory that has been given back
        reader.setOnImageAvailableListener(null, null)
        reading.quitSafely()
        reading.join(WAIT_S * 1_000)
        reader.close()
    }

    @Test
    fun thePlayerDrawsItsMainWindowOnTheLentSurface() {
        showSharp()

        val shown = pictureAfter(SETTLE_MS, "lent-1-shown.png")

        assertFalse("the window is on the surface", allBlack(shown, WINDOW_LEFT, WINDOW_TOP, WINDOW_W, WINDOW_H))
        assertTrue("the room around it is black", allBlack(shown, 0, 0, WINDOW_LEFT, HEIGHT))
    }

    @Test
    fun aTapOnPlayStartsTheSong() {
        showSharp()
        val stopped = pictureAfter(SETTLE_MS, "lent-2-before-play.png")

        player?.tap(WINDOW_LEFT + PLAY_X * SCALE, WINDOW_TOP + PLAY_Y * SCALE)
        val playing = pictureAfter(SETTLE_MS, "lent-3-after-play.png")
        // left as it was found
        player?.tap(WINDOW_LEFT + STOP_X * SCALE, WINDOW_TOP + PLAY_Y * SCALE)
        pictureAfter(SETTLE_MS, "lent-4-after-stop.png")

        assertFalse("the picture changes when the song starts", stopped.sameAs(playing))
    }

    @Test
    fun theEqButtonSwapsToTheEqualizerAndItsCloseSwapsBack() {
        showSharp()
        val main = buttonsOf(pictureAfter(SETTLE_MS, "lent-6-main.png"))

        player?.tap(WINDOW_LEFT + EQ_X * SCALE, WINDOW_TOP + EQ_Y * SCALE)
        val equalizer = buttonsOf(pictureAfter(SETTLE_MS, "lent-7-equalizer.png"))
        // a touch beside the X, which is too small a thing to hit on a car's screen
        player?.tap(WINDOW_LEFT + CLOSE_X * SCALE, WINDOW_TOP + CLOSE_Y * SCALE)
        val back = buttonsOf(pictureAfter(SETTLE_MS, "lent-8-main-again.png"))

        assertFalse("the equalizer has taken the main window's place", main.contentEquals(equalizer))
        assertTrue("the main window is back", main.contentEquals(back))
    }

    @Test
    fun thePlButtonOpensAListToPickASongFrom() {
        showSharp()
        val main = buttonsOf(pictureAfter(SETTLE_MS, "lent-9-main.png"))

        player?.tap(WINDOW_LEFT + PL_X * SCALE, WINDOW_TOP + EQ_Y * SCALE)
        Thread.sleep(SETTLE_MS)
        // a long drag down the screen brings the list to its top, whichever row it opened on
        player?.drag(0f, FAR)
        val top = pictureAfter(SETTLE_MS, "lent-10-list-top.png")
        player?.drag(0f, -2f * ROW_PX)
        val scrolled = pictureAfter(SETTLE_MS, "lent-11-list-scrolled.png")
        if (top.sameAs(scrolled)) {
            // back to the main window, as it was found
            player?.tap(LIST_CLOSE_X, LIST_TOP_PX / 2f)
            Thread.sleep(SETTLE_MS)
        }
        assumeTrue("the player holds more songs than fit the screen", !top.sameAs(scrolled))
        // the second row from the top of what is showing: first its name, then its play button
        player?.tap(WIDTH / 2f, LIST_TOP_PX + 1.5f * ROW_PX)
        val touched = pictureAfter(SETTLE_MS, "lent-12-list-touched.png")
        player?.tap(LIST_PLAY_X, LIST_TOP_PX + 1.5f * ROW_PX)
        val picked = pictureAfter(SETTLE_MS, "lent-12-list-picked.png")
        player?.tap(LIST_CLOSE_X, LIST_TOP_PX / 2f)
        val back = pictureAfter(SETTLE_MS, "lent-13-main-playing.png")
        // left as it was found
        player?.tap(WINDOW_LEFT + STOP_X * SCALE, WINDOW_TOP + PLAY_Y * SCALE)
        Thread.sleep(SETTLE_MS)

        assertFalse("the list has taken the main window's place", main.contentEquals(buttonsOf(top)))
        assertTrue("a touch on a row's name plays nothing", scrolled.sameAs(touched))
        assertFalse("the row whose button was pressed is shown as the one that is on", scrolled.sameAs(picked))
        assertTrue("the main window is back", main.contentEquals(buttonsOf(back)))
    }

    @Test
    fun theDInTheWindowMakesItFillTheScreenAndBack() {
        showSharp()
        pictureAfter(SETTLE_MS, "lent-14-sharp.png")

        player?.tap(WINDOW_LEFT + D_X * SCALE, WINDOW_TOP + D_Y * SCALE)
        val filled = pictureAfter(SETTLE_MS, "lent-15-filled.png")
        // filling, the window is as wide as the screen: 1280 / 275 of its pixels to one
        val per = WIDTH / 275f
        player?.tap(D_X * per, (HEIGHT - 116 * per) / 2f + D_Y * per)
        val sharp = pictureAfter(SETTLE_MS, "lent-16-sharp-again.png")

        assertFalse("filling, the window reaches the screen's left edge", allBlack(filled, 0, 0, WINDOW_LEFT, HEIGHT))
        assertTrue("sharp again, there is black beside it", allBlack(sharp, 0, 0, WINDOW_LEFT, HEIGHT))
    }

    /**
     * A drag is reported by its distance alone, so it moves the slider the last tap landed
     * on, wherever on the surface the finger is. The balance is used because its middle is
     * where it stands on nearly every player, and a tap there leaves it so.
     */
    @Test
    fun aDragMovesTheSliderThatWasTapped() {
        showSharp()
        // the window has to be up before a tap can land on it
        pictureAfter(SETTLE_MS, "lent-17-balance-before.png")
        player?.tap(WINDOW_LEFT + BALANCE_X * SCALE, WINDOW_TOP + BALANCE_Y * SCALE)
        val middle = balanceOf(pictureAfter(SETTLE_MS, "lent-17-balance-middle.png"))

        player?.drag(-BALANCE_DRAG * SCALE, 0f)
        val left = balanceOf(pictureAfter(SETTLE_MS, "lent-18-balance-left.png"))
        // left as it was found: the same way back
        player?.drag(BALANCE_DRAG * SCALE, 0f)
        val back = balanceOf(pictureAfter(SETTLE_MS, "lent-19-balance-back.png"))

        assertFalse("the drag moved the slider", middle.contentEquals(left))
        assertTrue("and the drag back put it in the middle again", middle.contentEquals(back))
    }

    @Test
    fun theButtonBringsUpTheListAndTakesItAway() {
        showSharp()
        val main = buttonsOf(pictureAfter(SETTLE_MS, "lent-20-main.png"))

        player?.listOrBack()
        val list = buttonsOf(pictureAfter(SETTLE_MS, "lent-21-list.png"))
        player?.listOrBack()
        val back = buttonsOf(pictureAfter(SETTLE_MS, "lent-22-main-again.png"))

        assertFalse("the list has taken the main window's place", main.contentEquals(list))
        assertTrue("the main window is back", main.contentEquals(back))
    }

    @Test
    fun theButtonTakesTheEqualizerAwayToo() {
        showSharp()
        val main = buttonsOf(pictureAfter(SETTLE_MS, "lent-26-main.png"))

        player?.tap(WINDOW_LEFT + EQ_X * SCALE, WINDOW_TOP + EQ_Y * SCALE)
        pictureAfter(SETTLE_MS, "lent-27-equalizer.png")
        player?.listOrBack()
        val back = buttonsOf(pictureAfter(SETTLE_MS, "lent-28-main-again.png"))

        assertTrue("the main window is back", main.contentEquals(back))
    }

    @Test
    fun theOwnerIsToldWhetherTheMainWindowIsUp() {
        val heard = LinkedBlockingQueue<Boolean>()
        val watcher =
            object : IRemoteWindowWatcher.Stub() {
                override fun mainUp(up: Boolean) {
                    heard += up
                }
            }
        showSharp()

        player?.watch(watcher)
        val first = heard.poll(WAIT_S, TimeUnit.SECONDS)
        player?.tap(WINDOW_LEFT + EQ_X * SCALE, WINDOW_TOP + EQ_Y * SCALE)
        val equalizer = heard.poll(WAIT_S, TimeUnit.SECONDS)
        player?.listOrBack()
        val back = heard.poll(WAIT_S, TimeUnit.SECONDS)

        assertEquals(listOf(true, false, true), listOf(first, equalizer, back))
    }

    /** The owner keeps room free for buttons it shows now and then; filling, the window takes it too. */
    @Test
    fun filledTheWindowTakesTheWholeSurfaceWhateverIsKept() {
        showSharp()
        player?.keepTo(WIDTH / 2, 0, WIDTH, HEIGHT)
        val kept = pictureAfter(SETTLE_MS, "lent-23-kept-right.png")
        // in the right half the window is drawn two to one, in the middle of that half
        val left = WIDTH / 2 + (WIDTH / 2 - 275 * 2) / 2
        val top = (HEIGHT - 116 * 2) / 2
        player?.tap(left + D_X * 2, top + D_Y * 2)
        val filled = pictureAfter(SETTLE_MS, "lent-24-filled-whole.png")
        // left as it was found
        val per = WIDTH / 275f
        player?.tap(D_X * per, (HEIGHT - 116 * per) / 2f + D_Y * per)
        pictureAfter(SETTLE_MS, "lent-25-sharp-again.png")

        assertTrue("kept to the right half, the left half is black", allBlack(kept, 0, 0, WIDTH / 2, HEIGHT))
        assertFalse("filling, the window reaches the left edge all the same", allBlack(filled, 0, 0, WINDOW_LEFT, HEIGHT))
    }

    @Test
    fun theWindowKeepsToThePartItIsGiven() {
        showSharp()
        player?.keepTo(WIDTH / 2, 0, WIDTH, HEIGHT)

        val kept = pictureAfter(SETTLE_MS, "lent-5-kept-right.png")

        assertTrue("the left half is left alone", allBlack(kept, 0, 0, WIDTH / 2, HEIGHT))
        assertFalse("the window is in the right half", allBlack(kept, WIDTH / 2, 0, WIDTH / 2, HEIGHT))
    }

    /** Lends the surface, and switches D off when the window fills it, so the test starts sharp. */
    private fun showSharp() {
        player?.show(reader.surface, WIDTH, HEIGHT, DPI)
        val first = pictureAfter(SETTLE_MS, "lent-0-shown.png")
        if (allBlack(first, 0, 0, WINDOW_LEFT, HEIGHT)) return
        val per = WIDTH / 275f
        player?.tap(D_X * per, (HEIGHT - 116 * per) / 2f + D_Y * per)
        Thread.sleep(SETTLE_MS)
    }

    /** The surface as it looks [ms] from now, also written to [name]. */
    private fun pictureAfter(
        ms: Long,
        name: String,
    ): Bitmap {
        Thread.sleep(ms)
        val picture = checkNotNull(latest.get()) { "nothing was drawn on the surface" }
        File(context.getExternalFilesDir(null), name).outputStream().use {
            picture.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return picture
    }

    private fun keep(from: ImageReader) {
        val image = from.acquireLatestImage() ?: return
        val plane = image.planes[0]
        // a row can be wider than the picture; the extra is cut off
        val wide = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
        wide.copyPixelsFromBuffer(plane.buffer)
        image.close()
        latest.set(Bitmap.createBitmap(wide, 0, 0, WIDTH, HEIGHT))
    }

    @Suppress("LongParameterList") // a picture and a rectangle in it
    private fun allBlack(
        picture: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Boolean {
        val pixels = IntArray(width * height)
        picture.getPixels(pixels, 0, width, left, top, width, height)
        return pixels.all { it == Color.BLACK }
    }

    /** The row of the main window's transport buttons, which stands still while nothing plays. */
    private fun buttonsOf(picture: Bitmap): IntArray = partOf(picture, BUTTONS_X, BUTTONS_Y, BUTTONS_W, BUTTONS_H)

    /** The main window's balance slider. */
    private fun balanceOf(picture: Bitmap): IntArray = partOf(picture, BALANCE_LEFT, BALANCE_TOP, BALANCE_W, BALANCE_H)

    /** A part of the main window, given in the window's own pixels. */
    @Suppress("LongParameterList") // a picture and a rectangle in it
    private fun partOf(
        picture: Bitmap,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): IntArray {
        val width = w * SCALE
        val height = h * SCALE
        val pixels = IntArray(width * height)
        picture.getPixels(pixels, 0, width, WINDOW_LEFT + x * SCALE, WINDOW_TOP + y * SCALE, width, height)
        return pixels
    }

    private fun installed(name: String) = runCatching { context.packageManager.getPackageInfo(name, 0) }.isSuccess

    private companion object {
        const val WAIT_S = 15L
        const val SETTLE_MS = 2_500L

        // a car's screen: 1280x720, at which the 275x116 window is drawn four times its size
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val DPI = 160
        const val SCALE = 4
        const val WINDOW_W = 275 * SCALE
        const val WINDOW_H = 116 * SCALE
        const val WINDOW_LEFT = (WIDTH - WINDOW_W) / 2
        const val WINDOW_TOP = (HEIGHT - WINDOW_H) / 2

        // the middle of the Play and Stop buttons, in the window's own pixels
        const val PLAY_X = 50f
        const val STOP_X = 96f
        const val PLAY_Y = 97f

        // the middle of the EQ button, and a place in the reach of the equalizer's close button
        const val EQ_X = 230f
        const val EQ_Y = 64f
        const val CLOSE_X = 245f
        const val CLOSE_Y = 12f

        // the middle of the D in the clutter bar
        const val D_X = 14f
        const val D_Y = 51f

        // the middle of the PL button, on the EQ button's row
        const val PL_X = 253f

        // the list: its rows start under a top bar 20 of its pixels high and are 26 high, it is
        // 300 wide in the middle of the screen, and its close button reaches 40 in from the right
        const val LIST_TOP_PX = 20f * SCALE
        const val ROW_PX = 26f * SCALE
        const val LIST_CLOSE_X = (WIDTH + 300 * SCALE) / 2f - 20f * SCALE

        // the middle of a row's play button: a square as wide as the row is high, 12 in from the list's left
        const val LIST_PLAY_X = (WIDTH - 300 * SCALE) / 2f + 12f * SCALE + ROW_PX / 2f
        const val FAR = 100_000f

        // the balance slider, the middle of its travel, and a drag well short of its end
        const val BALANCE_LEFT = 177
        const val BALANCE_TOP = 57
        const val BALANCE_W = 38
        const val BALANCE_H = 13
        const val BALANCE_X = 196f
        const val BALANCE_Y = 63f
        const val BALANCE_DRAG = 9f

        // where the transport buttons are
        const val BUTTONS_X = 16
        const val BUTTONS_Y = 88
        const val BUTTONS_W = 114
        const val BUTTONS_H = 18
    }
}
