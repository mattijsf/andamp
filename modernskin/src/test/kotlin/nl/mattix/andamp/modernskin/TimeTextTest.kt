// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.SystemObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Scripts format times from milliseconds, as getPosition, getPlayItemLength and getTimeOfDay give
 * them: Winamp2000SP4's song timer showed 143:12 nine seconds into a song when they were read as seconds.
 */
class TimeTextTest {
    private val files =
        object : SkinFiles {
            override fun read(path: String): ByteArray? = null

            override fun names(): List<String> = emptyList()
        }

    private val host =
        object : SkinHost {
            override fun imageSize(file: String): Pair<Int, Int>? = null

            override fun textWidth(
                text: String,
                font: FontDef?,
                size: Int,
            ) = 0

            override fun log(message: String) = Unit

            override fun invalidate() = Unit
        }

    private val rt = SkinRuntime(files, host, FakePlayer())
    private val system = SystemObject(rt, null, "", "test")

    private fun call(
        name: String,
        ms: Int,
    ) = system.makiCall(name, listOf(Value.Int(ms)))!!.asString

    @Test
    fun `a time is minutes and whole seconds of milliseconds`() {
        assertEquals("0:09", call("integertotime", 9_999))
        assertEquals("3:35", call("integertotime", 215_000))
        assertEquals("-0:09", call("integertotime", -9_500))
    }

    @Test
    fun `the time of day is local, as a clock script shows it`() {
        // 08:15:00 UTC on 22 Oct 2026, in a zone two hours ahead
        rt.clock = { 1_792_656_900_000L }
        rt.zoneOffset = { 2 * 3_600_000 }

        val now = system.makiCall("gettimeofday", emptyList())!!.asInt

        assertEquals("10:15:00", call("integertolongtime", now))
    }

    @Test
    fun `a long time is hours, minutes and seconds of milliseconds`() {
        assertEquals("10:30:05", call("integertolongtime", 37_805_000))
        assertEquals("0:00:00", call("integertolongtime", 999))
    }
}
