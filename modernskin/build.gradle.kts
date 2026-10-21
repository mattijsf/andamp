// SPDX-License-Identifier: GPL-3.0-or-later

// Winamp "modern" (Wasabi) skins: the .wal loader, the MAKI interpreter and the runtime, with no
// Android dependency so they run under plain JVM tests.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
    // the reference scenarios (~/Development/winamp-vm/scenarios.json), shared with the VM and Android scripts
    testImplementation("org.json:json:20240303")
}

tasks.test {
    // DisasmTest reads an optional .maki path and range from these; ReferenceRenderTest an optional rectangle to list
    listOf(
        "disasm.file",
        "disasm.from",
        "disasm.to",
        "probe.rect",
        "probe.all",
        "probe.dump",
        "scenario",
        "timing",
        "probe.color",
        "trace.base",
        "defaults",
        "fuzz.count",
        "sysfonts",
        "have.library",
        "private",
        "setting",
        "trace.content",
    ).forEach { k ->
        System.getProperty(k)?.let { systemProperty(k, it) }
    }
}
