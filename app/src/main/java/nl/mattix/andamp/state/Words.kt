// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.res.Resources

/**
 * The app's strings, in the phone's language, for the state layer: the menus, prompts, headers
 * and notices it words. A composable reads a string with `stringResource` instead.
 *
 * An interface rather than `Resources`, so that a test can hand an ops class the English words
 * without an Android runtime.
 */
interface Words {
    /** The string [id], with [args] filled into its placeholders. */
    fun get(
        id: Int,
        vararg args: Any,
    ): String

    /** The plural [id] for [count], with [count] filled in as the first placeholder, then [args]. */
    fun plural(
        id: Int,
        count: Int,
        vararg args: Any,
    ): String

    companion object {
        /** The app's resources, read through [context] each time, so a change of language is followed. */
        fun of(context: Context): Words =
            object : Words {
                override fun get(
                    id: Int,
                    vararg args: Any,
                ) = context.getString(id, *args)

                override fun plural(
                    id: Int,
                    count: Int,
                    vararg args: Any,
                ) = context.resources.getQuantityString(id, count, count, *args)
            }

        /** The same, for a place that holds only the resources. */
        fun of(resources: Resources): Words =
            object : Words {
                override fun get(
                    id: Int,
                    vararg args: Any,
                ) = resources.getString(id, *args)

                override fun plural(
                    id: Int,
                    count: Int,
                    vararg args: Any,
                ) = resources.getQuantityString(id, count, count, *args)
            }
    }
}
