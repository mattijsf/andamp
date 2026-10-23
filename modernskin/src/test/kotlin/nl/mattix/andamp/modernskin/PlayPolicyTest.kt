// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.SystemObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * What lets a skin's scripts ship under Play's rule for interpreted code: the engine reaches no
 * network, process, native code, reflection or files beyond the skin's archive, and the script calls
 * that would open a link, download, write the clipboard or open files do nothing.
 */
class PlayPolicyTest {
    @Test
    fun `the engine's compiled code reaches no network, process, native code, reflection or files outside the skin archive`() {
        // the bytecode, so neither line breaks nor names written out in full hide a call
        val root = File("build/classes/kotlin/main")
        val classes = root.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue("classes found in $root", classes.size > 100)
        val found =
            classes.flatMap { f ->
                val name = f.relativeTo(root).path.removeSuffix(".class")
                val code = String(f.readBytes(), Charsets.ISO_8859_1)
                FORBIDDEN
                    .filter { (kind, pattern) -> pattern.containsMatchIn(code) && !(kind == "files" && ARCHIVE.matches(name)) }
                    .map { (kind, _) -> "$name: $kind" }
            }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `the scan finds what it looks for`() {
        val samples =
            mapOf(
                "network" to "Ljava/net/URL;",
                "process" to "java/lang/Runtime\u0001\u0000\u0004exec",
                "native code" to "loadLibrary",
                "reflection" to "java/lang/reflect/Method",
                "files" to "kotlin/io/FilesKt__FileReadWriteKt",
            )
        for ((kind, pattern) in FORBIDDEN) assertTrue(kind, pattern.containsMatchIn(samples.getValue(kind)))
        assertTrue(
            FORBIDDEN.none { (_, pattern) ->
                pattern.containsMatchIn("java/lang/RuntimeException java/io/ByteArrayInputStream")
            },
        )
    }

    @Test
    fun `script calls that would open a link, download, write the clipboard or open files do nothing`() {
        // every host call is seen: a call that does nothing here does not pass the work to the app
        val asked = ArrayList<String>()
        val host =
            Proxy.newProxyInstance(SkinHost::class.java.classLoader, arrayOf(SkinHost::class.java)) { _, method, _ ->
                asked += method.name
                when (method.returnType) {
                    Int::class.javaPrimitiveType -> 0
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            } as SkinHost
        val files =
            object : SkinFiles {
                override fun read(path: String): ByteArray? = null

                override fun names(): List<String> = emptyList()
            }
        val player = FakePlayer()
        val tracks = player.tracks.toList()
        val system = SystemObject(SkinRuntime(files, host, player), null, "", "test")
        val link = Value.Str("https://example.com")
        asked.clear()

        for (call in NO_OPS) {
            val back = system.makiCall(call, listOf(link, link, link, link))
            assertTrue("$call gave $back", back == Value.Null || back?.asString == "")
        }

        assertEquals(tracks, player.tracks)
        assertEquals(emptyList<String>(), asked)
    }

    private companion object {
        val NO_OPS =
            listOf(
                "navigateurl",
                "navigateurlbrowser",
                "popmainbrowser",
                "getmainbrowser",
                "downloadurl",
                "downloadmedia",
                "setdownloadpath",
                "getdownloadpath",
                "setclipboardtext",
                "playfile",
                "enqueuefile",
                "selectfile",
                "selectfolder",
            )

        /** The classes that read the skin's archive from a file. */
        val ARCHIVE = Regex("""nl/mattix/andamp/modernskin/skin/ZipSkinFiles(\$.*)?""")

        /** Each kind of access, by the names its bytecode refers to; a name ends where a longer one would go on. */
        val FORBIDDEN =
            listOf(
                "network" to Regex("""java/net/|javax/net/|okhttp3/"""),
                "process" to Regex("""java/lang/(ProcessBuilder|Process|Runtime)(?![\w$])"""),
                "native code" to Regex("""(?<![\w$])loadLibrary(?![\w$])"""),
                "reflection" to
                    Regex(
                        """java/lang/reflect/|kotlin/reflect/(full|jvm)/""" +
                            """|(?<![\w$])(forName|getDeclaredMethods?|getDeclaredFields?)(?![\w$])""",
                    ),
                "files" to
                    Regex(
                        """java/io/(File\w*|RandomAccessFile)(?![\w$])|java/nio/file/|kotlin/io/path/|kotlin/io/FilesKt""" +
                            """|java/util/zip/ZipFile(?![\w$])""",
                    ),
            )
    }
}
