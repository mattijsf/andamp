// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.androidauto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate

/**
 * The one screen: the car's surface with nothing of the car's over it but the strip of
 * buttons the template requires. Without a player on the phone that lends its window, it
 * says what to do.
 */
internal class WindowScreen(
    carContext: CarContext,
) : Screen(carContext) {
    override fun onGetTemplate(): Template = if (Player.reach(carContext) != null) surface() else noPlayer()

    private fun surface(): Template =
        NavigationTemplate
            .Builder()
            .setActionStrip(ActionStrip.Builder().addAction(Action.APP_ICON).build())
            // the car reports what happens on the surface only to a template with this button
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()

    private fun noPlayer(): Template =
        MessageTemplate
            .Builder(carContext.getString(R.string.no_player))
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .build()
}
