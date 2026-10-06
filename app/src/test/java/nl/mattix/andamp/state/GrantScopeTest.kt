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
    fun `a row from a folder says which folder, and other rows say none`() {
        assertEquals(folder, GrantScope.folderOf(trackInFolder))
        assertEquals(null, GrantScope.folderOf(pickedFile))
        assertEquals(null, GrantScope.folderOf("content://media/external/audio/media/9"))
        assertEquals(null, GrantScope.folderOf("https://radio.example.org/stream"))
        assertEquals("a folder itself is not a row under it", null, GrantScope.folderOf(folder))
    }

    @Test
    fun `a folder is lost when rows play from it and no grant is held on it`() {
        val other = "content://com.android.externalstorage.documents/tree/primary%3APodcasts"
        val rows = listOf(trackInFolder, "$other/document/primary%3APodcasts%2Fa.mp3", pickedFile)

        assertEquals(setOf(folder), GrantScope.lostFolders(rows, held = listOf(other)))
        assertEquals(emptySet<String>(), GrantScope.lostFolders(rows, held = listOf(other, folder)))
        assertEquals(setOf(folder, other), GrantScope.lostFolders(rows, held = emptyList()))
    }

    @Test
    fun `a folder is called what a file manager shows`() {
        val tree = "content://com.android.externalstorage.documents/tree/"
        assertEquals("Music/Albums", GrantScope.folderName("${tree}primary%3AMusic%2FAlbums"))
        assertEquals("Music", GrantScope.folderName("${tree}1A2B-3C4D%3AMusic"))
        assertEquals("a whole card keeps its name", "1A2B-3C4D", GrantScope.folderName("${tree}1A2B-3C4D%3A"))
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
