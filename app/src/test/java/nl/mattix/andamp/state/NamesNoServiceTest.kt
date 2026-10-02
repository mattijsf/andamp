// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.backend.pack.PackFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Properties
import java.util.zip.ZipInputStream

/**
 * The app carries no source's code and does not name the service a source
 * plays. This is condition 4 of the five conditions in ENGINEERING.md: no
 * string, constant, package name or asset in the Play APK names the service.
 *
 * A source is an APK of its own, so its classes can reach the app only through
 * a declared dependency. The app does carry `:backend:pack`, the client that
 * binds a source. That client names no service, because a source's scheme and
 * label are read across a binder, and the scan reads it with everything else.
 *
 * The scan covers what the unit tests are handed: every file this project puts
 * on the classpath, and the manifest, resources and assets Robolectric is
 * pointed at. The packaged APK and bundle, with the libraries' code and the
 * native libraries, are checked by the `NamesNothing` tasks in
 * `app/build.gradle.kts`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NamesNoServiceTest {
    private val app = File(System.getProperty("user.dir")).absoluteFile
    private val repo = app.parentFile

    @Test
    fun `no source's code is on the app's classpath`() {
        val entries =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .map(::File)
                .filter { it.exists() }
        val classes = entries.flatMap(::classesIn)
        // a scan that read nothing would pass, so it must find the client
        // package, which the app does carry
        assertTrue("the scan finds the client package", classes.any { it.startsWith(CLIENT_PACKAGE) })
        val carried = classes.filter { it.startsWith(SOURCE_PACKAGE) }
        assertTrue("the app's classpath holds no source's code: ${carried.take(5)}", carried.isEmpty())
    }

    /** The class files one classpath entry holds, by their path inside it. */
    private fun classesIn(entry: File): List<String> =
        when {
            entry.isDirectory -> {
                entry
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .map { it.relativeTo(entry).invariantSeparatorsPath }
                    .toList()
            }

            entry.extension == "jar" -> {
                ZipInputStream(entry.inputStream().buffered()).use { zip ->
                    generateSequence { zip.nextEntry }.map { it.name }.filter { it.endsWith(".class") }.toList()
                }
            }

            else -> {
                emptyList()
            }
        }

    /**
     * The app declares no source: a source is an app the listener installed,
     * found at runtime. With no source app installed, the phone is the only
     * source.
     */
    @Test
    fun `with nothing installed beside it, there is no source beyond the phone`() {
        assertTrue("a test JVM has no source app on it", PackFinder.found(RuntimeEnvironment.getApplication()).isEmpty())
        assertTrue(PackSources.found.isEmpty())
        assertEquals(listOf(MusicSource.LOCAL), MusicSource.present())
    }

    /**
     * Every file this project contributes to the classpath, read as bytes: the
     * classes, with their string constants and class and member names, and
     * everything packaged beside them.
     *
     * Paths are compared relative to the checkout, so the directory the
     * checkout is in can match neither [TESTS] nor the service's name.
     *
     * A scan that read nothing would pass, so it must find [OURS].
     */
    @Test
    fun `nothing the app ships names the service it leaves out`() {
        val shipped =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .map { File(it).absoluteFile }
                .filter { it.startsWith(repo) && it.exists() }
                .filterNot { entry -> TESTS.any { inRepo(entry).contains(it, ignoreCase = true) } }
        val read = shipped.flatMap(::entriesIn)
        assertTrue("the scan finds the app's own classes in $shipped", read.any { it.name.endsWith(OURS) })

        assertEquals("no shipped file names the service", emptyList<String>(), read.filter { it.naming }.map { it.name })
    }

    /**
     * The merged manifest, the linked resources and the merged assets; a zip
     * among them, such as a skin, is opened and read too. AGP writes their
     * locations for Robolectric in [TEST_CONFIG] on the test classpath.
     */
    @Test
    fun `its manifest, resources and assets do not name the service`() {
        val config = testConfig()
        val built =
            OUTPUTS.map { key ->
                val path = config.getProperty(key) ?: error("$TEST_CONFIG names no $key")
                File(path).let { if (it.isAbsolute) it else File(app, path) }
            }
        built.forEach { assertTrue("$it exists", it.exists()) }

        assertEquals(
            "no shipped file names the service",
            emptyList<String>(),
            built.flatMap(::entriesIn).filter { it.naming }.map { it.name },
        )
    }

    /** Where [file] is inside the checkout, so that where the checkout is cannot match anything. */
    private fun inRepo(file: File) = file.relativeTo(repo).invariantSeparatorsPath

    /** The properties AGP writes for Robolectric, from the classpath directory that holds them. */
    private fun testConfig(): Properties {
        val file =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .map { File(it, TEST_CONFIG) }
                .firstOrNull { it.isFile }
                ?: error("no $TEST_CONFIG on the classpath: the unit tests do not include the Android resources")
        return Properties().apply { file.inputStream().use { load(it) } }
    }

    /** Every file in [entry] - a directory, a zip or a single file - and in every zip among them. */
    private fun entriesIn(entry: File): List<Read> {
        val where = inRepo(entry)
        return if (entry.isDirectory) {
            entry
                .walkTopDown()
                .filter { it.isFile }
                .flatMap { read("$where/${it.relativeTo(entry).invariantSeparatorsPath}", it.readBytes()) }
                .toList()
        } else {
            read(where, entry.readBytes())
        }
    }

    /** [bytes] under [name], and when they are a zip - a jar, a skin, the linked resources - what is inside it too. */
    private fun read(
        name: String,
        bytes: ByteArray,
    ): List<Read> {
        val inside = mutableListOf<Read>()
        if (bytes.isZip()) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }
                    .filterNot { it.isDirectory }
                    .forEach { inside += read("$name!${it.name}", zip.readBytes()) }
            }
        }
        return listOf(Read(name, SERVICE in name.lowercase() || bytes.names())) + inside
    }

    /** A zip starts with the local header of its first entry. */
    private fun ByteArray.isZip() = size >= ZIP.size && ZIP.indices.all { this[it] == ZIP[it] }

    /**
     * Whether these bytes carry the word, in any case: one byte a letter, as
     * class files, UTF-8 and native code keep text, or two, as UTF-16 does -
     * which binary XML and resource tables may use.
     */
    private fun ByteArray.names(): Boolean {
        val text = toString(Charsets.ISO_8859_1).lowercase()
        return SERVICE in text || WIDE in text
    }

    /** One file the scan read: where it was, and whether it names the service. */
    private data class Read(
        val name: String,
        val naming: Boolean,
    )

    private companion object {
        /** Class files under this path count as a source's own code. */
        const val SOURCE_PACKAGE = "nl/mattix/andamp/pack/"

        /** The client that binds a source, which the app does carry. */
        const val CLIENT_PACKAGE = "nl/mattix/andamp/backend/pack/"

        /** The word the scans look for. This test class does not ship. */
        const val SERVICE = "spotify"

        /** The same word two bytes a letter, the way UTF-16 little endian keeps it. */
        val WIDE = SERVICE.map { "$it\u0000" }.joinToString("")

        /** One of the app's own compiled classes. Finding it shows the scan read what this project built. */
        const val OURS = "nl/mattix/andamp/state/PackSources.class"

        /** Classpath entries with one of these in their path are tests and fixtures, and are not scanned. */
        val TESTS = listOf("UnitTest", "testFixtures", "test-fixtures", "robolectric")

        /** Where AGP says the merged manifest, the linked resources and the merged assets are. */
        const val TEST_CONFIG = "com/android/tools/test_config.properties"

        val OUTPUTS = listOf("android_merged_manifest", "android_resource_apk", "android_merged_assets")

        val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
