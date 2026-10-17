// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import android.util.Log
import nl.mattix.andamp.visualizer.core.PresetGaps
import java.io.File

/**
 * Loads an `.avs` file into an engine and says what of it is not showing.
 *
 * The render loop uses it for the preset it plays. A list of presets can also be checked
 * ahead of playing, with a separate engine off the render thread ([check]), so a
 * preset's problems can be shown before it is picked: AVS learns them from reading and
 * loading the file, and nothing it says depends on drawing.
 */
object AvsPresetCheck {
    private const val TAG = "AvsPresetCheck"

    /**
     * The whole file is read into memory before it is parsed, so a larger
     * file is refused first.
     */
    private const val MAX_PRESET_BYTES = 8L * 1024 * 1024

    /** A small frame: a check loads the preset and never draws it. */
    private const val CHECK_W = 32
    private const val CHECK_H = 24

    /**
     * Loads the preset at [path] into [engine]; the idle preset when [path] is null,
     * unreadable or does not parse. Answers what of the preset is not showing.
     */
    fun load(
        engine: AvsEngine,
        path: String?,
    ): PresetGaps {
        var why: String? = null
        val read =
            path?.let {
                try {
                    val file = File(it)
                    if (file.length() > MAX_PRESET_BYTES) throw AvsFormatException("${file.length()} bytes is no preset")
                    AvsParser.parse(file.readBytes())
                } catch (e: AvsFormatException) {
                    Log.w(TAG, "cannot run $it: ${e.message}")
                    why = e.message
                    null
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "cannot read $it: ${e.message}")
                    why = e.message
                    null
                }
            }
        try {
            engine.load(read ?: AvsIdlePreset.preset())
        } catch (
            // whatever a reader throws over a body it cannot read
            @Suppress("TooGenericExceptionCaught")
            e: RuntimeException,
        ) {
            Log.e(TAG, "preset $path would not load; falling back to idle", e)
            engine.load(AvsIdlePreset.preset())
            return PresetGaps(unreadable = true, reason = e.message)
        }
        return PresetGaps(unreadable = path != null && read == null, missing = engine.unimplemented, reason = why)
    }

    /** What of each of [paths] would not show, checked one at a time with an engine that never draws. */
    fun check(
        paths: List<String>,
        onChecked: (path: String, gaps: PresetGaps) -> Unit,
        keepGoing: () -> Boolean = { true },
    ) {
        val engine = AvsEngine(CHECK_W, CHECK_H)
        try {
            for (path in paths) {
                if (!keepGoing()) return
                onChecked(path, load(engine, path))
            }
        } finally {
            engine.close()
        }
    }
}
