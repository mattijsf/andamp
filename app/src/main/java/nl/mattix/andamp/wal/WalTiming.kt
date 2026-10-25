// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.BuildConfig

/**
 * The modern-skin timing log, under [TAG]: each engine tick and each frame published with what
 * caused it, how long the touches waited for the engine thread, the load, and where the stack is
 * placed on the screen. On in a debug build from the start; [WalDevSwitch] turns it on in any build
 * (`--es wal timing`) and off again, until the app's process ends.
 */
internal object WalTiming {
    /** The log tag of the timing lines. */
    const val TAG = "WalTiming"

    /** Whether the timing lines are logged now; read on every thread. */
    @Volatile
    var on: Boolean = BuildConfig.DEBUG

    /**
     * The line for one tick or one published frame on engine thread [thread]: the work before the
     * frame (the tick, the touch or the task) and the frame's draw list, in ns (0 when nothing was
     * published), and what caused it. A tick that published nothing has its line too.
     */
    fun line(
        thread: String,
        workNanos: Long,
        publishNanos: Long,
        cause: WalCause,
    ): String = "tick $thread: tick $workNanos ns, publish $publishNanos ns, ${cause.word}"
}

/** What made the engine tick a skin or publish a frame of it, as the timing lines name it. */
internal enum class WalCause(
    val word: String,
) {
    /** A tick the screen asked for on a display frame. */
    TICK("tick"),

    /** A tick the engine sets itself for when something on the skin is due, while the screen does not tick it ([WalRest]). */
    WAKE("wake"),

    /** A tick for a change the skin shows, such as the volume or the track ([WalSurface.wakeKey]). */
    KEY("key"),

    /** The listener's touch on the skin, or Back closing one of its windows. */
    TOUCH("touch"),

    /** A new box for the skin, or its first frame. */
    FIT("fit"),

    /** The skin as it stands when its popup menu opens. */
    MENU("menu"),

    /** Other work posted to the engine thread. */
    TASK("task"),
}
