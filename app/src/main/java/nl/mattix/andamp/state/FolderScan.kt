// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track

/**
 * ADD > DIR: which files in a picked folder become playlist entries, and in what order.
 * It is free of Android so the rules can be tested directly; [SafDocumentTree] supplies the
 * real documents.
 */
object FolderScan {
    data class Doc(
        val id: String,
        val name: String,
        val mimeType: String,
    ) {
        val isDirectory get() = mimeType == MIME_DIRECTORY
    }

    /** What a folder holds, as the picker reports it. */
    fun interface Tree {
        fun children(documentId: String): List<Doc>
    }

    const val MIME_DIRECTORY = "vnd.android.document/directory"

    /**
     * The entries a scan puts on the playlist before any tag has been read: one per file,
     * named by its filename.
     */
    fun arrivals(
        files: List<Doc>,
        stamp: Long,
        uriOf: (Doc) -> String,
    ): List<Track> =
        files.mapIndexed { i, doc ->
            Track(
                id = "picked-$stamp-$i",
                artist = "",
                title = "",
                durationMs = 0,
                uri = uriOf(doc),
                defaultName = doc.name,
            )
        }

    /**
     * Depth-first from [rootId]: each folder's own files in name order, then its subfolders,
     * as a file manager shows them. Every audio file in the tree is returned, however many.
     */
    fun audioFiles(
        tree: Tree,
        rootId: String,
    ): List<Doc> {
        val files = mutableListOf<Doc>()
        val pending = ArrayDeque(listOf(rootId))
        val seen = mutableSetOf(rootId)
        while (pending.isNotEmpty()) {
            val (dirs, entries) =
                tree
                    .children(
                        pending.removeFirst(),
                    ).sortedBy { it.name.lowercase() }
                    .partition { it.isDirectory }
            files += entries.filter(::isAudio)
            // a shortcut can point back up the tree; visiting it twice would loop
            dirs.filter { seen.add(it.id) }.reversed().forEach { pending.addFirst(it.id) }
        }
        return files
    }

    /**
     * Providers are inconsistent about MIME types (some report `application/octet-stream`
     * for an mp3), so the extension counts too.
     */
    fun isAudio(doc: Doc): Boolean =
        doc.mimeType.startsWith("audio/") ||
            doc.mimeType == "application/ogg" ||
            AUDIO_EXTENSIONS.any { doc.name.endsWith(it, ignoreCase = true) }

    // .mp4 is absent: it is usually a video container, and an audio-only mp4 reports an
    // audio/ MIME type
    private val AUDIO_EXTENSIONS =
        listOf(".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".oga", ".opus", ".flac", ".wav", ".wma", ".mka", ".mid")
}
