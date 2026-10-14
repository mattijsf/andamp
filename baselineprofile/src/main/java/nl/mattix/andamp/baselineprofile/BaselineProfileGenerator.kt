// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes `:app`'s baseline profile: the code a listener runs in the first seconds after opening
 * the app. `./gradlew :app:generateBaselineProfile` runs it; see `baselineprofile/build.gradle.kts`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * A start up to the player's first frame. It also makes the startup profile, by which R8 puts
     * the classes a start loads first in the dex (`dexLayoutOptimization` in `:app`).
     */
    @Test
    fun start() =
        rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startPlayer()
        }

    /** A start, then the playlist, the library and Preferences. */
    @Test
    fun firstMinute() =
        rule.collect(packageName = PACKAGE) {
            pressHome()
            startPlayer()
            keepTheSkin()
            browseTheWindows()
            openPreferences()
        }
}
