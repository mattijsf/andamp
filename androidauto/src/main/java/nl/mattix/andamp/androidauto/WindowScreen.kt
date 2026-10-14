// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.androidauto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink

/**
 * The one screen: the car's surface with nothing of the car's over it but the strip of
 * buttons the template requires. Its one button calls [onButton]. While the player's main
 * window is up ([mainUp]) it shows the list of songs, which it brings up; while the equalizer
 * or the list is up it is an X, which brings the main window back. Without a player on the
 * phone that lends its window, it says what to do. With one that speaks another version of the
 * window's interface, it says once which app to update.
 */
internal class WindowScreen(
    carContext: CarContext,
    private val onButton: () -> Unit = {},
) : Screen(carContext) {
    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = tellWhatToUpdate()
            },
        )
    }

    /** Whether the car was told this time that one of the two apps needs an update. */
    private var told = false

    /** Whether the player's main window is up, as the player last said. */
    var mainUp = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onGetTemplate(): Template = if (Player.reach(carContext) != null) surface() else noPlayer()

    private fun surface(): Template =
        NavigationTemplate
            .Builder()
            .setActionStrip(ActionStrip.Builder().addAction(button()).build())
            // the car reports what happens on the surface only to a template with this button
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()

    /** The one button the template requires: the list of songs, or an X back to the main window. */
    private fun button(): Action {
        val icon = IconCompat.createWithResource(carContext, if (mainUp) R.drawable.ic_playlist else R.drawable.ic_close)
        return Action
            .Builder()
            .setIcon(CarIcon.Builder(icon).setTint(CarColor.DEFAULT).build())
            .setOnClickListener { onButton() }
            .build()
    }

    /**
     * Says once, in a toast, which app to update when the player and this app speak different
     * versions of the window's interface. The window shows all the same: what one of them
     * lacks does nothing.
     */
    private fun tellWhatToUpdate() {
        if (told || Player.reach(carContext) == null) return
        told = true
        val player = Player.protocol(carContext)
        val update =
            when {
                player < RemoteWindowLink.PROTOCOL -> R.string.update_player
                player > RemoteWindowLink.PROTOCOL -> R.string.update_this_app
                else -> return
            }
        CarToast.makeText(carContext, update, CarToast.LENGTH_LONG).show()
    }

    private fun noPlayer(): Template =
        MessageTemplate
            .Builder(carContext.getString(R.string.no_player))
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .build()
}
