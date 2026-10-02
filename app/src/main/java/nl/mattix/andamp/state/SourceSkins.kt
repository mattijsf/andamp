// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The skin each music source asks for, chosen on its Preferences page.
 *
 * No entry means Default: the listener's own skin, the one the Skins menu picks. A choice
 * is worn over that while a track from the source is the current one.
 *
 * The phone has no entry, and neither does a source that does not support the setting; see
 * [ExtraSource.skinnable]. Held as snapshot state too, so a page shows a choice when it is
 * made.
 */
class SourceSkins(
    private val prefs: SharedPreferences,
    /** Whether a skin is still in the library; a choice of one that is not counts as Default. */
    private val installed: (String) -> Boolean = { true },
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        SkinLibrary(context)::has,
    )

    private var chosen: Map<String, String> by mutableStateOf(read())

    /** The skin [source] asks for, or null for Default. */
    fun of(source: MusicSource): String? = chosen[source.id]

    /**
     * The skin a track from [source] wears over the listener's own, or null when it wears
     * their own. A track wears a skin when it is not the phone's, its source takes one, one
     * has been chosen for it, and that skin is still installed. The player and the home
     * screen widget both use this rule.
     */
    fun wornFor(
        source: MusicSource,
        supports: (MusicSource) -> Boolean = ::takesSkin,
    ): String? = of(source)?.takeIf { source != MusicSource.LOCAL && supports(source) && installed(it) }

    /** Sets [skin] for [source]; null goes back to Default. */
    fun choose(
        source: MusicSource,
        skin: String?,
    ) {
        chosen = if (skin == null) chosen - source.id else chosen + (source.id to skin)
        prefs.edit().apply { if (skin == null) remove(source.id) else putString(source.id, skin) }.apply()
    }

    /** A skin left the library: every source that asked for it goes back to Default. */
    fun forgetSkin(skin: String) {
        val gone = chosen.filterValues { it == skin }.keys
        if (gone.isEmpty()) return
        chosen = chosen - gone
        prefs.edit().apply { gone.forEach(::remove) }.apply()
    }

    private fun read(): Map<String, String> =
        prefs.all
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            .toMap()

    private companion object {
        const val PREFS = "source_skins"
    }
}

/** Whether [source] takes a skin of its own, as the pack that brings it says; see [ExtraSource.skinnable]. */
fun takesSkin(source: MusicSource): Boolean = PackSources.found.any { it.source == source && it.skinnable }

/**
 * Wears the skin of whichever source the current track is from. It is told the source when
 * the current track changes source, and applies a new choice at once when it is for the
 * source that is playing. The skin comes from [SourceSkins.wornFor].
 */
class SourceSkinOps(
    val skins: SourceSkins,
    private val wear: (String?) -> Unit,
    /** Whether [source] takes a skin of its own; see [ExtraSource.skinnable]. */
    val supports: (MusicSource) -> Boolean = ::takesSkin,
) {
    /** The source of the current track, once there is one. */
    var playing: MusicSource? = null
        private set

    /** The current track is from [source]. */
    fun playingFrom(source: MusicSource) {
        playing = source
        wear(skins.wornFor(source, supports))
    }

    /** The listener chose [skin] for [source] on its page. */
    fun choose(
        source: MusicSource,
        skin: String?,
    ) {
        skins.choose(source, skin)
        if (source == playing) wear(skins.wornFor(source, supports))
    }
}
