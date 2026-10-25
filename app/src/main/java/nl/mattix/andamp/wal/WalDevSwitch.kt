// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The adb switch for modern skins while they are in development, in every build. `<package>` is
 * nl.mattix.andamp, plus the id suffix when the build has one (nl.mattix.andamp.dev):
 *
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal on
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal off
 *
 *     adb push Bento.wal /sdcard/Android/data/<package>/files/wal-check/
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal check
 *     adb logcat -s ModernSkin
 *     adb pull /sdcard/Android/data/<package>/files/wal-check/
 *
 * `on` and `off` set [WalGate]: they write its file and its state, so a running app follows at
 * once and the next start reads the same. The log says what the file holds.
 *
 * `check` runs every .wal in that folder through [WalCheck], which leaves a PNG beside each skin. A
 * run stops after [BUDGET_MS] and says how many skins are left; `check` again carries on, and
 * `--ez fresh true` starts over. Send it without `--receiver-foreground`, which allows less time.
 * Other broadcasts to the app, such as the widget's buttons, wait while a part runs.
 *
 * Timings depend on how much CPU Android gives the app: more while it is on screen or playing.
 * The run's first log line says which; open Andamp first to compare runs.
 *
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal timing
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal timing --ez on false
 *     adb logcat -s WalTiming
 *
 * `timing` turns the timing log ([WalTiming]) on, in a release build too, and `--ez on false`
 * turns it off; either holds until the app's process ends. A debug build starts with it on.
 *
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal direct
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal direct --ez on false
 *
 * `direct` draws skins at a fractional scale (Bento, Winamp3x) straight onto the screen with hard
 * edges ([WalRender.direct]), in place of drawing them at the next whole scale and shrinking the
 * picture; `--ez on false` goes back. It shows at once and holds until the app's process ends.
 * `--es wal draw direct` turns it on too and `--es wal draw whole` off: am takes the word after
 * `draw` as the intent's package, and drops what follows it.
 *
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal layers
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal layers --ez on false
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal layers --ez whole false
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal layers --ez partial false
 *
 * `layers` keeps each window's drawing between frames and draws again only what changed
 * ([WalRender.layers], on from the start); `--ez on false` draws every command of every frame and
 * stops the engine planning frames. `--ez whole false` keeps drawings at fractional scales only,
 * and draws a whole scale every command straight onto the screen ([WalRender.wholeOffscreen]);
 * `--ez partial false` keeps the drawings but draws the whole picture off screen again every frame
 * ([WalRender.partial]). Each `layers` sets all three, so `layers` alone turns them all on. It
 * shows at once (frames the engine made while it was off are drawn every command until the next
 * one) and holds until the app's process ends.
 *
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal hold
 *     adb shell am broadcast -n <package>/nl.mattix.andamp.wal.WalDevSwitch --es wal hold --ez on false
 *
 * `hold` keeps drawing the frame on screen while the skin plays on ([WalRender.hold]), so the
 * other switches can draw that one frame each way and screenshots compare them; `--ez on false`
 * goes back to the latest frame.
 *
 * Guarded by android.permission.DUMP in the manifest: only the shell, or an app granted DUMP over
 * adb, can send it.
 */
class WalDevSwitch : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (val command = intent.getStringExtra("wal")) {
            "check" -> check(context, intent)
            "on" -> gate(context, true)
            "off" -> gate(context, false)
            "timing" -> timing(intent.getBooleanExtra("on", true))
            "direct" -> direct(intent.getBooleanExtra("on", true))
            "draw" -> direct(intent.`package` == "direct")
            "layers" -> layers(intent)
            "hold" -> hold(intent.getBooleanExtra("on", true))
            else -> Log.w(WalHost.TAG, "WalDevSwitch: say --es wal on, off, check, timing, direct, layers or hold (got $command)")
        }
    }

    private fun gate(
        context: Context,
        on: Boolean,
    ) {
        val pending = goAsync()
        thread(name = "wal-gate") {
            try {
                WalGate.write(context, on)
                val file = WalGate.file(context)
                Log.i(WalHost.TAG, "modern skins ${if (WalGate.on) "on" else "off"}: ${file.path} holds ${file.readText()}")
            } catch (e: IOException) {
                Log.w(WalHost.TAG, "WalDevSwitch: could not write the switch", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun timing(on: Boolean) {
        WalTiming.on = on
        Log.i(WalHost.TAG, "timing log ${if (on) "on" else "off"} under ${WalTiming.TAG}")
    }

    private fun direct(on: Boolean) {
        WalRender.direct = on
        WalRender.redraws.intValue++
        Log.i(WalHost.TAG, "fractional scales drawn ${if (on) "directly with hard edges" else "whole and shrunk"}")
    }

    private fun layers(intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        val whole = intent.getBooleanExtra("whole", true)
        val partial = intent.getBooleanExtra("partial", true)
        WalRender.layers = on
        WalRender.wholeOffscreen = whole
        WalRender.partial = partial
        WalRender.redraws.intValue++
        val how =
            when {
                !on -> "with every command"
                !whole -> "keeping what did not change at fractional scales, with every command at whole scales"
                !partial -> "keeping what did not change, the whole picture drawn again each frame"
                else -> "keeping what did not change"
            }
        Log.i(WalHost.TAG, "frames drawn $how")
    }

    private fun hold(on: Boolean) {
        WalRender.hold = on
        WalRender.redraws.intValue++
        Log.i(WalHost.TAG, if (on) "the frame on screen held" else "frames drawn as the engine publishes them")
    }

    private fun check(
        context: Context,
        intent: Intent,
    ) {
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
