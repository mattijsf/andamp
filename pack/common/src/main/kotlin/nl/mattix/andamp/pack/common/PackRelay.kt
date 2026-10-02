// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common

import kotlinx.coroutines.flow.StateFlow
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.packapi.PackState
import nl.mattix.andamp.core.packapi.toPack

/**
 * Sends every state the backend reaches out of the process as a [PackState]. It needs no
 * Android, so it can be tested on a JVM.
 */
object PackRelay {
    /** Pushes every state the backend reaches to [say], for as long as this runs. */
    suspend fun relay(
        state: StateFlow<BackendState>,
        say: (PackState) -> Unit,
    ) {
        state.collect { say(it.toPack()) }
    }
}
