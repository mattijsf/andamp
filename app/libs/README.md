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
| 0.4.5 | v0.4.5 (cf30c95) | a skin's windows can sit apart, dock and snap and rise by a press as on Winamp's desktop, keep their place and linked width through a layout switch; a drag redraws no window (`Presentation.freeWindows`, off here until the player uses it) |
| 0.4.6 | v0.4.6 (99fa4ec) | among windows apart, a grip's resize snaps to the edges and a new window opens at its skin's spot; a host resize holds, and the move target is none (the player's Windows apart switch uses it) |
| 0.5.1 | v0.5.1 (f454ae5) | classic `.wsz` skins drawn as Winamp 5.666 draws them (main, equalizer, playlist, library and video windows, cursors), also when zipped inside a folder; a modern skin's balance and EQ sliders snap to their middle and a sideways drag sets band after band |

Only the current version's files are kept; the rows above are the log of what was kept here.
