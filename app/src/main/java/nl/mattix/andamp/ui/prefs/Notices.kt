// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.content.res.Resources
import nl.mattix.andamp.R
import nl.mattix.andamp.state.Store

/**
 * What ships inside Andamp that somebody else wrote.
 *
 * `NOTICE.md` records this for readers of the repository; this list is the copy in the app.
 * LGPL-2.1 asks for its text, an offer of source and the means to relink, and the OFL asks for its
 * text to be carried. Both texts ship as assets ([Notices.PROJECTM_LICENSE_ASSET],
 * [Notices.LIBERATION_SANS_LICENSE_ASSET]); no screen shows them.
 *
 * Kept as data so `NoticesTest` can check it against the build files and catch a new dependency
 * that has no entry. The names, licenses and addresses are the same in every language; [Notice.what]
 * and [Notice.obligation] are worded from the resources the list is built with.
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
    const val PROJECTM_PIN = "4.1.8"

    /** projectM's LGPL-2.1 text in the APK's assets, copied there by `:app:bundledLicenses`. */
    const val PROJECTM_LICENSE_ASSET = "licenses/projectM-LGPL-2.1.txt"

    /** Liberation Sans's OFL 1.1 text in the APK's assets, copied there by the same task. */
    const val LIBERATION_SANS_LICENSE_ASSET = "licenses/LiberationSans-OFL-1.1.txt"

    /** Every entry, worded from [res]: the list of this project, then what the store's build carries. */
    fun all(res: Resources): List<Notice> =
        listOf(
            Notice(
                name = "projectM",
                licence = "LGPL-2.1",
                what = res.getString(R.string.prefs_notice_projectm_what),
                url = "https://github.com/projectM-visualizer/projectm",
                obligation = res.getString(R.string.prefs_notice_projectm_obligation, PROJECTM_PIN),
            ),
            Notice(
                name = "AVS (vis_avs)",
                licence = "BSD 3-clause",
                what = res.getString(R.string.prefs_notice_avs_what),
                url = "https://github.com/grandchild/vis_avs",
            ),
            Notice(
                name = "AVS-File-Decoder",
                licence = "MIT",
                what = res.getString(R.string.prefs_notice_avs_decoder_what),
                url = "https://github.com/grandchild/AVS-File-Decoder",
            ),
            Notice(
                name = "projectM-eval",
                licence = "MIT",
                what = res.getString(R.string.prefs_notice_projectm_eval_what),
                url = "https://github.com/projectM-visualizer/projectm-eval",
            ),
            Notice(
                name = "MilkDrop",
                licence = "Attribution",
                what = res.getString(R.string.prefs_notice_milkdrop_what),
                url = "https://www.geisswerks.com/milkdrop/",
            ),
            Notice(
                name = "Webamp",
                licence = "MIT",
                what = res.getString(R.string.prefs_notice_webamp_what),
                url = "https://github.com/captbaritone/webamp",
            ),
            Notice(
                name = "Liberation Sans",
                licence = "SIL OFL 1.1",
                what = res.getString(R.string.prefs_notice_liberation_sans_what),
                url = "https://github.com/liberationfonts/liberation-fonts",
                obligation = res.getString(R.string.prefs_notice_liberation_sans_obligation),
            ),
            Notice(
                name = "LuaJ",
                licence = "MIT",
                what = res.getString(R.string.prefs_notice_luaj_what),
                url = "https://github.com/luaj/luaj",
                groups = listOf("org.luaj"),
            ),
            Notice(
                name = "AndroidX and Jetpack Compose",
                licence = "Apache-2.0",
                what = res.getString(R.string.prefs_notice_androidx_what),
                url = "https://github.com/androidx/androidx",
                groups = listOf("androidx"),
            ),
            Notice(
                name = "Kotlin and kotlinx.coroutines",
                licence = "Apache-2.0",
                what = res.getString(R.string.prefs_notice_kotlin_what),
                url = "https://github.com/JetBrains/kotlin",
                groups = listOf("org.jetbrains"),
            ),
            Notice(
                name = "Material Components for Android",
                licence = "Apache-2.0",
                what = res.getString(R.string.prefs_notice_material_what),
                url = "https://github.com/material-components/material-components-android",
                groups = listOf("com.google.android.material"),
            ),
            Notice(
                name = "Coil",
                licence = "Apache-2.0",
                what = res.getString(R.string.prefs_notice_coil_what),
                url = "https://github.com/coil-kt/coil",
                groups = listOf("io.coil-kt.coil3"),
            ),
            Notice(
                name = "OkHttp",
                licence = "Apache-2.0",
                what = res.getString(R.string.prefs_notice_okhttp_what),
                url = "https://github.com/square/okhttp",
                groups = listOf("com.squareup.okhttp3"),
            ),
        ) + Store.notices(res)

    /** Whether [group] is named by some entry, matching on the group or a prefix of it. */
    fun covers(
        group: String,
        res: Resources,
    ): Boolean =
        all(res).any { notice ->
            notice.groups.any { group == it || group.startsWith("$it.") }
        }
}
