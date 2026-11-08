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
| 0.5.2 | v0.5.2 (c513398) | a classic skin's shuffle and repeat buttons show on and pressed from the right rows of `shufrep.bmp` |
| 0.5.3 | v0.5.3 (6980e2d) | a classic skin's clutter bar shows a held letter and lights A and D from the host's `ontop` and `doublesize` |
| 0.5.4 | v0.5.4 (7e5ed19) | windows apart snap to and stay inside the host's view size, not the default viewport, and the stack is that size |
| 0.5.5 | v0.5.5 (a540673) | a press on a classic window stays on its control while the finger is within the touch slop of where it went down |
| 0.5.6 | v0.5.6 (ca3219f) | `Presentation.touch`: under it a tap on a classic playlist button leaves its menu open for a second tap |
| 0.5.7 | v0.5.7 (92e3be2) | a press on a picture of an open playlist menu always closes it |
| 0.5.8 | v0.5.8 (68198df) | the host hears the classic playlist's selected row (`PLAYLIST_SELECT`) |
| 0.5.9 | v0.5.9 (29bc396) | a stacked classic playlist stretches down to fill the view in 29-pixel steps |
| 0.5.10 | v0.5.10 (4fe4754) | a classic playlist stretched to fill the view reaches its bottom; its grip sizes nothing then |
| 0.5.11 | v0.5.11 (f95b6a2) | a classic playlist sized apart still fills the view once the windows stack |
| 0.5.12 | v0.5.12 (1d3c1d4) | `Presentation.skinListFont`: a classic playlist spells its rows in the skin's TEXT.BMP letters |
| 0.5.13 | v0.5.13 (3a0fa9d) | classic playlist text at Winamp's 12-pixel em; `HintedFont` draws text hinted, one bit a pixel, as Windows does |
| 0.5.14 | v0.5.14 (138b2fc) | `Presentation.listRowExtra`: taller classic playlist rows |
| 0.5.15 | v0.5.15 (af1f00d) | host actions carry `ActionFrom`: the sender's rect and the pressed point |
| 0.5.16 | v0.5.16 (ae5f9e2) | under touch, a press beside a classic control takes the nearest one; the playlist grip takes its whole corner and a finger beside it |

Only the current version's files are kept; the rows above are the log of what was kept here.
