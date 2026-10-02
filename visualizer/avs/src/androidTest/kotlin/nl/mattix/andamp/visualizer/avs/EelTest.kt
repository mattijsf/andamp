// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The evaluator, on a device, because it is native. These assert that the
 * binding carries values across the boundary in both directions, not that
 * ns-eel works.
 */
@RunWith(AndroidJUnit4::class)
class EelTest {
    @Test
    fun an_expression_returns_its_value() {
        Eel().use { eel ->
            assertEquals(7.0, eel.compile("3 + 4")!!.run(), 0.0)
        }
    }

    @Test
    fun a_variable_written_here_is_read_by_the_code() {
        Eel().use { eel ->
            val x = eel.variable("x")
            val code = eel.compile("x * 2")!!

            x.value = 21.0

            assertEquals(42.0, code.run(), 0.0)
        }
    }

    @Test
    fun a_variable_written_by_the_code_is_read_back_here() {
        Eel().use { eel ->
            val y = eel.variable("y")
            eel.compile("y = 5.5")!!.run()

            assertEquals(5.5, y.value, 0.0)
        }
    }

    @Test
    fun the_same_name_is_the_same_variable() {
        Eel().use { eel ->
            eel.variable("n").value = 3.0

            assertEquals(3.0, eel.variable("n").value, 0.0)
        }
    }

    /** A Super Scope runs its point code once per point with only `i` changing. */
    @Test
    fun one_compile_runs_many_times_with_changed_variables() {
        Eel().use { eel ->
            val i = eel.variable("i")
            val out = eel.variable("out")
            val code = eel.compile("out = i * i")!!

            val squares =
                (0..4).map {
                    i.value = it.toDouble()
                    code.run()
                    out.value
                }

            assertEquals(listOf(0.0, 1.0, 4.0, 9.0, 16.0), squares)
        }
    }

    @Test
    fun several_statements_run_in_order() {
        Eel().use { eel ->
            val result = eel.variable("r")
            eel.compile("a = 2; b = 3; r = a * b;")!!.run()

            assertEquals(6.0, result.value, 0.0)
        }
    }

    @Test
    fun common_math_functions_are_available() {
        Eel().use { eel ->
            val r = eel.variable("r")
            eel.compile("r = sin(0) + cos(0) + abs(-2) + sqrt(9) + pow(2,3) + if(1,10,20)")!!.run()

            assertEquals(0.0 + 1.0 + 2.0 + 3.0 + 8.0 + 10.0, r.value, 0.0001)
        }
    }

    @Test
    fun code_that_will_not_compile_is_null_and_says_why() {
        Eel().use { eel ->
            assertNull(eel.compile("this is ( not eel"))
            assertNotNull("a failed compile reports an error", eel.lastError())
        }
    }

    @Test
    fun a_reset_zeroes_the_variables_and_keeps_them() {
        Eel().use { eel ->
            val x = eel.variable("x")
            x.value = 9.0

            eel.reset()

            assertEquals(0.0, x.value, 0.0)
            assertEquals(0.0, eel.variable("x").value, 0.0)
        }
    }

    @Test
    fun closing_twice_is_harmless_and_so_is_running_code_after_it() {
        val eel = Eel()
        val code = eel.compile("1 + 1")!!
        eel.close()
        eel.close()

        assertEquals("a run after close returns zero", 0.0, code.run(), 0.0)
    }

    @Test
    fun two_contexts_do_not_share_variables() {
        Eel().use { first ->
            Eel().use { second ->
                first.variable("x").value = 1.0
                second.variable("x").value = 2.0

                assertEquals(1.0, first.variable("x").value, 0.0)
                assertEquals(2.0, second.variable("x").value, 0.0)
            }
        }
    }
}
