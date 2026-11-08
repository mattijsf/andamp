// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.geometry.Offset
import nl.mattix.walkit.Rect
import nl.mattix.walkit.TouchControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A hold on a skin's small control opens the magnifier, whose crosshair steers the skin's press.
 * Two surface pixels make a skin pixel here; the skin lists a column of three small letters
 * (x 11..19, y 24..47, 8 tall each) and one large button.
 */
class WalLensTouchTest {
    private val skin = LensTarget()
    private val shown = mutableListOf<WalLens?>()
    private val longPresses = mutableListOf<Int>()
    private var assist = true
    private val touch =
        WalTouch(
            skin,
            WalTouch.Rules(longPressMs = 400, touchSlop = 8f, doubleTapMs = 300, doubleTapSlop = 100f),
            toSkin = { (it.x / 2).toInt() to (it.y / 2).toInt() },
            onLongPress = { _, seq -> longPresses += seq },
            lens =
                object : WalTouch.Lens {
                    override val enabled get() = assist
                    override val skinPerPx = 0.5f

                    override fun open(
                        at: TouchPoint,
                        controls: List<TouchControl>,
                    ) = WalLens.over(controls, Offset(at.x / 2, at.y / 2), slop = 2f)

                    override fun show(lens: WalLens?) {
                        shown += lens
                    }
                },
        )

    @Test
    fun `a hold on a small control opens the lens on it and makes the press precise`() {
        touch.down(TouchPoint(30f, 70f), 0)
        assertEquals("the hold comes before the long press", 90L, touch.longPressIn(0))

        touch.due(90)

        assertEquals(listOf("down 15,35", "controls", "precise", "move 15,35"), skin.calls)
        assertNotNull(shown.single())
        assertNull("no long press while the lens is up", touch.longPressIn(100))
        // a wait for the long press that began before the lens opened runs out to nothing
        touch.due(400)
        assertEquals(emptyList<Int>(), longPresses)
        assertEquals(4, skin.calls.size)
    }

    @Test
    fun `the crosshair steers the press geared down, and the release lets go where it is`() {
        touch.down(TouchPoint(30f, 70f), 0)
        touch.due(90)
        // the first move after the lens opens moves nothing
        touch.move(TouchPoint(30f, 72f))
        // 24 surface pixels down: 12 skin pixels, geared down by 1.5 to 8, onto the letter below
        touch.move(TouchPoint(30f, 96f))
        touch.up(TouchPoint(30f, 96f), 600, consumed = false)

        assertEquals(listOf("move 15,43", "up 15,43"), skin.calls.drop(4))
        assertNull("the lens closes", shown.last())
        assertEquals(emptyList<Int>(), longPresses)
    }

    @Test
    fun `pushed far past its cluster the lens closes and the press is cancelled`() {
        touch.down(TouchPoint(30f, 70f), 0)
        touch.due(90)
        touch.move(TouchPoint(30f, 70f))
        touch.move(TouchPoint(400f, 70f))
        touch.up(TouchPoint(400f, 70f), 600, consumed = false)

        assertEquals(listOf("cancel"), skin.calls.drop(4))
        assertNull(shown.last())
    }

    @Test
    fun `a hold on a large control opens no lens, and the long press still comes`() {
        touch.down(TouchPoint(240f, 20f), 0)
        touch.due(90)

        assertEquals(listOf("down 120,10", "controls"), skin.calls)
        assertEquals(emptyList<WalLens?>(), shown)
        assertEquals(310L, touch.longPressIn(90))
        touch.due(400)
        assertEquals(listOf(1), longPresses)
    }

    @Test
    fun `a hold on an open menu's picture opens the lens on the picture, not the button under it`() {
        val lens =
            WalLens.over(
                listOf(
                    TouchControl(Rect(14, 86, 22, 18), magnify = false),
                    TouchControl(Rect(14, 87, 22, 18), magnify = true),
                    TouchControl(Rect(14, 69, 22, 18), magnify = true),
                    TouchControl(Rect(14, 51, 22, 18), magnify = true),
                ),
                Offset(20f, 95f),
                slop = 2f,
            )

        assertNotNull(lens)
        assertEquals(
            androidx.compose.ui.unit
                .IntRect(14, 87, 36, 105),
            lens!!.target,
        )
        assertEquals("the lens roams the three pictures", 51 - 3, lens.roam.top)
    }

    @Test
    fun `between two controls and past the last one the press stays on the nearest`() {
        // two letters with a gap of four between them
        val lens =
            WalLens.over(
                listOf(
                    TouchControl(Rect(11, 24, 9, 8), magnify = false),
                    TouchControl(Rect(11, 36, 9, 8), magnify = false),
                ),
                Offset(15f, 27f),
                slop = 2f,
            )!!
        // 9 skin pixels down, geared down to 6: into the gap, nearer the upper letter
        assertEquals(true, lens.move(Offset(0f, 9f)))
        assertEquals(33f, lens.focus.y)
        assertEquals(
            androidx.compose.ui.unit
                .IntRect(11, 24, 20, 32),
            lens.target,
        )
        assertEquals(15 to 31, lens.point)
        // further on, nearer the lower one
        lens.move(Offset(0f, 2f))
        assertEquals(
            androidx.compose.ui.unit
                .IntRect(11, 36, 20, 44),
            lens.target,
        )
        assertEquals(15 to 36, lens.point)
        // into the cluster's padding below the last letter
        lens.move(Offset(0f, 20f))
        assertEquals(
            androidx.compose.ui.unit
                .IntRect(11, 36, 20, 44),
            lens.target,
        )
        assertEquals(15 to 43, lens.point)
    }

    @Test
    fun `with Tap assist off a hold is only the long press`() {
        assist = false
        touch.down(TouchPoint(30f, 70f), 0)

        assertEquals(400L, touch.longPressIn(0))
    }
}

/** The skin's pointer calls as short lines, and the controls it lists for a press. */
private class LensTarget : WalTouch.Target {
    val calls = mutableListOf<String>()
    override val moveTarget: Int? = null
    private var seq = 0

    override fun pointerDown(
        x: Int,
        y: Int,
    ): Int {
        calls += "down $x,$y"
        return ++seq
    }

    override fun pointerMove(
        x: Int,
        y: Int,
    ) {
        calls += "move $x,$y"
    }

    override fun pointerUp(
        x: Int,
        y: Int,
    ) {
        calls += "up $x,$y"
    }

    override fun pointerCancel() {
        calls += "cancel"
    }

    override fun doubleTap(
        x: Int,
        y: Int,
    ): Int {
        calls += "doubleTap $x,$y"
        return ++seq
    }

    override fun pointerPrecise() {
        calls += "precise"
    }

    override fun pressControls(onControls: (List<TouchControl>) -> Unit) {
        calls += "controls"
        onControls(
            listOf(
                TouchControl(Rect(11, 24, 9, 8), magnify = false),
                TouchControl(Rect(11, 32, 9, 8), magnify = false),
                TouchControl(Rect(11, 40, 9, 8), magnify = false),
                TouchControl(Rect(100, 0, 60, 20), magnify = false),
            ),
        )
    }
}
