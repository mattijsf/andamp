# Libraries kept in the repository

`m2/` is a Maven-style folder `settings.gradle.kts` reads for the group `nl.mattix.walkit`:
walkit, the modern skin engine in native code, built from its own repository
(github.com/mattijsf/walkit) and published here with `./gradlew publishToMavenLocal` there and
a copy of `~/.m2/repository/nl/mattix/walkit/walkit/<version>/`. The AAR carries the engine for
`arm64-v8a` and `x86_64`; the test-fixtures AAR carries the engine built for the Mac it was
published on (`darwin-aarch64`) and the helpers the app's unit tests drive a skin with.

| version | walkit commit | notes |
|---|---|---|
| 0.3.0 | 06f5f4f (feat/host-library) | first build kept here: consumer rules, host build and script helpers in the test fixtures |
