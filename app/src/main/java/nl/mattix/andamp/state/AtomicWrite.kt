// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import java.io.File

/**
 * Writes [text] as this file, whole or not at all: into a scratch file beside it, which is
 * then renamed over it, so a crash mid-write leaves the old file. Each write gets its own
 * scratch name, because saves overlap and a shared name would let one rename take the file
 * from under the next.
 *
 * Throws when the file could not be written.
 */
internal fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File.createTempFile(name, ".tmp", parentFile)
    try {
        tmp.writeText(text)
        check(tmp.renameTo(this)) { "could not store $name" }
    } finally {
        // does nothing after a successful rename; removes the scratch file after a failed one
        tmp.delete()
    }
}

/**
 * This file's text: empty when there is no such file, null when it exists and could not be
 * read. A store that appends to what it read must not write after a failed read, or it
 * would replace everything stored.
 */
internal fun File.readOrNull(): String? = if (!exists()) "" else runCatching { readText() }.getOrNull()
