// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.wal.WalLook
import nl.mattix.andamp.wal.WalSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Picked files that are not added at once: a modern skin (.wal) picked while modern skins are off
 * waits in an offer to turn them on and add it, and a zip too large for a skin ends in a notice
 * that says why; both leave the library and the skin on screen as they were. While modern skins
 * are on, a modern skin is added once its look has started, and that look goes on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the base skin decodes through BitmapFactory
@Config(sdk = [35])
class SkinPickTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** Whether flushing a stored skin to the disk fails. */
    private var flushFails = false

    private val library =
        SkinLibrary(app) { channel ->
            if (flushFails) throw IOException("the disk failed")
            channel.force(true)
        }
    private val state = WinampState()

    /** Whether modern skins are on; taking the offer turns them on. */
    private var modernOn = false

    /** The names the modern skins' looks were opened under, in order. */
    private val built = ArrayList<String>()

    /** The looks opened, let go after each test. */
    private val looks = ArrayList<WalLook>()

    /** The player a look is opened with; a slow one makes its start run long. */
    private var player: () -> Player = { FakePlayer() }

    /** The time a look has to start. */
    private var budgetMs = 5_000L

    @After
    fun releaseLooks() = looks.forEach { it.release(save = false) }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("the condition holds within 20 s", condition())
    }

    /** The player's skins, with modern skins off unless [modernOn] says otherwise. */
    private fun started(): SkinOps =
        SkinOps(
            app,
            state,
            CoroutineScope(SupervisorJob() + Dispatchers.Main),
            library,
            modernOn = { modernOn },
            turnModernOn = { modernOn = true },
            openModern = { id, file, name, siblings ->
                built += name
                WalLook(id, file, name, siblings, app.cacheDir, WalSettings(app), player(), budgetMs = budgetMs)
                    .also { looks += it }
            },
        ).also { ops ->
            ops.start()
            // the move to the modern extension writes its flag once start is done
            await { ops.skin != null && state.skinEntries.isNotEmpty() && records().first.containsKey("modern_migrated") }
        }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val skinXml = "<WinampAbstractionLayer version=\"0.8\"/>".toByteArray()

    /** A modern skin that loads: one window with a play button, named by its skininfo when [info] is given. */
    private fun loading(
        info: String? = null,
        folder: String = "",
    ): ByteArray {
        val named = info?.let { "<skininfo><name>$it</name></skininfo>" }.orEmpty()
        return zip(
            "${folder}skin.xml" to
                "<WinampAbstractionLayer version=\"0.8\">$named<include file=\"player.xml\"/></WinampAbstractionLayer>"
                    .toByteArray(),
            "${folder}player.xml" to
                (
                    "<container id=\"main\" name=\"t\" default_visible=\"1\"><layout id=\"normal\" w=\"40\" h=\"20\">" +
                        "<button id=\"play\" action=\"PLAY\" x=\"0\" y=\"0\" w=\"20\" h=\"20\"/></layout></container>"
                ).toByteArray(),
        )
    }

    /** A player whose first status read takes [ms], so a start runs that long. */
    private fun slowPlayer(ms: Long): Player =
        object : Player by FakePlayer() {
            private var waited = false
            override val status: Player.Status
                get() {
                    if (!waited) {
                        waited = true
                        Thread.sleep(ms)
                    }
                    return Player.Status.STOPPED
                }
        }

    /** The threads running as this test begins. */
    private val runningBefore: Set<Thread> = Thread.getAllStackTraces().keys

    /**
     * The engine threads of the looks opened that are still running. An engine thread is named by
     * its look's id, the skin's hash, so a look of the same bytes opened earlier in this process
     * has a thread of the same name; one that was running before this test began is not counted.
     */
    private fun engineThreads(): List<Thread> {
        val names = looks.map { "wal-${it.id}" }.toSet()
        return Thread.getAllStackTraces().keys.filter { it.name in names && it.isAlive && it !in runningBefore }
    }

    /** Bytes that do not compress, so a zip holding them is about as large. */
    private fun noise(size: Int) = Random(size).nextBytes(size)

    /** Loads [bytes] as a pick named [name] and returns the notice it ends in. */
    private fun pick(
        ops: SkinOps,
        bytes: InputStream,
        name: String,
    ): String {
        ops.load(bytes, name)
        await { state.prompt != null }
        assertNoCopyLeft()
        return state.prompt!!.title
    }

    /** Loads [bytes] and checks that the pick ends with nothing to say. */
    private fun pickSilently(
        ops: SkinOps,
        bytes: InputStream,
        name: String,
    ) {
        val load = ops.load(bytes, name)
        await { load.isCompleted }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("$name says nothing", state.prompt)
        assertNull(state.waitingNotice)
        assertNoCopyLeft()
    }

    /** Loads [bytes] as a pick named [name] and waits for the load to complete. */
    private fun picked(
        ops: SkinOps,
        bytes: ByteArray,
        name: String,
    ) {
        val load = ops.load(bytes.inputStream(), name)
        await { load.isCompleted }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Picks a modern skin while modern skins are off and returns the offer it ends in. */
    private fun offered(
        ops: SkinOps,
        bytes: ByteArray = zip("skin.xml" to skinXml),
        name: String = "Bento.wal",
    ): ModernSkinOffer {
        picked(ops, bytes, name)
        val offer = state.modernOffer
        assertNotNull("an offer", offer)
        return offer!!
    }

    /** What the library records and holds: the "skins" file and the stored files. */
    private fun records(): Pair<Map<String, *>, List<String>> =
        app.getSharedPreferences("skins", 0).all.toMap() to
            File(app.filesDir, "skins").list().orEmpty().sorted()

    private fun assertNoCopyLeft() {
        val left = app.cacheDir.listFiles { f -> f.name.startsWith("pick") }.orEmpty()
        assertTrue("the pick's copy is gone: ${left.map { it.name }}", left.isEmpty())
    }

    /** A stream of [size] bytes that starts with [head] and goes on in zeros, made as it is read. */
    private fun stream(
        size: Long,
        head: ByteArray = ByteArray(0),
    ): InputStream =
        object : InputStream() {
            var at = 0L

            override fun read(): Int {
                if (at >= size) return -1
                val b = if (at < head.size) head[at.toInt()].toInt() and 0xff else 0
                at++
                return b
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (at >= size) return -1
                val n = minOf(len.toLong(), size - at).toInt()
                for (i in 0 until n) b[off + i] = read().toByte()
                return n
            }
        }

    private val zipHead = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

    @Test
    fun `with modern skins off a modern skin is not added before the offer is answered`() {
        val ops = started()
        val before = records()
        val onScreen = ops.skin!!.name

        offered(ops, zip("skin.xml" to skinXml, "player.xml" to skinXml), "Bento.wal")

        assertEquals("nothing is stored", before, records())
        assertEquals("the skin on screen stays", onScreen, ops.skin!!.name)
        assertEquals(SkinEntry.BASE_ID, ops.currentId)
        assertNull(ops.wal)
        assertTrue("no modern row is listed", state.skinEntries.none { it.kind == SkinKind.MODERN })
    }

    @Test
    fun `a modern skin kept in one folder of its zip is told apart too`() {
        val ops = started()

        assertEquals("Bento.zip", offered(ops, zip("Bento/skin.xml" to skinXml), "Bento.zip").name)
    }

    @Test
    fun `a modern skin larger than a classic skin may be is still told apart`() {
        val ops = started()
        val big = zip("skin.xml" to skinXml, "art.png" to noise(SkinOps.MAX_SKIN_BYTES + 1024))

        assertEquals("Big.wal", offered(ops, big, "Big.wal").name)
    }

    @Test
    fun `a zip too large for a skin says so`() {
        val ops = started()
        val before = library.list()
        val big = zip("main.bmp" to noise(SkinOps.MAX_SKIN_BYTES + 1024))

        assertEquals("“Huge.wsz” is too large", pick(ops, big.inputStream(), "Huge.wsz"))
        assertEquals("nothing is stored", before, library.list())
    }

    @Test
    fun `a zip past the most a pick reads says it is too large`() {
        val ops = started()

        assertEquals("“Endless.wsz” is too large", pick(ops, stream(SkinOps.MAX_PICK_BYTES + 1024, zipHead), "Endless.wsz"))
    }

    @Test
    fun `a large file that is not a zip says nothing`() {
        val ops = started()
        val before = library.list()

        pickSilently(ops, stream(SkinOps.MAX_SKIN_BYTES + 1024L), "clip.mp4")
        pickSilently(ops, stream(SkinOps.MAX_PICK_BYTES + 1024), "film.mkv")
        assertEquals("nothing is stored", before, library.list())
    }

    @Test
    fun `a modern skin whose zip directory is cut off is still told apart`() {
        val ops = started()
        val whole = zip("skin.xml" to skinXml, "player.xml" to skinXml)
        // the end of a zip is its directory: without it only the entries themselves remain
        val cut = whole.copyOf(whole.size - 30)

        assertEquals("Cut.wal", offered(ops, cut, "Cut.wal").name)
    }

    @Test
    fun `a notice waits for the prompt on screen to be answered`() {
        val ops = started()
        val first = AmpPrompt("First", "", "OK", null) { state.prompt = null }
        state.prompt = first
        val big = zip("main.bmp" to noise(SkinOps.MAX_SKIN_BYTES + 1024))

        ops.load(big.inputStream(), "Huge.wsz")
        await { state.waitingNotice != null }
        assertSame("the prompt on screen stays", first, state.prompt)

        first.onConfirm()
        assertEquals("“Huge.wsz” is too large", state.prompt?.title)
        assertNull(state.waitingNotice)
    }

    @Test
    fun `an offer raised under a prompt is no notice, and stays until answered`() {
        val ops = started()
        val first = AmpPrompt("First", "", "OK", null) { state.prompt = null }
        state.prompt = first

        val offer = offered(ops)

        // the offer's dialog waits for the prompt in AmpModals (AmpContextMenuTest)
        assertSame("the prompt on screen stays", first, state.prompt)
        assertNull("the offer is no notice", state.waitingNotice)
        first.onConfirm()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(state.prompt)
        assertSame("the offer is still there once the prompt is answered", offer, state.modernOffer)
    }

    @Test
    fun `a caller that takes the result words it, so no notice is set`() {
        val ops = started()
        var result: String? = "unset"
        val big = zip("main.bmp" to noise(SkinOps.MAX_SKIN_BYTES + 1024))

        val load = ops.load(big.inputStream(), "Huge.wsz") { result = it }
        await { load.isCompleted }

        assertNull("a zip too large reports no id", result)
        assertNull("the caller words it", state.prompt)
        assertNull(state.waitingNotice)
    }

    // modern skins on

    @Test
    fun `with modern skins on a picked modern skin is added under its hash, named without wal, and ticked`() {
        modernOn = true
        val ops = started()
        val bytes = loading()
        val id = library.idFor(bytes)

        picked(ops, bytes, "Bento.WAL")

        assertEquals("the hash taken while it streamed is the id", id, ops.currentId)
        assertEquals(id, library.currentId)
        assertTrue(File(app.filesDir, "skins/$id.wal").isFile)
        assertEquals("Bento", library.nameOf(id))
        assertEquals("opened once, under that name", listOf("Bento"), built)
        assertSame("the look that started is the one on screen", looks.single(), ops.wal)
        assertEquals(id, ops.wal!!.id)
        assertEquals("it loaded with the player", "Bento", ops.wal!!.loadedName)
        assertEquals(SkinKind.MODERN, state.skinEntries.single { it.id == id }.kind)
        assertSame("the last classic choice is drawn", ops.baseSkin, ops.skin)
        assertNull(state.prompt)
        assertNoCopyLeft()
    }

    @Test
    fun `a picked file named only wal takes the name the skin's skininfo gives`() {
        modernOn = true
        val ops = started()
        val bytes = loading(info = "Bento Pro")

        picked(ops, bytes, ".wal")

        assertEquals(listOf(""), built)
        assertEquals("Bento Pro", library.nameOf(library.idFor(bytes)))
    }

    @Test
    fun `picking the worn modern skin again opens nothing new`() {
        modernOn = true
        val ops = started()
        val bytes = loading()
        picked(ops, bytes, "Bento.wal")
        val worn = ops.wal

        picked(ops, bytes, "Bento.wal")

        assertEquals(listOf("Bento"), built)
        assertSame(worn, ops.wal)
        assertFalse(worn!!.released)
    }

    @Test
    fun `picking the worn modern skin again from a file named only wal keeps its name`() {
        modernOn = true
        val bytes = loading()
        picked(started(), bytes, "Bento.wal")
        // the next start opens its look, which no player surface has started yet
        val ops = started()
        await { ops.wal != null }
        assertEquals("", ops.wal!!.loadedName)

        picked(ops, bytes, ".wal")

        assertEquals("Bento", library.nameOf(library.idFor(bytes)))
        assertEquals("the pick kept the look the start opened", 2, looks.size)
        assertSame(looks.last(), ops.wal)
    }

    @Test
    fun `a modern skin picked as a wsz is added as a wal under the same id`() {
        modernOn = true
        val ops = started()
        val bytes = loading(folder = "Bento/")
        val id = library.idFor(bytes)

        picked(ops, bytes, "Bento.wsz")

        assertEquals(id, ops.currentId)
        assertEquals(SkinKind.MODERN, library.kindOf(id))
        assertFalse(File(app.filesDir, "skins/$id.wsz").exists())
        assertEquals("Bento.wsz", library.nameOf(id))
    }

    @Test
    fun `a modern skin that does not load is deleted, changes no record, sets no look, and says so`() {
        modernOn = true
        val ops = started()
        val before = records()

        val title = pick(ops, zip("skin.xml" to skinXml).inputStream(), "Broken.wal")

        assertEquals("“Broken.wal” couldn't be opened", title)
        assertEquals("the file goes and no record changes", before, records())
        assertEquals(SkinEntry.BASE_ID, ops.currentId)
        assertTrue(state.skinEntries.none { it.kind == SkinKind.MODERN })
        assertNull(ops.wal)
        assertTrue("its look is let go", looks.single().released)
        await { engineThreads().isEmpty() }
    }

    @Test
    fun `a modern skin that cannot be flushed to the disk is not added, changes no record and opens no look`() {
        modernOn = true
        val ops = started()
        val before = records()
        flushFails = true

        val title = pick(ops, loading().inputStream(), "Bento.wal")

        assertEquals("“Bento.wal” couldn't be opened", title)
        assertEquals("nothing is stored and no record changes", before, records())
        assertEquals(SkinEntry.BASE_ID, ops.currentId)
        assertNull(ops.wal)
        assertTrue(built.isEmpty())
    }

    @Test
    fun `a classic skin that cannot be flushed to the disk still goes on, is not stored and changes no record`() {
        library.choose(BundledSkins.SPOT.id)
        val ops = started()
        val before = records()
        flushFails = true

        picked(ops, app.assets.open("skins/AndAmp Light.wsz").use { it.readBytes() }, "Light.wsz")

        assertEquals("Light.wsz", ops.skin!!.name)
        assertEquals(SkinOps.UNSTORED_ID, ops.currentId)
        assertEquals("nothing is stored and no record changes", before, records())
    }

    @Test
    fun `a modern skin whose start runs past its time is deleted, changes no record and leaves no engine thread`() {
        modernOn = true
        player = { slowPlayer(600) }
        budgetMs = 100
        val ops = started()
        val before = records()

        val title = pick(ops, loading().inputStream(), "Slow.wal")

        assertEquals("“Slow.wal” couldn't be opened", title)
        assertEquals(before, records())
        assertNull(ops.wal)
        await { engineThreads().isEmpty() }
    }

    @Test
    fun `a modern skin added before stays when it does not load again`() {
        modernOn = true
        val ops = started()
        val bytes = loading()
        picked(ops, bytes, "Bento.wal")
        ops.reset()
        await { library.currentId == SkinEntry.BASE_ID && ops.wal == null }
        player = { slowPlayer(600) }
        budgetMs = 100

        pick(ops, bytes.inputStream(), "Bento.wal")

        assertTrue(library.has(library.idFor(bytes)))
        assertEquals(SkinEntry.BASE_ID, library.currentId)
    }

    @Test
    fun `the museum's modern skin is refused, also with modern skins on`() {
        modernOn = true
        val ops = started()
        val before = records()
        var result: String? = "unset"

        val load = ops.load(zip("skin.xml" to skinXml).inputStream(), "Bento.wal", allowModern = false) { result = it }
        await { load.isCompleted }

        assertNull(result)
        assertNull("no offer", state.modernOffer)
        assertEquals("nothing is stored", before, records())
        assertTrue("it is not loaded", built.isEmpty())
        assertNoCopyLeft()
    }

    // modern skins off: the offer

    @Test
    fun `with modern skins off, a modern skin gets the offer`() {
        val ops = started()

        val offer = offered(ops)

        assertEquals("Bento.wal", offer.name)
        assertNull("no notice besides the offer", state.prompt)
        assertNull(state.waitingNotice)
        assertTrue("a modal shows", state.showingAModal())
        assertTrue("nothing is loaded before the answer", built.isEmpty())
        assertFalse("modern skins stay off until the answer", modernOn)
    }

    @Test
    fun `Not now on the offer deletes the copy and changes nothing`() {
        val ops = started()
        val before = records()

        val offer = offered(ops)

        offer.decline()
        await {
            app.cacheDir
                .listFiles { f -> f.name.startsWith("pick") }
                .orEmpty()
                .isEmpty()
        }
        assertNull(state.modernOffer)
        assertFalse(modernOn)
        assertEquals("nothing changed", before, records())
    }

    @Test
    fun `taking the offer turns modern skins on and adds the skin, opened once`() {
        val ops = started()
        val bytes = loading()
        val id = library.idFor(bytes)

        offered(ops, bytes).accept()

        // the row is listed once the skin is on
        await { ops.currentId == id && state.skinEntries.any { it.id == id } }
        assertTrue(modernOn)
        assertNull(state.modernOffer)
        assertEquals("Bento", library.nameOf(id))
        assertEquals(listOf("Bento"), built)
        assertSame(looks.single(), ops.wal)
        assertNoCopyLeft()
    }

    @Test
    fun `an offer whose skin does not load leaves modern skins off and says so`() {
        val ops = started()
        val before = records()

        offered(ops).accept()

        await { state.prompt != null }
        assertEquals("“Bento.wal” couldn't be opened", state.prompt!!.title)
        assertFalse(modernOn)
        assertEquals(before, records())
        assertNoCopyLeft()
    }

    @Test
    fun `a newer pick replaces an offer still waiting`() {
        val ops = started()
        val first = offered(ops, name = "First.wal")

        val second = offered(ops, zip("skin.xml" to skinXml, "player.xml" to skinXml), "Second.wal")

        assertEquals("Second.wal", second.name)
        assertEquals(
            "the first copy is gone",
            1,
            app.cacheDir
                .listFiles { f -> f.name.startsWith("pick") }
                .orEmpty()
                .size,
        )
        first.decline()
        assertSame("declining the old offer leaves the new one", second, state.modernOffer)
    }
}
