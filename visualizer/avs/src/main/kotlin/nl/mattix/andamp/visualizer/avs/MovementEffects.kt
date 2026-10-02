// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/** Which pair of coordinates a movement's code works in. */
enum class AvsCoordinates { POLAR, CARTESIAN }

/**
 * One of Movement's built-in effects: a name, the ns-eel that produces it, and
 * the coordinates that code works in.
 *
 * A preset stores a built-in by number, and each is expressed here as ns-eel,
 * so a built-in and a preset's own script run the same way. Transcribed from
 * vis_avs (BSD; see NOTICE.md), `e_movement.h`'s effect table, whose scripts
 * are written for the conventions [WarpMesh] hands out: `r` carries AVS's
 * +pi/2 offset (hence the `r - pi/2` terms) and `d` is isotropic over the
 * half-diagonal in pixels.
 *
 * "Slight Fuzzify" and "Blocky Partial Out" have no script here: they work per
 * pixel, which the grid mesh cannot do. They are listed so that a preset using
 * one is reported as unimplemented.
 */
data class MovementEffect(
    val name: String,
    val code: String,
    val coordinates: AvsCoordinates,
) {
    /** Whether this build can produce it. The two without a script cannot. */
    val runnable get() = code.isNotBlank() || name == "None"

    /** The script in a form the evaluator accepts. See [asEel]. */
    val eel get() = asEel(code)

    companion object {
        /**
         * The built-in scripts separate statements with commas and end on one;
         * projectm-eval wants semicolons and rejects a trailing separator.
         *
         * Only commas outside brackets are separators: the ones inside
         * `atan2(y, x)` are arguments and are kept.
         *
         * Empty statements are removed: AVS's evaluator accepts a leading or
         * doubled separator, and presets open sections with one, but
         * projectm-eval treats it as a syntax error and the whole section
         * fails to compile.
         *
         * `//` comments are removed first: a comma inside one would otherwise
         * become a separator, and a comment reaching the end of a section
         * would swallow the statement joined after it.
         */
        fun asEel(code: String): String {
            val bare = if ('/' in code) code.lineSequence().map { it.substringBefore("//") }.joinToString("\n") else code
            val out = StringBuilder(bare.length)
            var depth = 0
            bare.forEach { character ->
                when (character) {
                    '(' -> depth++
                    ')' -> depth--
                }
                out.append(if (character == ',' && depth == 0) ';' else character)
            }
            return out
                .toString()
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(";")
        }
    }
}

object MovementEffects {
    private val byId: Map<Int, MovementEffect> =
        mapOf(
            0 to MovementEffect("None", "", AvsCoordinates.POLAR),
            1 to MovementEffect("Slight Fuzzify", "", AvsCoordinates.POLAR),
            2 to MovementEffect("Shift Rotate Left", "x=x+(1/32)-(1/sw),", AvsCoordinates.CARTESIAN),
            3 to MovementEffect("Big Swirl Out", "r = r + (0.1 - (0.2 * d)), d = d * 0.96,", AvsCoordinates.POLAR),
            4 to
                MovementEffect(
                    "Medium Swirl",
                    "d = d * (0.99 * (1.0 - sin(r-3.141592653589793*0.5) / 32.0)), r = r + (0.03 * sin(d * " +
                        "3.141592653589793 * 4)),",
                    AvsCoordinates.POLAR,
                ),
            5 to
                MovementEffect(
                    "Sunburster",
                    "d = d * (0.94 + (cos((r-3.141592653589793*0.5) * 32.0) * 0.06)),",
                    AvsCoordinates.POLAR,
                ),
            6 to
                MovementEffect(
                    "Swirl To Center",
                    "d = d * (1.01 + (cos((r-3.141592653589793*0.5) * 4) * 0.04)), r = r + (0.03 * sin(d * " +
                        "3.141592653589793 * 4)),",
                    AvsCoordinates.POLAR,
                ),
            7 to MovementEffect("Blocky Partial Out", "", AvsCoordinates.POLAR),
            8 to
                MovementEffect(
                    "Swirling Around Both Ways At Once",
                    "r = r + (0.1 * sin(d * 3.141592653589793 * 5)),",
                    AvsCoordinates.POLAR,
                ),
            9 to
                MovementEffect(
                    "Bubbling Outward",
                    "t = sin(d * 3.141592653589793), d = d - (8*t*t*t*t*t)/sqrt((sw*sw+sh*sh)/4),",
                    AvsCoordinates.POLAR,
                ),
            10 to
                MovementEffect(
                    "Bubbling Outward With Swirl",
                    "t = sin(d * 3.141592653589793), d = d - (8*t*t*t*t*t)/sqrt((sw*sw+sh*sh)/4), " +
                        "t=cos(d*3.141592653589793/2.0), r= r + 0.1*t*t*t,",
                    AvsCoordinates.POLAR,
                ),
            11 to
                MovementEffect(
                    "5 Pointed Distro",
                    "d = d * (0.95 + (cos(((r-3.141592653589793*0.5) * 5.0) - (3.141592653589793 / 2.50)) * " +
                        "0.03)),",
                    AvsCoordinates.POLAR,
                ),
            12 to
                MovementEffect(
                    "Tunneling",
                    "r = r + 0.04, d = d * (0.96 + cos(d * 3.141592653589793) * 0.05),",
                    AvsCoordinates.POLAR,
                ),
            13 to
                MovementEffect(
                    "Bleedin'",
                    "t = cos(d * 3.141592653589793), r = r + (0.07 * t), d = d * (0.98 + t * 0.10),",
                    AvsCoordinates.POLAR,
                ),
            // the native effect (e_movement.cpp's _effect_13) works on the
            // incoming polar pair and then adds 8*w/256 pixels of x; expressed
            // here through the isotropic d and offset r the mesh provides, with
            // the aspect factored back out per axis
            14 to
                MovementEffect(
                    "Shifted Big Swirl Out",
                    "r=r+0.1-0.2*d, d=d*0.96, x=cos(r-3.141592653589793/2)*d*sqrt(sw*sw+sh*sh)/sw + 8/128, " +
                        "y=sin(r-3.141592653589793/2)*d*sqrt(sw*sw+sh*sh)/sh,",
                    AvsCoordinates.CARTESIAN,
                ),
            15 to MovementEffect("Psychotic Beaming Outward", "d = 0.15", AvsCoordinates.POLAR),
            16 to
                MovementEffect(
                    "Cosine Radial 3-way",
                    "r = cos((r - 3.141592653589793/2) * 3) + 3.141592653589793/2",
                    AvsCoordinates.POLAR,
                ),
            17 to MovementEffect("Spinny Tube", "d = d * (1 - ((d - .35) * .5)), r = r + .1,", AvsCoordinates.POLAR),
            18 to
                MovementEffect(
                    "Radial Swirlies",
                    "d = d * (1 - (sin((r-3.141592653589793*0.5) * 7) * .03)), r = r + (cos(d * 12) * .03),",
                    AvsCoordinates.POLAR,
                ),
            19 to
                MovementEffect(
                    "Swill",
                    "d = d * (1 - (sin((r - 3.141592653589793*0.5) * 12) * .05)), r = r + (cos(d * 18) * " +
                        ".05), d = d * (1-((d - .4) * .03)), r = r + ((d - .4) * .13)",
                    AvsCoordinates.POLAR,
                ),
            20 to
                MovementEffect(
                    "Gridley",
                    "x = x + (cos(y * 18) * .02), y = y + (sin(x * 14) * .03),",
                    AvsCoordinates.CARTESIAN,
                ),
            21 to
                MovementEffect(
                    "Grapevine",
                    "x = x + (cos(abs(y-.5) * 8) * .02), y = y + (sin(abs(x-.5) * 8) * .05), x = x * .95, y " +
                        "= y * .95,",
                    AvsCoordinates.CARTESIAN,
                ),
            22 to
                MovementEffect(
                    "Quadrant",
                    "y = y * ( 1 + (sin(r + 3.141592653589793/2) * .3) ), x = x * ( 1 + (cos(r + " +
                        "3.141592653589793/2) * .3) ), x = x * .995, y = y * .995,",
                    AvsCoordinates.CARTESIAN,
                ),
            23 to
                MovementEffect(
                    "6-way Kaleida (use Wrap!)",
                    "y = (r*6)/(3.141592653589793), x = d,",
                    AvsCoordinates.CARTESIAN,
                ),
        )

    operator fun get(id: Int): MovementEffect? = byId[id]

    /** The effect id AVS writes when a preset carries its own script. */
    const val CUSTOM = 0x7FFF
}
