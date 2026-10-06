// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

/**
 * What ships inside Andamp that somebody else wrote.
 *
 * `NOTICE.md` records this for readers of the repository; this list is the copy in the app.
 * LGPL-2.1 asks for its text, an offer of source and the means to relink, and the OFL asks for its
 * text to be carried. Both texts ship as assets ([Notices.PROJECTM_LICENSE_ASSET],
 * [Notices.LIBERATION_SANS_LICENSE_ASSET]); no screen shows them.
 *
 * Kept as data so `NoticesTest` can check it against the build files and catch a new dependency
 * that has no entry.
 *
 * The bundled skins are the project's own, so they have no entry. The bundled intro audio has none
 * either: it keeps DJ Mike Llama's music under a re-recorded vocal, and the license of that music
 * is not established. NOTICE.md records it.
 */
data class Notice(
    /** Its name, as its authors write it. */
    val name: String,
    /** The license, by its usual short name. */
    val licence: String,
    /** One line: what it does here. */
    val what: String,
    /** Where the source and the license text live. */
    val url: String,
    /**
     * Maven groups this entry covers, so a test can tell whether a declared dependency has been
     * named. Empty for what arrives as source, art or transcription.
     */
    val groups: List<String> = emptyList(),
    /** What the license asks beyond attribution. */
    val obligation: String? = null,
)

/** The list itself, and the projectM version the LGPL source offer names. */
object Notices {
    /** The projectM revision this build links against; `NOTICE.md` records the same tag. */
    const val PROJECTM_PIN = "4.1.7"

    /** projectM's LGPL-2.1 text in the APK's assets, copied there by `:app:bundledLicenses`. */
    const val PROJECTM_LICENSE_ASSET = "licenses/projectM-LGPL-2.1.txt"

    /** Liberation Sans's OFL 1.1 text in the APK's assets, copied there by the same task. */
    const val LIBERATION_SANS_LICENSE_ASSET = "licenses/LiberationSans-OFL-1.1.txt"

    val ALL =
        listOf(
            Notice(
                name = "projectM",
                licence = "LGPL-2.1",
                what = "The Milkdrop visualizer engine, rendering .milk presets.",
                url = "https://github.com/projectM-visualizer/projectm",
                obligation =
                    "Andamp links projectM $PROJECTM_PIN dynamically, as a separate library in the app, " +
                        "so it can be replaced. The source for that exact revision is tag v$PROJECTM_PIN at " +
                        "the address above, and the glue Andamp builds around it is in its own repository. " +
                        "No changes were made to projectM.",
            ),
            Notice(
                name = "AVS (vis_avs)",
                licence = "BSD 3-clause",
                what = "Winamp's visualizer, reimplemented in Kotlin from Nullsoft's released source.",
                url = "https://github.com/grandchild/vis_avs",
            ),
            Notice(
                name = "AVS-File-Decoder",
                licence = "MIT",
                what = "The .avs preset format's framing and field layouts, as the preset reader follows them.",
                url = "https://github.com/grandchild/AVS-File-Decoder",
            ),
            Notice(
                name = "projectM-eval",
                licence = "MIT",
                what = "Evaluates the expressions AVS presets are scripted in, built into the AVS library.",
                url = "https://github.com/projectM-visualizer/projectm-eval",
            ),
            Notice(
                name = "MilkDrop",
                licence = "Attribution",
                what = "Ryan Geiss's original, whose .milk presets projectM renders.",
                url = "https://www.geisswerks.com/milkdrop/",
            ),
            Notice(
                name = "Webamp",
                licence = "MIT",
                what = "Its sprite maps are the ground truth for every coordinate in the skinned windows.",
                url = "https://github.com/captbaritone/webamp",
            ),
            Notice(
                name = "Liberation Sans",
                licence = "SIL OFL 1.1",
                what = "The playlist's font, metric-compatible with the Arial that PLEDIT.TXT names.",
                url = "https://github.com/liberationfonts/liberation-fonts",
                obligation = "Bundled unmodified, under a name the license permits.",
            ),
            Notice(
                name = "LuaJ",
                licence = "MIT",
                what = "Runs the .lua files that describe effects, in a sandbox.",
                url = "https://github.com/luaj/luaj",
                groups = listOf("org.luaj"),
            ),
            Notice(
                name = "Google Play Billing Library",
                licence = "Android SDK License",
                what = "Takes the tips on the Support Andamp page, through Google Play.",
                url = "https://developer.android.com/google/play/billing",
                groups = listOf("com.android.billingclient"),
                obligation =
                    "This library is not open source. Andamp's license carries an additional permission " +
                        "to combine Andamp with it and to distribute the result.",
            ),
            Notice(
                name = "AndroidX and Jetpack Compose",
                licence = "Apache-2.0",
                what = "The UI toolkit, navigation, lifecycle, and Media3 for local playback.",
                url = "https://github.com/androidx/androidx",
                groups = listOf("androidx"),
            ),
            Notice(
                name = "Kotlin and kotlinx.coroutines",
                licence = "Apache-2.0",
                what = "The language and its concurrency library.",
                url = "https://github.com/JetBrains/kotlin",
                groups = listOf("org.jetbrains"),
            ),
            Notice(
                name = "Material Components for Android",
                licence = "Apache-2.0",
                what = "Its color engine grows the modern screens' palette from the loaded skin.",
                url = "https://github.com/material-components/material-components-android",
                groups = listOf("com.google.android.material"),
            ),
            Notice(
                name = "Coil",
                licence = "Apache-2.0",
                what = "Loads and caches the skin museum's thumbnails.",
                url = "https://github.com/coil-kt/coil",
                groups = listOf("io.coil-kt.coil3"),
            ),
            Notice(
                name = "OkHttp",
                licence = "Apache-2.0",
                what = "The one HTTP client, for the skin museum and for radio streams.",
                url = "https://github.com/square/okhttp",
                groups = listOf("com.squareup.okhttp3"),
            ),
        )

    /** Whether [group] is named by some entry, matching on the group or a prefix of it. */
    fun covers(group: String): Boolean =
        ALL.any { notice ->
            notice.groups.any { group == it || group.startsWith("$it.") }
        }
}
