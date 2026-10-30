// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * What the engine has done so far ([SkinRuntime.stats]), for a host's timing log or a probe:
 * - [scriptSteps]: script steps since the last tick began, across every script;
 * - [timerFires]: timer events fired;
 * - [layoutPasses]: layout passes run;
 * - [shapesWorkedOut]: window shapes worked out.
 */
data class EngineStats(
    val scriptSteps: Long = 0,
    val timerFires: Long = 0,
    val layoutPasses: Long = 0,
    val shapesWorkedOut: Long = 0,
)
