// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether a pack is in the listener's app list, as a settings page sees it. An interface,
 * so that a page can be tested without a package manager.
 */
interface AppListEntry {
    /** Whether the pack's icon is in the app list now. */
    val shown: Boolean

    /** Puts the icon in the app list or takes it out, and remembers that the listener chose so. */
    fun show(shown: Boolean)
}

/**
 * A pack's launcher icon, which the listener may take out of their app list.
 *
 * A pack is reached two ways: from its own icon, and from Andamp, which starts the activity
 * answering `nl.mattix.andamp.source.SETTINGS`. A listener who does not want the icon can
 * remove it; the way in through Andamp stays.
 *
 * The icon is an alias that the manifest declares disabled. Disabling a launcher activity
 * at run time does not remove its icon from Android 10 on: for an app that requests any
 * permission and whose manifest declares an enabled launcher activity,
 * `LauncherAppsService.shouldShowSyntheticActivity` makes the launcher show a synthetic
 * icon that opens the app's system info page. The check reads the manifest's `enabled`, not
 * the state set since, so only a launcher entry that the manifest declares off can
 * disappear.
 *
 * So a pack declares its MAIN/LAUNCHER filter on an `<activity-alias>` with
 * `android:enabled="false"` that points at the settings activity, and this class enables
 * the alias at run time. The settings activity keeps the SETTINGS filter and stays enabled.
 * [restore] enables the alias unless the listener has hidden it; the choice is kept in this
 * app's own preferences.
 *
 * Call [restore] where the process is sure to start: the bound service's `onCreate`,
 * because Andamp binds a pack as soon as it finds one, and the settings activity's
 * `onCreate`, for a pack opened without Andamp. Changes are made with `DONT_KILL_APP`, and
 * only when the state differs from the one wanted.
 *
 * @param alias the alias's fully qualified name, as the manifest's `android:name` resolves
 *   it; it is a name, not a class, so it need not exist in code.
 */
class PackLauncherEntry(
    context: Context,
    alias: String,
) : AppListEntry {
    private val app = context.applicationContext
    private val component = ComponentName(app, alias)
    private val choice = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * Whether the alias is on right now.
     *
     * Only an explicit enable counts: the default state is the manifest's, and
     * the manifest says off.
     */
    override val shown: Boolean
        get() = app.packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Whether the listener has taken the icon away; false until they do. */
    val hidden: Boolean
        get() = choice.getBoolean(HIDDEN, false)

    override fun show(shown: Boolean) {
        // committed before the component changes, so that a start running meanwhile reads
        // the new choice and does not put the icon back
        choice.edit().putBoolean(HIDDEN, !shown).commit()
        apply(shown)
    }

    /** Puts the alias in the state the listener last chose, which is shown for somebody who never chose. */
    fun restore() = apply(!hidden)

    private fun apply(shown: Boolean) {
        if (this.shown == shown) return
        // the manifest's default, which is off, so no override is left behind
        val state =
            if (shown) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        app.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }

    private companion object {
        /** Its own file, so it cannot collide with whatever the pack keeps in its own. */
        const val PREFERENCES = "andamp_pack_launcher"
        const val HIDDEN = "hidden"
    }
}
