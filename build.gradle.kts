// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.dsl.CommonExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.io.File
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.spotless)
    // applied here, not `apply false`: the root project is the one that
    // aggregates every documented module into a single site
    alias(libs.plugins.dokka)
}

spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    kotlin {
        target("**/src/**/*.kt")
        // a git worktree checked out under a dot-directory is another branch's
        // copy of this repo, not source of this build: formatting it here fails
        // a push over code the pusher never wrote
        targetExclude("**/build/**", ".*/**")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        // every module's build file, not only the root's and the app's, so
        // no build file goes unformatted
        target("**/*.gradle.kts")
        targetExclude("**/build/**", ".*/**")
        ktlint(ktlintVersion)
    }
}

/**
 * Everything a push has to pass, as one task: the formatting, detekt in every
 * module, the JVM tests of every module that has them - the contract suites
 * among them - and the goldens.
 *
 * One name, because the push hook, CI and ENGINEERING.md all have to mean the
 * same thing by "the gate", and a list written out in three places drifts.
 *
 * `:app`'s own tests are not listed because `verifyRoborazziDebug` *is* them -
 * it runs `testDebugUnitTest` with the golden comparison turned on.
 *
 * The other test tasks are named per module because the variant differs: a pure
 * Kotlin module has `test`, an Android library has one unit test variant -
 * debug, or release for `:backend:media3`, whose tests measure the audio path
 * as it ships. `:core:network` and `:visualizer:core` have no tests; a module
 * that gains some joins this list. No APK is assembled here; that is the wider
 * local gate in ENGINEERING.md.
 *
 * The source contract is three of them: `:core:packapi` (the wire types, whose
 * parcels break silently on a field added to one half, and whose round-trip
 * suite lives beside them), `:backend:pack` (the client, the end of the wire
 * the player compiles) and `:pack:common` (the source's end of it, which every
 * source is built on).
 */
val gate =
    tasks.register("gate") {
        group = "verification"
        description = "Format, detekt, the JVM tests and the goldens: what a push has to pass."
        dependsOn(
            "spotlessCheck",
            ":app:verifyRoborazziDebug",
            ":core:model:test",
            ":core:playback:test",
            ":core:player:test",
            ":core:dsp:test",
            ":core:plugin:test",
            ":core:packapi:testDebugUnitTest",
            ":backend:media3:testReleaseUnitTest",
            ":backend:pack:testDebugUnitTest",
            ":pack:common:testDebugUnitTest",
            ":visualizer:avs:testDebugUnitTest",
            ":visualizer:projectm:testDebugUnitTest",
        )
    }

// The API reference, rendered from the KDoc.
//
// One site over the modules somebody outside this repository reads to write a
// pack, aggregated here so a type can be followed across modules. The modules
// listed are the wire and the two ends of it; :app is absent because none of
// it is anybody else's contract.
//
// Not in `gate`: this is a full analysis of every listed module and the gate
// is what every push waits on.
dokka {
    dokkaPublications.html {
        outputDirectory = layout.buildDirectory.dir("api-docs")
    }
}

dependencies {
    dokka(project(":core:packapi"))
    dokka(project(":core:model"))
    dokka(project(":core:playback"))
    dokka(project(":core:player"))
    dokka(project(":backend:pack"))
}

tasks.register("apiDocs") {
    group = "documentation"
    description = "Aggregated HTML API reference for the pack contract and its two ends, from the KDoc, into build/api-docs."
    dependsOn("dokkaGeneratePublicationHtml")
}

/**
 * The "Extend Andamp" site: MkDocs over a named list of pages, with the Dokka
 * reference dropped in beside them, into one directory of plain files that any
 * static host will serve and a browser will open off a disk.
 *
 * The published pages are an allowlist. `docs/` also holds design notes,
 * measurements and the privacy policy, and a site built from the whole
 * directory would publish any new file under it by accident. So the input is
 * a staging directory this task fills from [pages], and a page reaches the
 * site because it is named here.
 *
 * Nothing under `docs/` moves to make that work: a spec test reads
 * `../../docs/dsp-plugin-spec.md` off the disk and the KDoc cites these paths
 * throughout, so the staging copy is a copy and the original stays where
 * everything already points at it.
 *
 * A page that has not been written yet is skipped with a line on the console
 * and does not fail the build. The one exception is the index, which is
 * generated as a stub: without an `index.html` at its root a host would answer
 * its own directory listing for the site's address.
 *
 * Self-contained, because a task class in a build script cannot reach the
 * script around it: everything it needs arrives as a property.
 */
abstract class DocsSite : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val files: FileSystemOperations

    /** `docs/`, read only - the allowlisted pages are copied out of it. */
    @get:Internal
    abstract val docs: DirectoryProperty

    /**
     * The pages that get published, as source name under `docs/` to the name
     * it is staged under. `extend.md` becomes `index.md` because the site's
     * front page and the repository's page about extending it are one text.
     */
    @get:Input
    abstract val pages: MapProperty<String, String>

    /** The example plug-ins, copied whole so the prose can link to them. */
    @get:Internal
    abstract val examples: DirectoryProperty

    /** Where the pages are assembled; MkDocs' `docs_dir`, and nothing else's. */
    @get:Internal
    abstract val staging: DirectoryProperty

    /** `mkdocs.yml`, whose `docs_dir` has to be [staging] and not `docs/`. */
    @get:Internal
    abstract val config: RegularFileProperty

    /** `tools/docs-venv/bin/mkdocs`; see [build] for why it is a venv. */
    @get:Internal
    abstract val mkdocs: RegularFileProperty

    /** What `apiDocs` rendered, copied into `api/` inside the output. */
    @get:Internal
    abstract val api: DirectoryProperty

    /**
     * Everything the built site is made of, declared so that editing a page
     * rebuilds it: the allowlisted pages by name - a name with no file yet is
     * simply absent from the snapshot - the examples, the MkDocs config and
     * the rendered reference.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        // A project-local virtualenv, deliberately, and not pip3 into the
        // machine: PEP 668 marks a Homebrew or system Python as
        // externally-managed, so `pip3 install mkdocs` refuses outright. The
        // venv also pins what renders the site to tools/docs-requirements.txt
        // rather than to whatever a machine happens to have. Missing, it stops
        // with the two commands that fix it.
        val renderer = mkdocs.get().asFile
        if (!renderer.canExecute()) {
            throw GradleException(
                "MkDocs is not installed in tools/docs-venv. Run:\n" +
                    "  python3 -m venv tools/docs-venv\n" +
                    "  tools/docs-venv/bin/pip install -r tools/docs-requirements.txt",
            )
        }

        val stage = staging.get().asFile
        files.delete { delete(stage) }
        stage.mkdirs()
        val from = docs.get().asFile
        pages.get().forEach { (source, staged) ->
            val page = File(from, source)
            if (page.isFile) {
                page.copyTo(File(stage, staged), overwrite = true)
            } else {
                logger.lifecycle("docsSite: docs/$source is not written yet - skipping \"$staged\".")
            }
        }
        val index = File(stage, "index.md")
        if (!index.isFile) {
            logger.lifecycle("docsSite: no index page staged - writing a stub so the site has a root.")
            index.writeText("# Extend Andamp\n\nThis page is not written yet.\n")
        }
        val examplesDir = examples.get().asFile
        if (examplesDir.isDirectory) {
            files.copy {
                from(examplesDir)
                into(File(stage, "examples"))
            }
        }

        // --clean, and the site directory named absolutely: MkDocs resolves a
        // relative one against the config file rather than against the working
        // directory, and this task is the only thing that decides where the
        // output goes.
        val out = outputDir.get().asFile
        exec.exec {
            commandLine(
                renderer.absolutePath,
                "build",
                "--clean",
                "--config-file",
                config.get().asFile.absolutePath,
                "--site-dir",
                out.absolutePath,
            )
        }

        // After MkDocs, never before: --clean empties the output directory, so
        // a reference copied in first would be deleted by the build that is
        // supposed to sit beside it.
        files.copy {
            from(api.get().asFile)
            into(File(out, "api"))
        }
    }
}

// The site. Not in `gate`: it renders the API reference from the KDoc and then
// shells out to Python, which a push should not wait on. Run it when the
// extension documentation is about to be published; `apiDocs` supplies the
// reference.
tasks.register<DocsSite>("docsSite") {
    group = "documentation"
    description = "The \"Extend Andamp\" developer site - allowlisted pages plus the API reference - into build/docs-site."
    dependsOn("apiDocs")

    val allowlist =
        linkedMapOf(
            "extend.md" to "index.md",
            "source-packs.md" to "source-packs.md",
            "dsp-plugins-intro.md" to "dsp-plugins-intro.md",
            "dsp-plugin-spec.md" to "dsp-plugin-spec.md",
        )
    val docsDir = layout.projectDirectory.dir("docs")

    docs = docsDir
    pages = allowlist
    examples = docsDir.dir("examples")
    staging = layout.buildDirectory.dir("docs-src")
    config = layout.projectDirectory.file("mkdocs.yml")
    mkdocs = layout.projectDirectory.file("tools/docs-venv/bin/mkdocs")
    api = layout.buildDirectory.dir("api-docs")
    outputDir = layout.buildDirectory.dir("docs-site")
    sources.from(allowlist.keys.map { docsDir.file(it) })
    sources.from(docsDir.dir("examples"))
    sources.from(layout.projectDirectory.file("mkdocs.yml"))
    sources.from(layout.buildDirectory.dir("api-docs"))
}

// What every Android module builds against, set once.
//
// The SDK, the build tools and the Java level are the same for every Android
// module, so they are set here and cannot differ between modules. A module
// says what is its own - a namespace, an application id, what it depends on.
subprojects {
    val module = this
    listOf("com.android.application", "com.android.library").forEach { android ->
        plugins.withId(android) {
            module.extensions.configure<CommonExtension>("android") {
                compileSdk = 36
                buildToolsVersion = "36.1.0"
                defaultConfig.minSdk = 26
                compileOptions.sourceCompatibility = JavaVersion.VERSION_17
                compileOptions.targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}

/**
 * The source SDK: what somebody writing a source compiles against, published
 * so that they need a dependency and not this checkout.
 *
 * Five modules under one group and one version, because a source takes them
 * together: the wire (`source-api`), the types it is stated in (`model`), and
 * the pack side that is the same in every source (`source-common`) with the two
 * modules its own API names (`playback`, `network`). They are Apache-2.0, where
 * the player is GPL: a source is its author's, and may carry any license.
 *
 * The names are said once, here. A module joins by applying the publish plugin
 * and being in this map; what it is called and how it describes itself is not
 * in its own build file, so the five cannot drift apart.
 *
 * `publishToMavenLocal` needs nothing. Reaching Maven Central and signing are
 * asked for with `-PsdkRelease`, on the machine that holds the key, so a clone
 * can publish locally without one.
 */
val sdkGroup = "nl.mattix.andamp"
val sdkKotlin = KotlinVersion.KOTLIN_2_2
val sdkStdlib = "2.2.20"
val sdkVersion: String = libs.versions.andampSdk.get()
val sdkModules =
    mapOf(
        ":core:model" to ("model" to "The types Andamp's source contract is stated in: tracks, transport, backend state."),
        ":core:playback" to ("playback" to "Andamp's playback contract and its contract tests, which ship as test fixtures."),
        ":core:network" to ("network" to "Whether the phone has a network, as Andamp and its sources ask it."),
        ":core:packapi" to
            ("source-api" to "The contract between Andamp and a music source: the AIDL and the parcels that cross."),
        ":pack:common" to
            ("source-common" to "The source side of Andamp's contract that is the same in every source: service, audio, paging."),
    )

subprojects {
    val module = this
    val (artifact, summary) = sdkModules[module.path] ?: return@subprojects
    // the group and version a module's capabilities are named from. Test
    // fixtures are asked for as a capability - `playback-test-fixtures` in the
    // published group - and a module that kept Gradle's default group would
    // offer them under another name than the one they are published by.
    module.group = sdkGroup
    module.version = sdkVersion
    // Written in the Kotlin a source may still be compiled with, not in the one
    // this build has. A compiler reads metadata one version ahead of its own
    // and no further, so a library written at the newest language level can
    // only be used by somebody who has already upgraded - and a source's
    // author upgrades when they choose to, not when the player does.
    module.tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            languageVersion.set(sdkKotlin)
            apiVersion.set(sdkKotlin)
        }
    }
    // and the standard library it asks a source to bring, for the same reason:
    // this build's own would arrive in a source that cannot read it. The floor
    // is the one kotlinx.coroutines already sets.
    listOf("org.jetbrains.kotlin.jvm", "com.android.library").forEach { kind ->
        plugins.withId(kind) {
            (module.extensions.findByName("kotlin") as? KotlinProjectExtension)?.coreLibrariesVersion = sdkStdlib
        }
    }
    plugins.withId("com.vanniktech.maven.publish") {
        module.extensions.configure<MavenPublishBaseExtension> {
            coordinates(sdkGroup, artifact, sdkVersion)
            if (providers.gradleProperty("sdkRelease").isPresent) {
                publishToMavenCentral()
                signAllPublications()
            }
            pom {
                name.set("Andamp $artifact")
                description.set(summary)
                url.set("https://github.com/mattijsf/andamp")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("mattijsf")
                        name.set("Mattijs Fuijkschot")
                        email.set("mattijs@mattix.nl")
                    }
                }
                scm {
                    url.set("https://github.com/mattijsf/andamp")
                    connection.set("scm:git:https://github.com/mattijsf/andamp.git")
                    developerConnection.set("scm:git:ssh://git@github.com/mattijsf/andamp.git")
                }
            }
        }
    }
}

// detekt, set up once for every module that applies it, and part of the gate
// from the moment it does.
//
// Every source set, named as one directory rather than as a list: detekt's
// default set is main and test only, so code in any other source set - device
// tests, the contract suites in test fixtures - would go unanalysed and nothing
// would say so. Here, the next module gets it by applying the plugin.
subprojects {
    val module = this
    plugins.withId("io.gitlab.arturbosch.detekt") {
        module.extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.file("detekt.yml"))
            source.setFrom(module.files("src"))
        }
        gate.configure { dependsOn("${module.path}:detekt") }
    }
    // Dokka names a module after its directory by default, which puts "pack"
    // and "packapi" side by side in the site's index with nothing to tell the
    // client from the wire. Name them the way ENGINEERING.md and every task
    // name do, by path.
    plugins.withId("org.jetbrains.dokka") {
        module.extensions.configure<DokkaExtension> {
            moduleName = module.path.removePrefix(":")
        }
    }
}
