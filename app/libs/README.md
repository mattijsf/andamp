# Libraries kept in the repository

`m2/` is a Maven-style folder `settings.gradle.kts` reads for the group `nl.mattix.walkit`:
walkit, the modern skin engine in native code, built from its own repository
(github.com/mattijsf/walkit) and published here with `./gradlew publishToMavenLocal` there and
a copy of `~/.m2/repository/nl/mattix/walkit/walkit/<version>/`. The AAR carries the engine for
`arm64-v8a` and `x86_64`; the test-fixtures AAR carries the engine built for the Mac it was
published on (`darwin-aarch64`) and the helpers the app's unit tests drive a skin with.

| version | walkit tag (commit) | notes |
|---|---|---|
| 0.3.0 | v0.3.0 (fce69c9) | first build kept here: consumer rules, host build and script helpers in the test fixtures |
| 0.3.2 | v0.3.2 (a2b9595) | a window is black where its skin draws nothing; a group with a stretched background reaches one pixel further up and left |
| 0.4.3 | v0.4.3 (73a4e4b) | a skin's windows can sit apart, dock and snap and rise by a press as on Winamp's desktop; a drag redraws no window; a press between them moves nothing (`Presentation.freeWindows`, off here until the player uses it) |

Only the current version's files are kept; the rows above are the log of what was kept here.
