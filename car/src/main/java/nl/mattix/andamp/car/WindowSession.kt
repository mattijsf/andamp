// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.car

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * One connection to a car. The car's surface is lent to the player for as long as the
 * connection lasts.
 */
internal class WindowSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val lent = LentSurface(carContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(lent)
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) = lent.takeBack()
            },
        )
        return WindowScreen(carContext)
    }
}
