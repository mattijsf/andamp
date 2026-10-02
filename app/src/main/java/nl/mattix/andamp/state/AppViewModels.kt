// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * One [WinampViewModel] for the whole process, shared by the activity and the floating
 * overlay, so both show the same window layout, selection and shade state. Nothing clears
 * this store.
 */
object AppViewModels : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}
