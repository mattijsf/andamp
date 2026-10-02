// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.debug

import android.app.Activity
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only: sets the device's wallpaper.
 *
 *     adb shell am broadcast -n nl.mattix.andamp/nl.mattix.andamp.debug.ScreenshotWallpaper --es color '#008080'
 *     adb shell am broadcast -n nl.mattix.andamp/nl.mattix.andamp.debug.ScreenshotWallpaper --es file wallpaper.png
 *
 * The player is translucent, so the wallpaper shows around it. `file` is a name inside the app's
 * own files directory, which needs no storage permission to read.
 *
 * Both the home screen and the lock screen are set.
 */
class ScreenshotWallpaper : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val color = intent.getStringExtra("color")
        val file = intent.getStringExtra("file")
        val pending = goAsync()
        // off the main thread: a wallpaper is compressed and written to disk,
        // and a large picture can take longer than a receiver is allowed
        thread(name = "screenshot-wallpaper") {
            val outcome =
                runCatching {
                    val wallpapers = WallpaperManager.getInstance(context)
                    val flags = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                    when {
                        color != null -> {
                            // a solid color scales to any screen, so the picture can be tiny
                            val plain = Bitmap.createBitmap(SWATCH, SWATCH, Bitmap.Config.ARGB_8888)
                            plain.eraseColor(Color.parseColor(normalised(color)))
                            wallpapers.setBitmap(plain, null, true, flags)
                            "wallpaper set to $color"
                        }

                        file != null -> {
                            File(context.filesDir, file).inputStream().use {
                                wallpapers.setStream(it, null, true, flags)
                                "wallpaper set from $file"
                            }
                        }

                        else -> {
                            error("say --es color '#rrggbb' or --es file name.png")
                        }
                    }
                }
            outcome
                .onSuccess {
                    Log.i(TAG, it)
                    pending.resultCode = Activity.RESULT_OK
                    pending.resultData = it
                }.onFailure {
                    Log.w(TAG, "wallpaper not set", it)
                    pending.resultCode = Activity.RESULT_CANCELED
                    pending.resultData = it.message
                }
            pending.finish()
        }
    }

    /** `008080` as well as `#008080`: a `#` is a comment to an unquoted shell word. */
    private fun normalised(color: String) = if (color.startsWith("#")) color else "#$color"

    private companion object {
        const val TAG = "ScreenshotWallpaper"
        const val SWATCH = 16
    }
}
