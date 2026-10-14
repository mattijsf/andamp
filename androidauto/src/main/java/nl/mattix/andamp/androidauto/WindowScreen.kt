// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.androidauto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat

/**
 * The one screen: the car's surface with nothing of the car's over it but the strip of
 * buttons the template requires. Its one button calls [onPlaylist], which brings the list of
 * songs up and takes it away again. Without a player on the phone that lends its window, it
 * says what to do.
 */
internal class WindowScreen(
    carContext: CarContext,
    private val onPlaylist: () -> Unit = {},
) : Screen(carContext) {
    override fun onGetTemplate(): Template = if (Player.reach(carContext) != null) surface() else noPlayer()

    private fun surface(): Template =
        NavigationTemplate
            .Builder()
            .setActionStrip(ActionStrip.Builder().addAction(playlistButton()).build())
            // the car reports what happens on the surface only to a template with this button
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()

    /** The one button the template requires: the list of songs, up or away. */
    private fun playlistButton(): Action {
        val rows = IconCompat.createWithResource(carContext, R.drawable.ic_playlist)
        return Action
            .Builder()
            .setIcon(CarIcon.Builder(rows).setTint(CarColor.DEFAULT).build())
            .setOnClickListener { onPlaylist() }
            .build()
    }

    private fun noPlayer(): Template =
        MessageTemplate
            .Builder(carContext.getString(R.string.no_player))
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .build()
}
