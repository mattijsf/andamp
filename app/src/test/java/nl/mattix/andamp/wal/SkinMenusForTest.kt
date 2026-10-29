// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.modernskin.skin.SkinMenuChoices
import nl.mattix.andamp.state.EnglishWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The skin's right-click menus a long press offers under "Skin menu", and the names they go by. */
class SkinMenusForTest {
    private val surface = FakeWalSurface().apply { layout = stackOf(300, 200, 3f)(WalFit(900, 1600, 3f, sideways = false)) }

    private fun choice(
        id: String,
        tooltip: String? = null,
    ) = SkinMenuChoices.Choice(id, tooltip)

    private fun offer(
        seq: Int,
        vararg choices: SkinMenuChoices.Choice,
    ) {
        surface.layout = surface.layout!!.copy(menuChoices = SkinMenuChoices(seq, choices.toList()))
    }

    @Test
    fun `a press on an object with a menu is offered nothing, as it opens that menu itself`() {
        surface.layout = surface.layout!!.copy(menuTarget = 4)

        assertTrue(skinMenusFor(surface, 4, EnglishWords).isEmpty())
    }

    @Test
    fun `a press beside the only menu offers it, which right-clicks that object`() {
        offer(2, choice("title1"))

        val menus = skinMenusFor(surface, 2, EnglishWords)

        assertEquals(1, menus.size)
        menus.single().onClick()
        assertEquals(listOf(2 to 0), surface.choiceClicks)
        assertTrue(surface.rightClicks.isEmpty())
    }

    @Test
    fun `menus go by the tooltip, else by a word of the id, else by number`() {
        offer(
            1,
            choice("comp.menu", "Change the content this area holds"),
            choice("main.vis.trigger"),
            choice("TimerTrigger"),
            choice("winamp.albumart"),
            choice("waaa"),
            choice("display.time", "  "),
            choice("coolgraph"),
        )

        val names = skinMenusFor(surface, 1, EnglishWords).map { it.label }

        assertEquals(
            listOf("Change the content this area holds", "Visualizer", "Time", "Album art", "Menu 1", "Menu 2"),
            names,
        )
    }

    @Test
    fun `objects that go by one name are offered once, and a pick right-clicks the first of them`() {
        offer(3, choice("main.vis.trigger"), choice("rate.1"), choice("rate.2"), choice("rate.3"), choice("display.time"))

        val menus = skinMenusFor(surface, 3, EnglishWords)

        assertEquals(listOf("Visualizer", "Rating", "Time"), menus.map { it.label })
        menus[1].onClick()
        menus[2].onClick()
        assertEquals(listOf(3 to 1, 3 to 4), surface.choiceClicks)
    }

    @Test
    fun `no menu on screen, or one listed for another press, offers nothing`() {
        assertTrue(skinMenusFor(surface, 1, EnglishWords).isEmpty())

        offer(1, choice("main.vis.trigger"))
        assertTrue("an older press's list", skinMenusFor(surface, 2, EnglishWords).isEmpty())

        surface.layout = null
        assertTrue(skinMenusFor(surface, 1, EnglishWords).isEmpty())
    }
}
