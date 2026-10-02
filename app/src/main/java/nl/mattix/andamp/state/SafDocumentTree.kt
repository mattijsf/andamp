// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * [FolderScan.Tree] backed by the document provider behind a picked folder. It queries the
 * provider directly, one cursor per folder, without androidx.documentfile.
 */
class SafDocumentTree(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : FolderScan.Tree {
    val rootId: String get() = DocumentsContract.getTreeDocumentId(treeUri)

    override fun children(documentId: String): List<FolderScan.Doc> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        return runCatching {
            resolver
                .query(childrenUri, PROJECTION, null, null, null)
                ?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                FolderScan.Doc(
                                    id = cursor.getString(0),
                                    name = cursor.getString(1) ?: "",
                                    mimeType = cursor.getString(2) ?: "",
                                ),
                            )
                        }
                    }
                }.orEmpty()
        }.onFailure { Log.w(TAG, "Could not list $documentId", it) }.getOrDefault(emptyList())
    }

    /** The playable uri for a document this tree reported. */
    fun uriFor(doc: FolderScan.Doc): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.id)

    private companion object {
        const val TAG = "SafDocumentTree"
        val PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
    }
}
