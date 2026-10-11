// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.car

import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink

/**
 * The one screen: the car's surface with nothing of the car's over it but the strip of
 * buttons the template requires. Without the player on the phone, it says so.
 */
internal class WindowScreen(
    carContext: CarContext,
) : Screen(carContext) {
    override fun onGetTemplate(): Template = if (playerInstalled()) surface() else noPlayer()

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

    private fun playerInstalled(): Boolean =
        runCatching { carContext.packageManager.getPackageInfo(RemoteWindowLink.PLAYER, 0) }
            .map { true }
            .getOrElse { it !is PackageManager.NameNotFoundException }
}
