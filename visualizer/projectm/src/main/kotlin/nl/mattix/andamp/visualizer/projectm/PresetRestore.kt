// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

import java.io.File

/**
 * Finds a remembered preset in a freshly loaded pack. It needs no device or GL
 * context, so a JVM test covers it.
 *
 * It matches by name, which is what the view remembers. An index holds for one
 * load only: the playlist is in the order projectM's directory walk returns,
 * and the pack's files can change between two loads.
 */
internal object PresetRestore {
    /**
     * Where [name] sits in [paths], or -1 when the pack does not have it: a
     * preset can be deleted, or the pack swapped, between two loops.
     */
    fun indexOf(
        paths: List<String>,
        name: String?,
    ): Int {
        if (name.isNullOrEmpty()) return -1
        return paths.indexOfFirst { File(it).nameWithoutExtension == name }
    }
}
