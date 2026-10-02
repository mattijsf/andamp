// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackDescriptor

/**
 * How far the player can get with a pack right now.
 *
 * Each state has a different thing to offer on screen: a pack that is not installed wants an
 * install, one built against another contract wants an update, one nobody has signed into
 * wants its settings screen opened, and only the last can be played.
 *
 * The value describes this moment only. A pack can be uninstalled while the player holds it,
 * so a verb can still fail under [Ready].
 */
sealed interface PackReach {
    /** Nothing on this phone answers the bind action. */
    data object Absent : PackReach

    /**
     * Something answers, but with a contract number this build does not implement. Nothing
     * further is asked of it. The pack stays bound, so that its process ending, which
     * installing a new one causes, is noticed.
     */
    data object Outdated : PackReach

    /**
     * The pack is there and understood, and nobody has signed into it. Its descriptor is
     * available all the same, so the page can show what does not depend on an account.
     */
    data class SignedOut(
        val descriptor: PackDescriptor,
    ) : PackReach

    /** A source that can be played and browsed, as it describes itself. */
    data class Ready(
        val descriptor: PackDescriptor,
        val account: PackAccount,
    ) : PackReach
}

/** The pack's descriptor in the states that carry one: [PackReach.SignedOut] and [PackReach.Ready]. */
val PackReach.descriptor: PackDescriptor?
    get() =
        when (this) {
            is PackReach.Ready -> descriptor
            is PackReach.SignedOut -> descriptor
            else -> null
        }
