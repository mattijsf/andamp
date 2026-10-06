// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GrantScope]: which persisted permissions a prune keeps. */
class GrantScopeTest {
    private val folder = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FSongs"
    private val trackInFolder = "$folder/document/primary%3ADownload%2FSongs%2Fsong.mp3"
    private val pickedFile = "content://com.android.providers.downloads.documents/document/msf%3A23"

    @Test
    fun `a grant on the exact file is kept`() {
        assertTrue(GrantScope.isNeeded(pickedFile, setOf(pickedFile)))
    }

    @Test
    fun `a folder grant is kept while any of its tracks are queued`() {
        // the queue names the folder's tracks, never the folder
        assertTrue(GrantScope.isNeeded(folder, setOf(trackInFolder)))
    }

    @Test
    fun `three quarters of what the platform holds are kept before any is given up`() {
        assertEquals(96, GrantScope.room(sdk = 26))
        assertEquals(96, GrantScope.room(sdk = 29))
        assertEquals(384, GrantScope.room(sdk = 30))
        assertEquals(384, GrantScope.room(sdk = 36))
    }

    @Test
    fun `a folder grant is released once its tracks are gone`() {
        assertFalse(GrantScope.isNeeded(folder, setOf(pickedFile)))
        assertFalse(GrantScope.isNeeded(folder, emptySet()))
    }

    @Test
    fun `a folder whose name merely starts the same is not covered`() {
        val other = "$folder-Old/document/primary%3ADownload%2FSongs-Old%2Fsong.mp3"

        assertFalse("a prefix match stops at a path boundary", GrantScope.isNeeded(folder, setOf(other)))
    }

    @Test
    fun `an unrelated grant is released`() {
        assertFalse(GrantScope.isNeeded("content://com.example/tree/other", setOf(trackInFolder, pickedFile)))
    }
}
