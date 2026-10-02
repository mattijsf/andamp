// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * The two things outside playback that decide whether the widget is worth feeding, as flows, so a
 * collector waits for a change and does not poll.
 */
object WidgetSignals {
    /** Whether the screen is on. */
    fun screenOn(context: Context): Flow<Boolean> =
        callbackFlow {
            val power = context.getSystemService(PowerManager::class.java)
            trySend(power?.isInteractive != false)
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        ignored: Context?,
                        intent: Intent,
                    ) {
                        trySend(intent.action == Intent.ACTION_SCREEN_ON)
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
            // these two are protected broadcasts and cannot be registered in a manifest, so the
            // receiver is registered while somebody is collecting
            context.registerReceiver(receiver, filter)
            awaitClose { runCatching { context.unregisterReceiver(receiver) } }
        }.conflate()

    /** What the listener chose for the widget, re-emitted when a setting changes. */
    fun settings(context: Context): Flow<WidgetSettings> =
        callbackFlow {
            val prefs = WidgetSettings.prefs(context)
            trySend(WidgetSettings.read(context))
            val listener =
                android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    // the snapshot shares this file and writes every second while playing; the key
                    // check keeps those writes from re-emitting the settings and restarting the
                    // analyzer's burst
                    if (key == null || key.startsWith("settings.")) trySend(WidgetSettings.read(context))
                }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }.conflate()
}
