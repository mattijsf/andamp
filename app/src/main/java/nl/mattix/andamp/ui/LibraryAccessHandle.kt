// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.LibraryPrompt
import nl.mattix.andamp.state.MediaStoreAudio
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.prefs.openAppSettings

/** The audio permission as the preferences screen sees it: a status and a way to change it. */
class LibraryAccessHandle(
    val status: LibraryAccess,
    /** Asks for the permission, from a place that already says what it is for. */
    val request: () -> Unit,
    /**
     * For a place with nothing on screen about the permission, like a song
     * somebody pressed: explains first when Android recommends it, then asks,
     * and runs `then` if it was allowed; see [rememberLibraryAccess].
     */
    val ask: (then: () -> Unit) -> Unit = { request() },
)

/**
 * Tracks whether the device's audio library may be read.
 *
 * Read when the screen first composes and again on every resume: the only way out of
 * [LibraryAccess.BLOCKED] is the system's settings screen, and a grant made there changes
 * nothing else this screen reads.
 */
@Composable
fun rememberLibraryAccess(vm: WinampViewModel): LibraryAccessHandle =
    rememberLibraryAccess(
        canRead = vm.playlistFiles::canReadLibrary,
        onGrant = vm.playlistFiles::healRestoredEntries,
        explain = { ask ->
            vm.state.prompt = LibraryPrompt.explain(onDone = { vm.state.prompt = null }, ask = ask)
        },
        cannotAsk = { openSettings ->
            vm.state.prompt = LibraryPrompt.settings(onDone = { vm.state.prompt = null }, open = openSettings)
        },
    )

/**
 * [rememberLibraryAccess] with the view model's parts in it handed in, so it
 * can be tested without one.
 *
 * The flow is the one in Android's guide to requesting permissions:
 *
 * 1. Held already: go ahead.
 * 2. [rationale] (`shouldShowRequestPermissionRationale`) is true - refused
 *    once before - and nothing on screen says what the permission is for:
 *    [explain] it, with a button that asks.
 * 3. Otherwise ask straight away.
 *
 * Refused with no rationale to show, Android will not ask again: [cannotAsk] says so and
 * offers the settings screen, the only place the permission can still be given.
 */
@Composable
@Suppress("LongMethod") // one flow, read top to bottom
internal fun rememberLibraryAccess(
    canRead: () -> Boolean,
    onGrant: () -> Unit,
    /** Explain the permission, with `ask` to put the system's prompt up. */
    explain: (ask: () -> Unit) -> Unit = { ask -> ask() },
    /** Android will not ask again: say so, and offer `openSettings`. */
    cannotAsk: (openSettings: () -> Unit) -> Unit = { openSettings -> openSettings() },
    /** `shouldShowRequestPermissionRationale` for the audio permission. */
    rationale: (() -> Boolean)? = null,
): LibraryAccessHandle {
    val context = LocalContext.current
    val shouldExplain =
        rationale ?: {
            context.findActivity()?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, MediaStoreAudio.permission())
            } == true
        }
    var granted by remember { mutableStateOf(canRead()) }
    // refused, and Android has no rationale left to show: it will not ask again
    var refusedForGood by remember { mutableStateOf(false) }
    // what to do once the permission is given, for an ask that wanted
    // something done; cleared by the answer, so a refusal is not acted on later
    var then by remember { mutableStateOf<(() -> Unit)?>(null) }
    var inSettings by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = canRead()
        // coming back from the system's settings screen is its answer
        if (inSettings) {
            inSettings = false
            if (granted) {
                refusedForGood = false
                onGrant()
                then?.invoke()
            }
            then = null
        }
    }
    val openSettings = {
        inSettings = true
        context.openAppSettings()
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { given ->
            granted = given
            when {
                given -> {
                    refusedForGood = false
                    onGrant()
                    then?.invoke()
                    then = null
                }

                !shouldExplain() -> {
                    refusedForGood = true
                    cannotAsk(openSettings)
                }

                else -> {
                    then = null
                }
            }
        }
    val launch: (() -> Unit) -> Unit = { next ->
        then = next
        launcher.launch(MediaStoreAudio.permission())
    }
    return LibraryAccessHandle(
        status = LibraryAccess.of(granted, canAsk = !refusedForGood),
        request = { if (granted) Unit else launch {} },
        ask = { next ->
            when {
                granted -> next()
                shouldExplain() -> explain { launch(next) }
                else -> launch(next)
            }
        },
    )
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? =
    when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
