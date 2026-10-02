// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.displayNameOf

/**
 * Imports a `.zip` of `.milk` presets when something asks for one.
 *
 * A launcher belongs to the composition that created it, so whatever offers the import sets
 * `presetPickRequested` and this composable, which the screen always composes, launches.
 *
 * `application/octet-stream` is in the filter because some providers give a `.zip` no type
 * of its own.
 */
@Composable
fun PresetPicker(vm: WinampViewModel) {
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            context.contentResolver.openInputStream(uri)?.let { stream ->
                vm.presetOps.import(stream, context.displayNameOf(uri)?.removeSuffix(".zip") ?: "presets")
            }
        }
    LaunchedEffect(vm.state.presetPickRequested) {
        if (!vm.state.presetPickRequested) return@LaunchedEffect
        vm.state.presetPickRequested = false
        picker.launch(arrayOf("application/zip", "application/octet-stream"))
    }
}
