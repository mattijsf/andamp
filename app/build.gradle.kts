// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.detekt)
    alias(libs.plugins.play.publisher)
}

/**
 * Where the upload key lives, never in the repository.
 *
 * A `keystore.properties` beside the project names the keystore file and the alias inside it.
 * Without one the release build is unsigned, so a clone can build everything and only the machine
 * that publishes needs the key.
 */
val signing =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

/**
 * The two passwords, which are not in that file.
 *
 * They come from the environment, so the secret reaches the build for the length of one command:
 *
 * ```
 * RT_STORE_PASSWORD=… RT_KEY_PASSWORD=… ./gradlew :app:bundleRelease
 * ```
 *
 * A password written into `keystore.properties` still works; the environment wins when both are
 * there.
 */
fun secret(
    variable: String,
    property: String,
): String? = System.getenv(variable)?.takeIf { it.isNotBlank() } ?: signing.getProperty(property)

val storePath: String? = signing.getProperty("storeFile")

/**
 * A semver name as one climbing integer: 1.2.3 becomes 10203.
 *
 * Two digits each for minor and patch, which caps a release at 99 of either. Play only requires
 * that the number rises.
 */
fun versionCodeOf(name: String): Int {
    val parts = name.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return major * 10_000 + minor * 100 + patch
}

/**
 * The skins Andamp ships, copied from where they are generated.
 *
 * `skin/` is the source: one Python build that writes three themes. They are copied at build time,
 * so no second copy is kept under assets. What goes: the three .wsz, and the two templates from
 * which Dark and Light are rebuilt on the phone from its own palette (SkinTemplate). The other
 * files in `skin/dist` are not packaged.
 */
abstract class BundleSkins : DefaultTask() {
    @get:InputDirectory
    abstract val from: DirectoryProperty

    @get:OutputDirectory
    abstract val into: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(this@BundleSkins.from) {
                include("AndAmp *.wsz", "andamp-dark-template.zip", "andamp-light-template.zip")
            }
            // the asset path is skins/
            into(this@BundleSkins.into.dir("skins"))
        }
    }
}

val bundledSkins =
    tasks.register<BundleSkins>("bundledSkins") {
        description = "Copies the generated AndAmp skins into the packaged assets."
        from.set(rootProject.layout.projectDirectory.dir("skin/dist"))
    }

/**
 * The license texts of projectM and Liberation Sans, copied into the packaged
 * assets.
 *
 * LGPL-2.1 and the OFL ask that their text accompany what they cover, and the
 * app ships projectM as a native library and the font as an asset. The asset
 * paths are under `licenses/`, and Notices names them.
 */
abstract class BundleLicenses : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val projectM: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val liberationSans: RegularFileProperty

    @get:OutputDirectory
    abstract val into: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(projectM) { rename { "projectM-LGPL-2.1.txt" } }
            from(liberationSans) { rename { "LiberationSans-OFL-1.1.txt" } }
            into(this@BundleLicenses.into.dir("licenses"))
        }
    }
}

val bundledLicenses =
    tasks.register<BundleLicenses>("bundledLicenses") {
        description = "Copies the license texts of projectM and Liberation Sans into the packaged assets."
        liberationSans.set(rootProject.layout.projectDirectory.file("docs/LiberationSans-LICENSE.txt"))
        projectM.set(
            rootProject.layout.projectDirectory.file("visualizer/projectm/third_party/projectm/LICENSE.txt"),
        )
    }

/**
 * The packaged app read back the way Play would receive it, failing on a word it must not contain.
 *
 * NamesNoServiceTest reads what this project compiled; this reads what was packaged: the libraries'
 * code in every dex, the manifest, the resource table, each asset (a skin is a zip, so it is opened
 * too) and every native library. The word is matched in any case, as one byte a letter and as two
 * (UTF-16, as binary XML and resource tables may store it).
 */
abstract class NamesNothing : DefaultTask() {
    /** The variant's APK directory, or its bundle. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packages: ConfigurableFileCollection

    @get:Input
    abstract val word: Property<String>

    /** Written when nothing was found, so a package that has not changed is not read again. */
    @get:OutputFile
    abstract val verdict: RegularFileProperty

    @TaskAction
    fun scan() {
        val needle = word.get().lowercase()
        val read = packages.asFileTree.files.filter { it.extension == "apk" || it.extension == "aab" }
        // an empty directory would pass by reading nothing
        if (read.isEmpty()) throw GradleException("no APK or bundle to read in ${packages.files}")
        val found =
            read.flatMap { pkg ->
                ZipFile(pkg).use { zip ->
                    val entries =
                        zip
                            .entries()
                            .asSequence()
                            .filterNot { it.isDirectory }
                            .map { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }
                    naming(pkg.name, entries, needle)
                }
            }
        if (found.isNotEmpty()) {
            throw GradleException(
                "the app that goes to Play names \"$needle\" in:\n\n" + found.joinToString("\n") { "  $it" } + "\n",
            )
        }
        val said = verdict.get().asFile
        said.parentFile?.mkdirs()
        said.writeText(read.joinToString("\n", postfix = "\n") { "${it.name} names nothing it leaves out" })
    }

    /** The [entries] under [where] that name [needle], and those of every zip among them. */
    private fun naming(
        where: String,
        entries: Sequence<Pair<String, ByteArray>>,
        needle: String,
    ): List<String> =
        entries
            .flatMap { (name, bytes) ->
                val path = "$where!$name"
                val here = if (needle in name.lowercase() || says(bytes, needle)) listOf(path) else emptyList()
                here + if (isZip(bytes)) naming(path, unzipped(bytes), needle) else emptyList()
            }.toList()

    private fun unzipped(bytes: ByteArray): Sequence<Pair<String, ByteArray>> {
        val zip = ZipInputStream(bytes.inputStream())
        return generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.map { it.name to zip.readBytes() }
    }

    private fun says(
        bytes: ByteArray,
        needle: String,
    ): Boolean {
        val text = bytes.toString(Charsets.ISO_8859_1).lowercase()
        return needle in text || needle.map { "$it\u0000" }.joinToString("") in text
    }

    /** A zip starts with the local header of its first entry: "PK", 3, 4. */
    private fun isZip(bytes: ByteArray) = bytes.take(4) == listOf<Byte>(0x50, 0x4B, 3, 4)
}

android {
    namespace = "nl.mattix.andamp"

    defaultConfig {
        applicationId = "nl.mattix.andamp"
        targetSdk = 36
        // release-please owns this line
        versionName = "0.11.1" // x-release-please-version

        // The code is derived from the name, not assigned by Play, so the repository records which
        // commit has which version code.
        versionCode = versionCodeOf(versionName!!)
    }

    // One app, and no flavors. No source is compiled in: a source is an app the listener installed,
    // and the player draws what it finds.
    //
    // This APK names no service. NamesNothing (wired below) reads the packaged APK back for the
    // word, and NamesNoServiceTest reads what was compiled.
    signingConfigs {
        create("upload") {
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                keyAlias = signing.getProperty("keyAlias")
                storePassword = secret("RT_STORE_PASSWORD", "storePassword")
                keyPassword = secret("RT_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks and renames; proguard-rules.pro says what it may not
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("upload").takeIf { storePath != null }
        }
    }

    buildFeatures {
        compose = true
        // the About line shows the build's own version
        buildConfig = true
    }

    androidResources {
        noCompress += "wsz"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true // Robolectric needs the real assets (base skin)
        }
    }
}

androidComponents {
    onVariants { variant ->
        // AGP 9 does not take a provider through the source-set API; a generated directory is added
        // through the variant API.
        variant.sources.assets?.addGeneratedSourceDirectory(bundledSkins, BundleSkins::into)
        variant.sources.assets?.addGeneratedSourceDirectory(bundledLicenses, BundleLicenses::into)

        // The app read back as it was packaged: its APK before assembling counts as done, and its
        // bundle before anything sends it. Every variant.
        val named = variant.name.replaceFirstChar { it.uppercase() }
        val apk =
            tasks.register<NamesNothing>("verify${named}ApkNamesNothing") {
                description = "Reads the packaged $named APK for the name of the service this app leaves out."
                packages.from(variant.artifacts.get(SingleArtifact.APK))
                word.set("spotify")
                verdict.set(layout.buildDirectory.file("reports/names-nothing/${variant.name}-apk.txt"))
            }
        val bundle =
            tasks.register<NamesNothing>("verify${named}BundleNamesNothing") {
                description = "Reads the packaged $named bundle for the name of the service this app leaves out."
                packages.from(variant.artifacts.get(SingleArtifact.BUNDLE))
                word.set("spotify")
                verdict.set(layout.buildDirectory.file("reports/names-nothing/${variant.name}-bundle.txt"))
            }
        // by name, because AGP and Play Publisher make these tasks after this
        // runs; configureEach reaches them whenever they appear
        tasks.configureEach {
            val sends = name.startsWith("publish$named") || name.startsWith("upload$named")
            when {
                name == "assemble$named" -> dependsOn(apk)
                name == "bundle$named" -> finalizedBy(bundle)
                sends && name.endsWith("Apk") -> dependsOn(apk)
                sends && name.endsWith("Bundle") -> dependsOn(bundle)
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.compose.material.icons)
    implementation(project(":core:playback"))
    implementation(project(":core:player"))
    // the app loads a picked .lua before it stores it, so a plug-in that cannot run is not
    // installed
    implementation(project(":core:plugin"))
    implementation(project(":backend:media3"))
    // The client end of a pack, and the contract it implements. A source is found on the phone, not
    // compiled in, and neither module names a service, so the word scan over the shipped app finds
    // nothing.
    //
    // The contract is declared as well as the client, though the client already passes it on: what
    // is used directly is declared.
    implementation(project(":backend:pack"))
    implementation(project(":core:packapi"))
    implementation(project(":visualizer:avs"))
    implementation(project(":visualizer:projectm"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose) // the modern screens are destinations in a navigation graph
    implementation(libs.coil.compose) // online skin browser thumbnails
    implementation(libs.coil.network)
    implementation(libs.okhttp) // museum pages and thumbnails share one client
    implementation(libs.material.mdc) // modern chrome (menus, dialogs) outside the skin canvases only
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.billing.ktx) // the tip jar: tips are bought through Google Play
    detektPlugins(libs.detekt.compose.rules)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core:playback")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest) // merges ComponentActivity into the debug manifest for Robolectric
}

/**
 * Something signed with the upload key comes out of it: a release packaged,
 * installed or sent. Matched on the whole name and on the packaging verbs
 * only, because a bare "Release" also matches `testReleaseUnitTest`, and unit
 * tests need no passwords.
 */
fun Task.signsARelease(): Boolean {
    val packages = name.endsWith("Release") && listOf("assemble", "bundle", "install").any { name.startsWith(it) }
    val sends = "Release" in name && (name.startsWith("publish") || name.startsWith("upload"))
    return packages || sends
}

// A refusal made against the tasks Gradle resolved, not the command line.
//
// Gradle expands an abbreviation (`pRB`) and an aggregate (`publishReleaseBundle`, `build`) into
// the variant tasks behind it, and only the graph says which those are. The graph is ready before
// the first task runs, so a refusal here comes before anything is built or sent, and it looks at
// this module's tasks alone.
//
// The signing config falls back to nothing when a password is missing, and AGP then packages an
// unsigned bundle, which Play rejects on upload. So a build that would sign a release and has a
// keystore but no passwords stops here, naming the command that supplies them.
gradle.taskGraph.whenReady {
    val ours = allTasks.filter { it.project == project }
    if (storePath == null || ours.none { it.signsARelease() }) return@whenReady
    val missing =
        listOf("RT_STORE_PASSWORD" to "storePassword", "RT_KEY_PASSWORD" to "keyPassword")
            .filter { (variable, property) -> secret(variable, property) == null }
            .map { it.first }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "the upload key is named in keystore.properties but ${missing.joinToString(" and ")} " +
                "${if (missing.size == 1) "is" else "are"} not set, and the release would be " +
                "unsigned. Set them for the command:\n\n" +
                "  RT_STORE_PASSWORD=… RT_KEY_PASSWORD=… ./gradlew :app:bundleRelease\n",
        )
    }
}

/**
 * Publishing from the command line.
 *
 * ```
 * PLAY_JSON_KEY=/path/to/service-account.json ./gradlew :app:publishReleaseBundle
 * ```
 *
 * builds the bundle, signs it with the upload key and puts it on the internal track, with the
 * release notes tools/release.py wrote under src/main/play/release-notes. Another track is named
 * with `-Ptrack=production`.
 *
 * Without the variable the block is not configured, so an ordinary build carries no credentials and
 * cannot publish. The service account's JSON key is kept outside this repository.
 *
 * The version code comes from `versionName`.
 */
val playKey: String? = System.getenv("PLAY_JSON_KEY")?.takeIf { it.isNotBlank() }
if (playKey != null) {
    play {
        serviceAccountCredentials.set(file(playKey))
        track.set(providers.gradleProperty("track").orElse("internal"))
        defaultToAppBundles.set(true)
        // not AUTO: the version code is derived from the name, and letting
        // Play pick a different one would put the two out of step
        resolutionStrategy.set(com.github.triplet.gradle.androidpublisher.ResolutionStrategy.FAIL)
    }
    // bootstrap downloads what Play has now. Into the build directory, because the plugin's default
    // is src/main/play, where a download would overwrite what release.py wrote. By name, since the
    // plugin keeps the task's class to itself, and once the graph is ready, since the plugin sets
    // its default after any configureEach here.
    tasks.configureEach {
        if (name.startsWith("bootstrap") && hasProperty("srcDir")) {
            val into = property("srcDir") as DirectoryProperty
            gradle.taskGraph.whenReady { into.set(layout.buildDirectory.dir("play-now")) }
        }
    }
}
