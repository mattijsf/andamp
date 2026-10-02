// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.welcome

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.online.OnlineSkin
import nl.mattix.andamp.ui.online.MuseumTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The first launch offers two answers: keep what the player is wearing, or go
 * where the classic skins are.
 *
 * The strip is the museum's own front row. The original Winamp skin is the
 * museum's first tile, so browsing lands on it.
 */
@RunWith(RobolectricTestRunner::class)
// tall enough for both cards at once: the screen scrolls on a phone, and this
// rule cannot press a node below the fold
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the card draws a real skin
@Config(sdk = [35], qualifiers = "w411dp-h1600dp-mdpi")
class WelcomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun tile(
        md5: String,
        filename: String,
    ) = OnlineSkin(
        md5 = md5,
        filename = filename,
        screenshotUrl = "https://r2.webampskins.org/screenshots/$md5.png",
        downloadUrl = "https://r2.webampskins.org/skins/$md5.wsz",
        museumUrl = null,
        nsfw = false,
    )

    private val front =
        listOf(
            tile("5e4f10275dcb1fb211d4a8b4f1bda236", "base-2.91.wsz"),
            tile("cd251187a5e6ff54ce938d26f1f2de02", "Winamp3_Classified_v5.5.wsz"),
            tile("47597ab8e5ffcd39686d455c10c3b436", "Garfield.zip"),
        )

    private var kept = 0
    private var browsed = 0

    private fun show(
        tiles: List<OnlineSkin> = front,
        count: Int = 93_603,
    ) {
        compose.setContent {
            WelcomeScreen(
                WelcomeChoice(
                    skin = null,
                    tiles = tiles,
                    museumCount = count,
                    onKeep = { kept++ },
                    onBrowse = { browsed++ },
                ),
            )
        }
    }

    /**
     * With the player drawn in the card: the preview is a SubcomposeLayout,
     * and a parent that asks one for its intrinsic size throws.
     */
    @Test
    fun `it draws with a real skin in the card`() {
        val skin = SkinLoader.loadBase(ApplicationProvider.getApplicationContext())

        compose.setContent {
            WelcomeScreen(WelcomeChoice(skin = skin, tiles = front, museumCount = 93_603))
        }

        compose.onNodeWithTag("welcome.andamp").assertIsDisplayed()
        compose.onNodeWithTag("welcome.keep").assertIsDisplayed()
    }

    @Test
    fun `both answers are offered, and nothing is chosen for the listener`() {
        show()

        compose.onNodeWithTag("welcome.andamp").assertIsDisplayed()
        compose.onNodeWithTag("welcome.museum").assertIsDisplayed()
        assertEquals(0, kept)
        assertEquals(0, browsed)
    }

    @Test
    fun `keeping what is on screen is one press`() {
        show()

        compose.onNodeWithTag("welcome.keep").performClick()

        assertEquals(1, kept)
        assertEquals(0, browsed)
    }

    @Test
    fun `the museum is one press away`() {
        show()

        compose.onNodeWithTag("welcome.browse").performClick()

        assertEquals(1, browsed)
    }

    /** The whole card is clickable, tiles included. */
    @Test
    fun `the card itself opens the museum`() {
        show()

        compose.onNodeWithTag("welcome.museum").performClick()

        assertEquals(1, browsed)
    }

    @Test
    fun `the strip is the museum's own front row`() {
        show()

        // unmerged: the card is one press, so its tiles fold into its semantics
        front.forEach { compose.onNodeWithTag("welcome.tile.${it.md5}", useUnmergedTree = true).assertIsDisplayed() }
    }

    @Test
    fun `the museum says how many it holds`() {
        show(count = 93_603)

        compose.onNodeWithText("93,603 classic skins").assertIsDisplayed()
    }

    /**
     * The first row must not read as three of one brand's own skins, so the
     * museum's fifth, Garfield, is moved up into it.
     */
    @Test
    fun `Garfield rides up into the first row`() {
        val front =
            listOf(
                tile("5e4f10275dcb1fb211d4a8b4f1bda236", "base-2.91.wsz"),
                tile("cd251187a5e6ff54ce938d26f1f2de02", "Winamp3_Classified_v5.5.wsz"),
                tile("b0fb83cc20af3abe264291bb17fb2a13", "Winamp5_Classified_v5.5.wsz"),
                tile("d6010aa35bed659bc1311820daa4b341", "Bento_Classified.wsz"),
                tile("47597ab8e5ffcd39686d455c10c3b436", "Garfield.zip"),
                tile("d770a634cc1ab252cfb16c64f4f5f616", "Zelda_Amp_3.wsz"),
            )

        val shown = firstRow(front).map { it.filename }

        assertEquals("Garfield.zip", shown[2])
        assertEquals("base-2.91.wsz", shown.first())
        assertEquals(front.map { it.filename }.sorted(), shown.sorted())
    }

    @Test
    fun `a front without it is left as the museum sent it`() {
        val front = listOf(tile("a", "one.wsz"), tile("b", "two.wsz"))

        assertEquals(front, firstRow(front))
    }

    /**
     * The tiles this card shows are known by name in [MuseumTiles.front], so
     * the strip draws before anything is fetched.
     */
    @Test
    fun `with nothing fetched the strip still shows its tiles`() {
        show(tiles = emptyList(), count = 0)

        MuseumTiles.front.forEach {
            compose.onNodeWithTag("welcome.tile.${it.md5}", useUnmergedTree = true).assertIsDisplayed()
        }
        compose.onNodeWithTag("welcome.browse").assertIsDisplayed()
    }

    /**
     * The strip is on screen before the fetch returns and must not change
     * height when it does. Measured in one composition.
     */
    @Test
    fun `the strip stands the same height before and after the museum answers`() {
        val tiles = mutableStateOf(emptyList<OnlineSkin>())
        compose.setContent {
            WelcomeScreen(WelcomeChoice(skin = null, tiles = tiles.value, museumCount = 0))
        }

        val empty = compose.onNodeWithTag("welcome.strip", useUnmergedTree = true).getUnclippedBoundsInRoot().height

        tiles.value = front
        compose.waitForIdle()
        val full = compose.onNodeWithTag("welcome.strip", useUnmergedTree = true).getUnclippedBoundsInRoot().height

        // a rounding row apart at most: both come out of the same measurement
        assertTrue(
            "the strip keeps its height: $empty with nothing loaded, $full with tiles",
            abs(empty.value - full.value) <= 1f,
        )
    }
}
