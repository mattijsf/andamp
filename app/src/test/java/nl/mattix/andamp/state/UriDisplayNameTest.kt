// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A provider answering DISPLAY_NAME like the SAF Downloads provider does. */
private class FakeDocumentsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
            addRow(arrayOf("TopazAmp1-2.wsz"))
        }

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UriDisplayNameTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `resolves the display name instead of the opaque document id`() {
        // lastPathSegment of a Downloads uri is the document id, "msf:19"
        Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create("com.fake.documents")
        val uri = Uri.parse("content://com.fake.documents/document/msf%3A19")
        assertEquals("TopazAmp1-2.wsz", app.displayNameOf(uri))
    }

    @Test
    fun `falls back to the last path segment when nothing answers the query`() {
        val uri = Uri.parse("file:///sdcard/Download/XMMS-Turquoise.wsz")
        assertEquals("XMMS-Turquoise.wsz", app.displayNameOf(uri))
    }
}
