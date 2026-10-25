// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

import java.io.File
import java.util.zip.ZipFile

/** The files of one skin, looked up by path without regard to case or slash direction. */
interface SkinFiles {
    fun read(path: String): ByteArray?

    fun exists(path: String): Boolean = read(path) != null

    fun names(): List<String>

    companion object {
        fun normalize(path: String): String =
            path
                .replace(
                    '\\',
                    '/',
                ).trimStart('/')
                .lowercase()
                .split('/')
                .filter { it.isNotEmpty() && it != "." }
                .joinToString("/")
    }
}

/** A `.wal`, which is a zip. The whole archive is read into memory once; skins are a few megabytes. */
class ZipSkinFiles(
    file: File,
) : SkinFiles {
    private val entries: Map<String, ByteArray> =
        ZipFile(file).use { zip ->
            // sizes the archive claims can lie (a zip bomb): count what actually comes out
            var total = 0L
            val all = LinkedHashMap<String, ByteArray>()
            for (e in zip
                .entries()
                .asSequence()
                .filter { !it.isDirectory }
                .take(MAX_ENTRIES)) {
                val bytes = zip.getInputStream(e).use { readAtMost(it, MAX_ENTRY) } ?: continue
                total += bytes.size
                if (total > MAX_TOTAL) break
                all[SkinFiles.normalize(e.name)] = bytes
            }
            // some archives hold the skin in one folder: strip it when skin.xml sits there
            val prefix = WalArchive.skinFolder(all.keys)
            if (prefix.isNullOrEmpty()) {
                all
            } else {
                all.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
            }
        }

    override fun read(path: String): ByteArray? = entries[SkinFiles.normalize(path)]

    override fun names(): List<String> = entries.keys.toList()

    /**
     * A `.wal` read entry by entry as paths ask for it, for a skin another skin reads a few files
     * from (Bento reads Big Bento's scripts and XML): the list of entries is read as it opens, and
     * only the bytes of the entries read are kept, within the same limits as a whole archive. The
     * archive stays open while this is in use, so a file deleted meanwhile reads on as it was.
     * Two threads may read at once.
     */
    class OnDemand(
        file: File,
    ) : SkinFiles {
        private val zip = ZipFile(file)

        /** Entry names by path, as [ZipSkinFiles] would name them. */
        private val index: Map<String, String>

        /** The entries read so far, null for one that did not read; guarded by itself. */
        private val kept = HashMap<String, ByteArray?>()

        /** The bytes [kept] holds. */
        var heldBytes = 0L
            private set

        init {
            val all = LinkedHashMap<String, String>()
            for (e in zip
                .entries()
                .asSequence()
                .filter { !it.isDirectory }
                .take(MAX_ENTRIES)) {
                all[SkinFiles.normalize(e.name)] = e.name
            }
            val prefix = WalArchive.skinFolder(all.keys)
            index =
                if (prefix.isNullOrEmpty()) {
                    all
                } else {
                    all.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
                }
        }

        override fun read(path: String): ByteArray? {
            val n = SkinFiles.normalize(path)
            synchronized(kept) { if (n in kept) return kept[n] }
            val name = index[n] ?: return null
            // read outside the lock, so one thread does not wait on another's entry
            val bytes =
                runCatching {
                    zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { readAtMost(it, MAX_ENTRY) } }
                }.getOrNull()
            return synchronized(kept) {
                if (n !in kept) {
                    val held = bytes?.takeIf { heldBytes + it.size <= MAX_TOTAL }
                    kept[n] = held
                    heldBytes += held?.size ?: 0
                }
                kept[n]
            }
        }

        override fun names(): List<String> = index.keys.toList()
    }

    private companion object {
        const val MAX_ENTRY = 32 * 1024 * 1024
        const val MAX_TOTAL = 128L * 1024 * 1024
        const val MAX_ENTRIES = 8_192

        /** The stream's bytes, or null when there are more than [limit]. */
        fun readAtMost(
            input: java.io.InputStream,
            limit: Int,
        ): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > limit) return null
                out.write(buf, 0, n)
            }
        }
    }
}

/**
 * A skin's files with Winamp's default element set behind them: paths under [BASE_PREFIX] read
 * from [base] (the `Plugins\freeform\xml\wasabi` folder, zipped), everything else from [skin].
 * Winamp draws lists, scrollbars and frames a skin does not define from that set.
 */
class LayeredSkinFiles(
    private val skin: SkinFiles,
    private val base: SkinFiles,
) : SkinFiles {
    override fun read(path: String): ByteArray? {
        val n = SkinFiles.normalize(path)
        return if (n.startsWith(BASE_PREFIX)) base.read(n.removePrefix(BASE_PREFIX)) else skin.read(path)
    }

    override fun names(): List<String> = skin.names() + base.names().map { BASE_PREFIX + it }

    companion object {
        const val BASE_PREFIX = "wasabi-base/"
    }
}

/**
 * A skin beside the other skins in a Skins folder: a path `../<name>/...` or `@SKINSPATH@/<name>/...`
 * reads from that sibling (Bento takes its scripts and most of its XML from "Big Bento").
 * Siblings are named by their folder name lowercased without spaces ("bigbento"); [siblings] gives
 * the one by that name, or null, so a host can open a sibling only when a path names it.
 */
class SkinsFolderFiles(
    private val skin: SkinFiles,
    private val siblings: (String) -> SkinFiles?,
) : SkinFiles {
    /** Siblings opened beforehand, by folder name. */
    constructor(skin: SkinFiles, siblings: Map<String, SkinFiles>) : this(skin, siblings::get)

    private fun sibling(path: String): Pair<SkinFiles, String>? {
        val n = SkinFiles.normalize(path).removePrefix("@skinspath@/")
        val parts = n.split('/')
        val start =
            when {
                parts.firstOrNull() == ".." -> 1
                n != SkinFiles.normalize(path) -> 0
                else -> return null
            }
        val name = parts.getOrNull(start)?.replace(" ", "") ?: return null
        return siblings(name)?.let { files -> files to parts.drop(start + 1).joinToString("/") }
    }

    override fun read(path: String): ByteArray? = sibling(path)?.let { (f, p) -> f.read(p) } ?: skin.read(path)

    override fun names(): List<String> = skin.names()
}

/**
 * Andamp's default element set (module resources under `defaultset/`), for use as the base of
 * [LayeredSkinFiles] in place of Winamp's: the frames, title bar and widgets skins build on, in
 * plain pictures of our own. Its `$solid` pictures are answered by [GeneratedImages].
 */
object AndampDefaultSet : SkinFiles {
    private const val ROOT = "/nl/mattix/andamp/modernskin/defaultset/"
    private val FILES =
        listOf(
            "wasabi.xml",
            "elements.xml",
            "aliases.xml",
            "groups.xml",
            "fonts/DejaVuSansCondensed.ttf",
            "fonts/DejaVuSansCondensed-Bold.ttf",
        )

    // paths reach a SkinFiles normalized to lower case; resources keep their own spelling
    private val byLowerName = FILES.associateBy { it.lowercase() }

    override fun read(path: String): ByteArray? {
        val n = SkinFiles.normalize(path)
        GeneratedImages.read(n)?.let { return it }
        val name = byLowerName[n] ?: return null
        return AndampDefaultSet::class.java.getResourceAsStream(ROOT + name)?.use { it.readBytes() }
    }

    override fun names(): List<String> = FILES
}
