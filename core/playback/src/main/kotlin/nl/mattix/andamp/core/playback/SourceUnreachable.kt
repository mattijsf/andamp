// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

/**
 * Thrown by a [BrowseSource] that could not answer, as opposed to an empty list, which
 * means there is nothing there.
 *
 * It is not a [kotlinx.coroutines.CancellationException]; whoever catches it must let
 * cancellation through.
 *
 * [reason] is for a log or a test. The player words its own message for the listener.
 */
class SourceUnreachable(
    val reason: String,
    cause: Throwable? = null,
) : Exception(reason, cause)
