// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import nl.mattix.andamp.state.AppViewModels

/**
 * The three owners a Compose view needs, for a view that belongs to no activity.
 *
 * The view model store is the shared one: the floating player and the activity are two windows onto
 * one player. Nothing here clears it, so closing the overlay keeps the state the activity shows
 * next.
 */
class OverlayOwners :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = AppViewModels.viewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun onShown() {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun onHidden() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
