// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A .lua handed to Andamp by another app reaches the plug-in install, and
 * nothing else does.
 *
 * Read against the merged manifest, through the platform's own intent
 * matching: Android has no type for .lua, so a file manager sends
 * application/octet-stream, and the filter has to take that on a .lua path
 * without putting Andamp in the "Open with" list of every unknown file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginImportTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun opens(intent: Intent): Boolean =
        context.packageManager
            .queryIntentActivities(intent.setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.name == PluginImportActivity::class.java.name }

    private fun view(
        uri: String,
        type: String,
    ) = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), type)

    @Test
    fun `a lua file from a file manager, typed as octet-stream, is offered to Andamp`() {
        assertTrue(opens(view("content://com.example.files/document/Download/citrus.lua", "application/octet-stream")))
        assertTrue(opens(view("file:///storage/emulated/0/Download/my.effect.v2.lua", "application/octet-stream")))
    }

    @Test
    fun `a file named as lua by its type is offered to Andamp, opened or shared`() {
        assertTrue(opens(view("content://com.example.chat/attachment/42", "text/x-lua")))
        assertTrue(opens(Intent(Intent.ACTION_SEND).setType("application/x-lua")))
    }

    @Test
    fun `other unknown files are not`() {
        assertTrue(!opens(view("content://com.example.files/document/Download/archive.bin", "application/octet-stream")))
        assertTrue(!opens(view("content://com.example.files/document/Download/notes.txt", "text/plain")))
    }

    /** The site's Install button is a link to the .lua, which Andamp opens. */
    @Test
    fun `a plug-in link on the site opens Andamp, and other links do not`() {
        val link =
            Intent(Intent.ACTION_VIEW, Uri.parse("https://mattix.nl/andamp/extensions/plugins/warmth.lua"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
        assertTrue(opens(link))
        assertTrue(
            !opens(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://mattix.nl/andamp/extensions/plugins/"),
                ).addCategory(Intent.CATEGORY_BROWSABLE),
            ),
        )
        assertTrue(
            !opens(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://example.org/andamp/extensions/plugins/warmth.lua"),
                ).addCategory(Intent.CATEGORY_BROWSABLE),
            ),
        )
    }

    @Test
    fun `the file is the one a VIEW points at or a SEND carries`() {
        val file = Uri.parse("content://com.example.files/document/gain.lua")

        assertEquals(file, handed(Intent(Intent.ACTION_VIEW, file)))
        assertEquals(file, handed(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, file)))
        assertNull(handed(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun `the import activity is exported`() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, PluginImportActivity::class.java), 0)
        assertTrue("the import activity is reachable from other apps", info.exported)
    }
}
