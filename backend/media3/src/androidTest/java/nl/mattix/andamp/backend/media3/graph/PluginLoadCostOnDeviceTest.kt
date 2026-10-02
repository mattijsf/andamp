// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.mattix.andamp.core.plugin.PluginLoader
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What loading a plug-in costs on a phone.
 *
 * The load deadline exists to abandon a script that hangs. This measures the
 * load time of each bundled plug-in and asserts it is inside the deadline.
 *
 * Method names are plain identifiers: a name with spaces is illegal in DEX
 * below version 040, and this module runs from API 26.
 */
@RunWith(AndroidJUnit4::class)
class PluginLoadCostOnDeviceTest {
    private fun source(name: String): String =
        PluginLoadCostOnDeviceTest::class.java
            .getResourceAsStream("/plugins/$name")!!
            .bufferedReader()
            .readText()

    /**
     * Times a first and a second load of the same script. What a process pays
     * once, such as loading LuaJ, falls on whichever script runs first.
     */
    private fun cost(name: String): Pair<Long, Long> {
        val lua = source(name)
        val cold = timed(lua)
        val warm = timed(lua)
        return cold to warm
    }

    private fun timed(lua: String): Long {
        val started = System.nanoTime()
        val result = PluginLoader(PluginLoader.Budgets(maxLoadMillis = GENEROUS)).load(lua, RATE, CHANNELS)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("the plug-in loads: $result", result is PluginLoader.Result.Loaded)
        return millis
    }

    @Test
    fun everyPluginLoadsInsideTheDeadline() {
        val costs =
            listOf(
                "preamp.lua",
                "tone.lua",
                "virtualbass.lua",
                "width.lua",
                "leveler.lua",
                "crossfeed.lua",
            ).associateWith {
                cost(it)
            }

        val report = costs.entries.joinToString { "${it.key} ${it.value.first}ms cold, ${it.value.second}ms warm" }
        Log.i("AndAmpCost", "plugin load on device: $report")
        println("plugin load on device: $report")
        val allowed = PluginLoader.Budgets().maxLoadMillis
        costs.forEach { (name, both) ->
            val cold = both.first
            assertTrue("$name loads within the ${allowed}ms a host allows: ${cold}ms", cold < allowed)
        }
    }

    private companion object {
        const val RATE = 44_100
        const val CHANNELS = 2

        /** A deadline long enough that the load is measured and not refused. */
        const val GENEROUS = 30_000L
    }
}
