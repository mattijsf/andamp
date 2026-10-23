// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * The adb switch for modern skins while they are in development, in every build. `<package>` is
 * nl.mattix.andamp, plus the id suffix when the build has one (nl.mattix.andamp.dev):
 *
 *     adb push Bento.wal /sdcard/Android/data/<package>/files/wal-check/
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal check
 *     adb logcat -s ModernSkin
 *     adb pull /sdcard/Android/data/<package>/files/wal-check/
 *
 * `check` runs every .wal in that folder through [WalCheck], which leaves a PNG beside each skin. A
 * run stops after [BUDGET_MS] and says how many skins are left; `check` again carries on, and
 * `--ez fresh true` starts over. Send it without `--receiver-foreground`, which allows less time.
 * Other broadcasts to the app, such as the widget's buttons, wait while a part runs.
 *
 * Timings depend on how much CPU Android gives the app: more while it is on screen or playing.
 * The run's first log line says which; open Andamp first to compare runs.
 *
 * Guarded by android.permission.DUMP in the manifest: only the shell, or an app granted DUMP over
 * adb, can send it.
 */
class WalDevSwitch : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val command = intent.getStringExtra("wal")
        if (command != "check") {
            Log.w(WalHost.TAG, "WalDevSwitch: say --es wal check (got $command)")
            return
        }
        val dir = context.getExternalFilesDir("wal-check")
        if (dir == null) {
            Log.w(WalHost.TAG, "check: no external files folder")
            return
        }
        val fresh = intent.getBooleanExtra("fresh", false)
        // a new install starts the run over: its engine has not checked anything yet
        val build =
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .lastUpdateTime
                .toString()
        val pending = goAsync()
        thread(name = "wal-check") {
            try {
                Log.i(
                    WalHost.TAG,
                    "check: app importance ${importance()} (100 is on screen, 125 playing, 400 and up in the background)",
                )
                WalCheck.run(dir, context.cacheDir, BUDGET_MS, fresh, build)
            } finally {
                pending.finish()
            }
        }
    }

    private fun importance(): Int =
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance

    private companion object {
        /** A part's time: other broadcasts to the app wait behind it, and a background broadcast may take a minute. */
        const val BUDGET_MS = 15_000L
    }
}
