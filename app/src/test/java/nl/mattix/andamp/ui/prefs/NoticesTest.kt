// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notices, checked against the build.
 *
 * The test reads every runtime dependency the modules declare, resolves each
 * alias through the version catalog, and asserts that a notice covers its
 * group. Test-only and debug-only configurations are left out: they do not
 * ship.
 */
class NoticesTest {
    @Test
    fun `every shipped dependency is named in the notices`() {
        val catalogue = versionCatalogue()
        val aliases = runtimeAliases()
        // a scan that finds nothing would pass this test while checking nothing
        assertTrue("the build scan finds at least $EXPECTED_AT_LEAST runtime dependencies", aliases.size >= EXPECTED_AT_LEAST)
        val missing =
            aliases
                .map { alias -> alias to (catalogue[alias] ?: error("no entry for $alias in libs.versions.toml")) }
                .filterNot { (_, group) -> group == OWN_GROUP || Notices.covers(group) }
        assertTrue("every dependency has a notice; missing: $missing", missing.isEmpty())
    }

    @Test
    fun `every notice says what it is, under what, and where`() {
        Notices.ALL.forEach { notice ->
            assertTrue("${notice.name} names a license", notice.licence.isNotBlank())
            assertTrue("${notice.name} says what it is", notice.what.isNotBlank())
            assertTrue("${notice.name} has an https source address", notice.url.startsWith("https://"))
        }
    }

    @Test
    fun `the LGPL entry offers the pinned projectM revision`() {
        val projectM = Notices.ALL.single { it.name == "projectM" }
        val offer = projectM.obligation ?: error("projectM carries no LGPL offer")
        assertTrue("the offer names the pinned revision", offer.contains(Notices.PROJECTM_PIN))
        assertTrue(
            "NOTICE.md pins the projectM revision the licenses screen offers",
            repoRoot().resolve("NOTICE.md").readText().contains("v${Notices.PROJECTM_PIN}"),
        )
    }

    /** Aliases used by `implementation(...)` or `api(...)`, which is what ships. */
    private fun runtimeAliases(): List<String> =
        moduleBuildFiles()
            .flatMap { it.readLines() }
            .mapNotNull { RUNTIME_DEPENDENCY.find(it.trim())?.groupValues?.get(2) }
            .map { it.removePrefix("libs.") }
            .distinct()

    /**
     * The build file of every module `settings.gradle.kts` includes: the
     * modules Gradle builds. A build file anywhere else under the root, such
     * as in another checkout of this repo nested inside this one, belongs to
     * another build and is not read.
     */
    private fun moduleBuildFiles(): List<File> {
        val root = repoRoot()
        return root
            .resolve("settings.gradle.kts")
            .readLines()
            .filter { it.trim().startsWith("include(") }
            .flatMap { line -> MODULE_PATH.findAll(line).map { it.groupValues[1] } }
            .map { path ->
                val buildFile = root.resolve(path.replace(':', '/')).resolve("build.gradle.kts")
                // a module left out without a word would shrink the scan
                check(buildFile.isFile) { "settings.gradle.kts includes :$path, which has no $buildFile" }
                buildFile
            }
    }

    /** Catalog alias, in the dotted form Gradle's accessors use, to Maven group. */
    private fun versionCatalogue(): Map<String, String> =
        repoRoot()
            .resolve("gradle/libs.versions.toml")
            .readLines()
            .mapNotNull { line ->
                val entry = CATALOGUE_ENTRY.find(line.trim()) ?: return@mapNotNull null
                entry.groupValues[1].replace('-', '.') to entry.groupValues[2]
            }.toMap()

    /** The directory holding `settings.gradle.kts`, wherever the test was run from. */
    private fun repoRoot(): File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("no settings.gradle.kts above ${File(".").absolutePath}")

    private companion object {
        /** Low enough not to be a second copy of the dependency list, high enough to catch a broken scan. */
        const val EXPECTED_AT_LEAST = 8

        /** This project's own group, which needs no notice. */
        const val OWN_GROUP = "nl.mattix.andamp"

        /** A module path in an `include(...)` line, without its leading colon. */
        val MODULE_PATH = Regex("""":([A-Za-z0-9:_-]+)"""")

        val RUNTIME_DEPENDENCY = Regex("""^(implementation|api)\((libs\.[A-Za-z0-9.]+)\)""")
        val CATALOGUE_ENTRY = Regex("""^([A-Za-z0-9-]+)\s*=\s*\{[^}]*group\s*=\s*"([^"]+)"""")
    }
}
