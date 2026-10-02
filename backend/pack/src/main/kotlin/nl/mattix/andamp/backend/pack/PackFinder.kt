// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import android.content.Context
import android.content.Intent
import nl.mattix.andamp.core.packapi.PackApi

/**
 * Every pack on this phone, found by the bind action they all answer.
 *
 * A phone may hold more than one pack, and `resolveService` answers with a single match, so
 * this queries for all of them.
 *
 * The query is for one action, which the `<queries><intent>` entry in the player's manifest
 * makes visible. The player does not hold `QUERY_ALL_PACKAGES` and sees no other apps.
 *
 * The result is ordered by package name, because the package manager's order is not
 * documented. A player that has to choose between two packs claiming the same source then
 * chooses the same one on every launch.
 */
object PackFinder {
    /**
     * The packages that answer [action], sorted; empty on a phone with no pack.
     *
     * A query only: nothing is bound and no process is started. What each pack can play, which
     * contract it implements and what it calls itself are questions for a [PackClient].
     *
     * The suppressed deprecation is the query: the flag-taking overloads are the only ones
     * that exist below API 33.
     */
    @Suppress("DEPRECATION")
    fun found(
        context: Context,
        action: String = PackApi.ACTION_BIND,
    ): List<String> =
        context.packageManager
            .queryIntentServices(Intent(action), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
            .sorted()
}
