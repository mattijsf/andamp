// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.state.AppViewModels
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.InMemoryEqPresetStore
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A Winamp skin handed to Andamp by another app, classic or modern, reaches Load skin's import,
 * and other files do not. The app then comes forward on the player, where the skin goes on.
 *
 * Read against the merged manifest, through the platform's own intent matching: a file manager
 * hands a skin over as application/octet-stream or a zip type, so the filter takes those on a
 * .wsz or .wal path without putting Andamp in the "Open with" list of every zip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkinImportTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun opens(intent: Intent): Boolean =
        context.packageManager
            .queryIntentActivities(intent.setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.name == SkinImportActivity::class.java.name }

    private fun view(
        uri: String,
        type: String,
    ) = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), type)

    @Test
    fun `a classic or modern skin from a file manager is offered to Andamp`() {
        assertTrue(opens(view("content://com.example.files/document/Download/Bento.wal", "application/octet-stream")))
        assertTrue(opens(view("content://com.example.files/document/Download/base-2.91.wsz", "application/zip")))
        assertTrue(opens(view("file:///storage/emulated/0/Download/Winamp.Modern.v2.wal", "application/x-zip-compressed")))
    }

    @Test
    fun `a file named as a Winamp skin by its type is offered, opened or shared`() {
        assertTrue(opens(view("content://com.example.chat/attachment/42", "interface/x-winamp-skin")))
        assertTrue(opens(view("content://com.example.chat/attachment/43", "interface/x-winamp3-skin")))
        assertTrue(opens(Intent(Intent.ACTION_SEND).setType("application/x-winamp-skin")))
    }

    @Test
    fun `other zips and unknown files are not, and a shared zip is not`() {
        assertFalse(opens(view("content://com.example.files/document/Download/photos.zip", "application/zip")))
        assertFalse(opens(view("content://com.example.files/document/Download/archive.bin", "application/octet-stream")))
        assertFalse(opens(Intent(Intent.ACTION_SEND).setType("application/zip")))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // the player's base skin decodes through BitmapFactory
    fun `a skin handed over brings the app forward on the player`() {
        val player =
            ViewModelProvider(
                AppViewModels,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") // the provider asks only for WinampViewModel here
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        WinampViewModel(
                            context,
                            createBackend = { scope -> MockBackend(FakeTracks.tracks, scope) },
                            presetStore = InMemoryEqPresetStore(),
                        ) as T
                },
            )[WinampViewModel::class.java]
        // a file that cannot be read changes nothing, so no load runs past the test
        val skin = Uri.fromFile(File(context.cacheDir, "missing.wsz"))
        try {
            val activity =
                Robolectric
                    .buildActivity(SkinImportActivity::class.java, view(skin.toString(), "application/octet-stream"))
                    .create()
                    .get()

            assertEquals(Screen.PLAYER, player.state.arrivalDestination)
            assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
            assertTrue(activity.isFinishing)
        } finally {
            AppViewModels.viewModelStore.clear()
        }
    }
}
