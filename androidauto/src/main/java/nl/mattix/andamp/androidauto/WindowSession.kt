// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.androidauto

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
        lateinit var screen: WindowScreen
        val lent = LentSurface(carContext, onMainUp = { up -> screen.mainUp = up })
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(lent)
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) = lent.takeBack()
            },
        )
        screen = WindowScreen(carContext, onButton = lent::listOrBack)
        return screen
    }
}
