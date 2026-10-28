// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Flushes an open file or folder to the disk. A test passes one that records when it runs, or fails. */
internal typealias Flush = (FileChannel) -> Unit

/** Waits until what was written to the channel is on the disk. */
internal val forceToDisk: Flush = { it.force(true) }

/**
 * Writes this file with [write] and flushes it to the disk, so all of it is there before a rename
 * gives it a stored name: a phone that switches off just after the rename then finds the whole
 * file, not an empty one. A file not written and flushed in full is deleted, and the failure is
 * thrown.
 */
internal fun File.writeFlushed(
    flush: Flush = forceToDisk,
    write: (OutputStream) -> Unit,
) {
    var flushed = false
    try {
        FileOutputStream(this).use { out ->
            write(out)
            flush(out.channel)
        }
        flushed = true
    } finally {
        if (!flushed) delete()
    }
}

/**
 * Flushes this folder to the disk, so a rename in it is kept when the phone switches off. A folder
 * that cannot be flushed is logged; the renamed file is in place either way.
 */
internal fun File.flushFolder(flush: Flush = forceToDisk) {
    try {
        FileChannel.open(toPath(), StandardOpenOption.READ).use(flush)
    } catch (e: IOException) {
        Log.w("AtomicWrite", "the folder $name was not flushed", e)
    }
}

/**
 * Writes [text] as this file, whole or not at all: into a scratch file beside it, flushed to the
 * disk and then renamed over it, so a crash or a phone switching off mid-write leaves the old
 * file or the new one. Each write gets a separate scratch name, because saves overlap and a shared
 * name would let one rename take the file from under the next.
 *
 * Throws when the file could not be written.
 */
internal fun File.writeAtomically(
    text: String,
    flush: Flush = forceToDisk,
) {
    parentFile?.mkdirs()
    val tmp = File.createTempFile(name, ".tmp", parentFile)
    try {
        tmp.writeFlushed(flush) { it.write(text.toByteArray()) }
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
