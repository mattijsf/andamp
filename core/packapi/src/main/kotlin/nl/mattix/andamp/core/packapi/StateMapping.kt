// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.packapi

import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Transport

/** Conversions between [BackendState] and [PackState], in both directions. */

fun BackendState.toPack(): PackState =
    PackState(
        transport = transport.name,
        positionMs = positionMs,
        currentIndex = currentIndex,
        queue = queue.map { it.toPack() },
        volumeFraction = volumeFraction,
        shuffle = shuffle,
        repeat = repeat,
        connecting = connecting,
        streamBitrateKbps = streamBitrateKbps ?: 0,
        notice = notice.wireName(),
        noticeSeq = noticeSeq,
        streamSampleRateKhz = streamSampleRateKhz ?: 0,
    )

/**
 * A notice crosses as a name. [BackendNotice] is a sealed interface and has no ordinal, and
 * a name the reader does not know is read as no notice.
 */
private fun BackendNotice?.wireName(): String =
    when (this) {
        BackendNotice.SourceCannotPlay -> CANNOT_PLAY
        BackendNotice.NothingPlayableHere -> NOTHING_HERE
        BackendNotice.StationLost -> STATION_LOST
        BackendNotice.ServerLost -> SERVER_LOST
        null -> ""
    }

private fun String.toNotice(): BackendNotice? =
    when (this) {
        CANNOT_PLAY -> BackendNotice.SourceCannotPlay
        NOTHING_HERE -> BackendNotice.NothingPlayableHere
        STATION_LOST -> BackendNotice.StationLost
        SERVER_LOST -> BackendNotice.ServerLost
        else -> null
    }

/** The source itself will not play; see [BackendNotice.SourceCannotPlay]. */
const val CANNOT_PLAY = "sourceCannotPlay"

/** Nothing in the queue has a player here; see [BackendNotice.NothingPlayableHere]. */
const val NOTHING_HERE = "nothingPlayableHere"

/** A station dropped and did not come back; see [BackendNotice.StationLost]. */
const val STATION_LOST = "stationLost"

/** A music server went away mid-song and did not come back; see [BackendNotice.ServerLost]. */
const val SERVER_LOST = "serverLost"

fun PackState.toState(): BackendState =
    BackendState(
        // an unknown transport is read as stopped
        transport = Transport.entries.firstOrNull { it.name == transport } ?: Transport.Stopped,
        positionMs = positionMs,
        currentIndex = currentIndex,
        queue = queue.map { it.toTrack() },
        volumeFraction = volumeFraction,
        shuffle = shuffle,
        repeat = repeat,
        streamBitrateKbps = streamBitrateKbps.takeIf { it > 0 },
        streamSampleRateKhz = streamSampleRateKhz.takeIf { it > 0 },
        connecting = connecting,
        notice = notice.toNotice(),
        noticeSeq = noticeSeq,
    )
