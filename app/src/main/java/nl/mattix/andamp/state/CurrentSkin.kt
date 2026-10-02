// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.util.Log
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.skin.LiveSkins
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.skin.SystemPalette

/**
 * The skin the app is wearing, for anything that draws without the player's state.
 *
 * [SkinOps] holds the skin while the app is on screen. A home screen widget is woken by the
 * launcher with no such state, so it loads the same skin from the same library through
 * this object. The order of fallbacks is here once, for both: a live skin from the phone's
 * palette, the stored skin, and the bundled one when either fails or none is stored.
 */
object CurrentSkin {
    private const val TAG = "CurrentSkin"

    /**
     * The live skins, fed by the phone's palette unless the listener switched that off.
     * The preference is applied here because the skin package does not depend on state.
     */
    val live: LiveSkins =
        LiveSkins { context, dark -> if (PaletteStore(context).enabled) SystemPalette.of(context, dark) else null }

    /**
     * The base skin: rebuilt from the phone's palette when there is one, the shipped file
     * otherwise. A user skin that lacks a sheet borrows it from this.
     */
    fun base(
        context: Context,
        live: LiveSkins = this.live,
    ): Skin = live.build(context, BundledSkins.BASE) ?: SkinLoader.loadBase(context)

    /**
     * The base skin, or what is worn over it: the listener's own choice, or a music
     * source's skin while one is worn. Null only when the bundled skin does not decode.
     */
    fun load(
        context: Context,
        library: SkinLibrary = SkinLibrary(context),
        live: LiveSkins = this.live,
        /** The worn skin's id; a caller that has already read it passes it on. */
        id: String = library.worn,
    ): Skin? {
        val base = runCatching { base(context, live) }.getOrNull()
        if (base == null) {
            Log.w(TAG, "the bundled skin would not decode; there is nothing to draw with")
            return null
        }
        return stored(context, library, id, base, live) ?: base
    }

    @Volatile
    private var cached: Pair<String, Skin>? = null

    /**
     * What a cached skin depends on: the worn id (a source's skin while one is on, the
     * listener's own otherwise) and the palette stamps of the base and of the entry when
     * they are live. Without a palette both stamps are null.
     */
    fun cacheKey(
        context: Context,
        library: SkinLibrary,
        live: LiveSkins = this.live,
        /** The worn skin's id; a caller that has already read it passes it on. */
        id: String = library.worn,
    ): String {
        val base = live.stamp(context, BundledSkins.BASE)?.contentHashCode()
        val entry = BundledSkins.of(id)?.let { live.stamp(context, it) }?.contentHashCode()
        return "$id|$base|$entry"
    }

    /**
     * [load], cached. The widget's clock asks for the skin every tick, and parsing a skin
     * is expensive. The cache is keyed by [cacheKey], so another skin, a source's skin
     * going on or a wallpaper change is picked up on the next draw.
     */
    fun cached(
        context: Context,
        library: SkinLibrary = SkinLibrary(context),
        live: LiveSkins = this.live,
    ): Skin? = keyed(context, library, live)?.second

    /**
     * [cached], with the [cacheKey] it belongs to, for the widget, which keeps the key to
     * decide whether to send the whole window again. The worn id is read once and both
     * values come from that read, so the skin and the key always match.
     */
    fun keyed(
        context: Context,
        library: SkinLibrary = SkinLibrary(context),
        live: LiveSkins = this.live,
    ): Pair<String, Skin>? {
        val id = library.worn
        val key = cacheKey(context, library, live, id)
        cached?.let { if (it.first == key) return it }
        val loaded = load(context, library, live, id) ?: return null
        return (key to loaded).also { cached = it }
    }

    /**
     * The stored skin [id], or null when there is none or it does not parse, in which case
     * the caller uses the bundled skin. A live entry is built from its template first and
     * falls through to its file when the phone has no palette.
     */
    @Suppress("TooGenericExceptionCaught") // a corrupt skin file can throw any exception
    fun stored(
        context: Context,
        library: SkinLibrary,
        id: String,
        fallback: Skin?,
        live: LiveSkins = this.live,
    ): Skin? {
        if (id == SkinEntry.BASE_ID) return null
        BundledSkins.of(id)?.let { bundled -> live.build(context, bundled)?.let { return it } }
        return try {
            library.open(id)?.use { SkinLoader.load(it, fallback = fallback, name = library.nameOf(id)) }
        } catch (e: Exception) {
            Log.w(TAG, "Stored skin '$id' failed to load", e)
            null
        }
    }
}
