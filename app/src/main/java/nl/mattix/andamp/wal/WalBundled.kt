// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context
import android.os.Build
import nl.mattix.andamp.skin.BundledSkin
import nl.mattix.andamp.skin.LiveSkins
import java.io.File

/**
 * The skins that ship with the app as files the engine can open ([WalFiles.open] takes a file).
 *
 * A live skin ([BundledSkin.live]) is written in the phone's palette, one file per palette, so a
 * new wallpaper gives a new file and a new look. Any other, or a live one while the phone gives no
 * palette, is a copy of the `.wsz` it ships, made again when the app is updated. The files live in
 * the app's no-backup folder, and the [KEPT] newest of each skin stay, so a look still showing the
 * one before keeps its file.
 */
internal object WalBundled {
    private const val DIR = "wal-bundled"

    /** How many files of one skin stay: the one on screen and the one before it. */
    private const val KEPT = 2

    private val lock = Any()

    /** The file of [bundled] as it looks now, written first when it is not there yet. */
    fun fileOf(
        context: Context,
        bundled: BundledSkin,
        live: LiveSkins,
    ): File {
        val dir = File(context.noBackupFilesDir, DIR)
        val stamp = live.stamp(context, bundled)
        val name =
            if (stamp != null) {
                "${bundled.id}-live-${Integer.toHexString(stamp.contentHashCode())}.wsz"
            } else {
                "${bundled.id}-${java.lang.Long.toHexString(installedAt(context))}.wsz"
            }
        synchronized(lock) {
            val file = File(dir, name)
            if (file.isFile) return file
            dir.mkdirs()
            val bytes =
                if (stamp != null) {
                    live.archive(context, bundled)?.second
                } else {
                    null
                } ?: context.assets.open(bundled.asset).use { it.readBytes() }
            val part = File(dir, "$name.part")
            part.writeBytes(bytes)
            check(part.renameTo(file)) { "could not write $file" }
            prune(dir, bundled.id)
            return file
        }
    }

    /** Removes all but the [KEPT] newest files of the skin [id]. */
    private fun prune(
        dir: File,
        id: String,
    ) {
        dir
            .listFiles { f -> f.name.startsWith("$id-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEPT)
            ?.forEach { it.delete() }
    }

    /** When this build of the app was installed: a new build may ship other skins. */
    @Suppress("DEPRECATION") // getPackageInfo(String, Int) is the call before Android 13
    private fun installedAt(context: Context): Long {
        val pm = context.packageManager
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags
                        .of(0),
                )
            } else {
                pm.getPackageInfo(context.packageName, 0)
            }
        return info.lastUpdateTime
    }
}
