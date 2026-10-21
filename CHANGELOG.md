# Changelog

What changed for somebody who uses Andamp, newest first. It is written from the commit
messages, which is why those are conventional (`feat:`, `fix:`, `change:`). `docs:` and
`chore:` commits do not appear here.

## [0.16.0](https://github.com/mattijsf/andamp/compare/v0.15.0...v0.16.0) (2026-10-21)


### New

* a source's library opens at once, also without a network ([#79](https://github.com/mattijsf/andamp/issues/79)) ([80297b7](https://github.com/mattijsf/andamp/commit/80297b7a139ee49fa8dc188d7b6d2a3c2ffb41b4))
* Android's App info page opens Andamp's settings ([aecf444](https://github.com/mattijsf/andamp/commit/aecf444572b48de7e66b7a8c96cb6c6bdbe3d6d1))
* browse the phone's music by folder or genre, and pick several songs at once ([#85](https://github.com/mattijsf/andamp/issues/85)) ([3226c1c](https://github.com/mattijsf/andamp/commit/3226c1c2d530a008e89cb0b7352dcd96b7a14559))
* choose how songs show in the playlist and the library ([#86](https://github.com/mattijsf/andamp/issues/86)) ([3d6db9a](https://github.com/mattijsf/andamp/commit/3d6db9a191c2e0b8a8b6274363d4bfb715618086))
* each source's rows carry a small picture, also in the playlist if you like ([#82](https://github.com/mattijsf/andamp/issues/82)) ([58eb185](https://github.com/mattijsf/andamp/commit/58eb18501f7ac6e5abe7990b8b05e70f313aff6f))
* every source shows a little picture of itself in the lists ([#87](https://github.com/mattijsf/andamp/issues/87)) ([f2f16bc](https://github.com/mattijsf/andamp/commit/f2f16bc2f18dcb443c26a955a1b50a03016c6529))
* favorite visualizer presets, with a preview and why one failed ([#74](https://github.com/mattijsf/andamp/issues/74)) ([724099a](https://github.com/mattijsf/andamp/commit/724099a91223dca97e7cee448ab70e55bb6d97a5))
* Folders and Genres in the library include your music servers ([#93](https://github.com/mattijsf/andamp/issues/93)) ([04b95e4](https://github.com/mattijsf/andamp/commit/04b95e4434f0cac92c37ecd0376159f72de6c621))
* one Media Library for all your sources, with the phone's copy played first ([#80](https://github.com/mattijsf/andamp/issues/80)) ([736488f](https://github.com/mattijsf/andamp/commit/736488faeb7da17fc54e87e8251d5eb81e5464e8))
* Recently Added, Folders and Genres carry the mark of the source they come from ([866c364](https://github.com/mattijsf/andamp/commit/866c3642faa0d5b97b43cbece3bad9fa851f00e2))
* sort albums, jump from a song to its album or artist, and play every track at once ([#83](https://github.com/mattijsf/andamp/issues/83)) ([73374ed](https://github.com/mattijsf/andamp/commit/73374edbb16979f196306e1e31261064f13aa6ab))
* the library's source menu and its corner show each source's mark ([#89](https://github.com/mattijsf/andamp/issues/89)) ([1d06dff](https://github.com/mattijsf/andamp/commit/1d06dff3dbaf1efa932cce2e869ffb0441d34ad2))
* the media library keeps lists of what you added lately and what you play most ([#81](https://github.com/mattijsf/andamp/issues/81)) ([6522918](https://github.com/mattijsf/andamp/commit/6522918807fc52d921643c5b452bbee0ef601d81))
* the media library keeps your place in each tab ([#77](https://github.com/mattijsf/andamp/issues/77)) ([f7cb1f3](https://github.com/mattijsf/andamp/commit/f7cb1f375cf5b97658f6ef0e6035eee525a556ae))
* the media library opens faster and its search finds artists and albums too ([#78](https://github.com/mattijsf/andamp/issues/78)) ([0167ab2](https://github.com/mattijsf/andamp/commit/0167ab2504bbd3df97173af4db40e9583a20267f))
* the media library writes in the skin's letters, like the playlist ([#76](https://github.com/mattijsf/andamp/issues/76)) ([09b3288](https://github.com/mattijsf/andamp/commit/09b32888f1ee53e06a042db49604ac7a3e30b515))
* with every source shown, each source's playlists carry its mark ([866c364](https://github.com/mattijsf/andamp/commit/866c3642faa0d5b97b43cbece3bad9fa851f00e2))


### Fixed

* a broken AVS preset came back after switching apps ([#69](https://github.com/mattijsf/andamp/issues/69)) ([d0ffd94](https://github.com/mattijsf/andamp/commit/d0ffd9470d73bf0c33ff27742002f7cf50bc4598))
* a plug-in link from the site opened the start of Preferences ([#73](https://github.com/mattijsf/andamp/issues/73)) ([343fbed](https://github.com/mattijsf/andamp/commit/343fbedf1e6963fbd9a133a42fd12aee1ba736e6))
* a skin's own digits no longer show through the clock while paused ([#91](https://github.com/mattijsf/andamp/issues/91)) ([4832e6a](https://github.com/mattijsf/andamp/commit/4832e6ab24a2a0c1d3bb26b4b0b4e0cf224cf78c))
* mono tracks showed as stereo ([#57](https://github.com/mattijsf/andamp/issues/57)) ([b3d0d00](https://github.com/mattijsf/andamp/commit/b3d0d0055832814cf295bcb1e4e4ab9956988c82))
* Preferences and the preset screen close with a back arrow, not Done ([#75](https://github.com/mattijsf/andamp/issues/75)) ([48f1e70](https://github.com/mattijsf/andamp/commit/48f1e708670430934d08dc207307a40a306fc0f2))
* the app no longer declares the Notifications permission ([#92](https://github.com/mattijsf/andamp/issues/92)) ([dc23498](https://github.com/mattijsf/andamp/commit/dc23498089380fe4ea8e48e35fb1aa9778c631c8))
* the visualizer window showed the wallpaper for a moment as it opened ([#72](https://github.com/mattijsf/andamp/issues/72)) ([a3d0e09](https://github.com/mattijsf/andamp/commit/a3d0e0926f477a88c8b14ec5dc85cd604bf1c9de))
* titles line up beside source marks of different widths ([#88](https://github.com/mattijsf/andamp/issues/88)) ([5c694d2](https://github.com/mattijsf/andamp/commit/5c694d252dea4fcb32e51ee4bae01b013734bc07))


### Faster

* saved lists open without freezing the library ([#70](https://github.com/mattijsf/andamp/issues/70)) ([0bc862b](https://github.com/mattijsf/andamp/commit/0bc862b58d35df92cf197f708def54d8fff5ea52))

## [0.15.0](https://github.com/mattijsf/andamp/compare/v0.14.0...v0.15.0) (2026-10-12)


### New

* Andamp can be read in Dutch, German and French ([#55](https://github.com/mattijsf/andamp/issues/55)) ([3c15c17](https://github.com/mattijsf/andamp/commit/3c15c1780e830b43bb0546f5fd7d9fdb2423620c))
* Andamp can be read in Simplified Chinese ([#53](https://github.com/mattijsf/andamp/issues/53)) ([7dfe2d7](https://github.com/mattijsf/andamp/commit/7dfe2d7825a18254320b42c21ba180f679f07704))

## [0.14.0](https://github.com/mattijsf/andamp/compare/v0.13.0...v0.14.0) (2026-10-12)


### New

* Andamp shows up in Android Auto, with the playlist to pick a song from ([#50](https://github.com/mattijsf/andamp/issues/50)) ([b40d325](https://github.com/mattijsf/andamp/commit/b40d325d19346ca7bfd4b6100b393a9e18ae0924))
* Preferences look like the phone's Settings app, and a music source shows its app's icon ([#49](https://github.com/mattijsf/andamp/issues/49)) ([3e716c7](https://github.com/mattijsf/andamp/commit/3e716c7e453965bfb5b6485ebc5463378fde9869))
* the playlist is written in the skin's own letters, and list rows can be made taller for easier tapping ([#47](https://github.com/mattijsf/andamp/issues/47)) ([189e275](https://github.com/mattijsf/andamp/commit/189e275dd5603737abb26b9021ce2fbb9ad9c646))

## [0.13.0](https://github.com/mattijsf/andamp/compare/v0.12.1...v0.13.0) (2026-10-11)


### New

* turn the phone sideways and the player turns with it, with its own window layout each way ([#42](https://github.com/mattijsf/andamp/issues/42)) ([a73b13b](https://github.com/mattijsf/andamp/commit/a73b13bc52234f3d7b42e0ab414342b9e8c83d9a))

## [0.12.1](https://github.com/mattijsf/andamp/compare/v0.12.0...v0.12.1) (2026-10-11)


### Fixed

* a skin search no longer looks capped at 160 results ([#40](https://github.com/mattijsf/andamp/issues/40)) ([98e936f](https://github.com/mattijsf/andamp/commit/98e936f800fdee3ef2f6fb879fe7c68f2939c55b))
* the playlist follows the song that is playing ([#39](https://github.com/mattijsf/andamp/issues/39)) ([fc3eda8](https://github.com/mattijsf/andamp/commit/fc3eda8d872a56496414fc56213f7c2d9cd793e7))

## [0.12.0](https://github.com/mattijsf/andamp/compare/v0.11.1...v0.12.0) (2026-10-11)


### New

* AVS runs fifteen plug-in effects, and presets with pictures and text ([#37](https://github.com/mattijsf/andamp/issues/37)) ([68c1b23](https://github.com/mattijsf/andamp/commit/68c1b2311a814e0687edfac646118e99bb7b0ba1))
* AVS runs Winamp's own presets, with eight more effects ([#34](https://github.com/mattijsf/andamp/issues/34)) ([2f98e35](https://github.com/mattijsf/andamp/commit/2f98e3527e393b357eebb498a802b69ea1adf64b))
* the AVS window says when a preset has effects it cannot show ([#36](https://github.com/mattijsf/andamp/issues/36)) ([efbc590](https://github.com/mattijsf/andamp/commit/efbc590de438697ea68762ca8f6b2bc2092c2a63))


### Fixed

* a bitrate above 999 kbps no longer runs into its label ([#33](https://github.com/mattijsf/andamp/issues/33)) ([156a909](https://github.com/mattijsf/andamp/commit/156a90930dae5843c50c07f54c68a75cea656b79))
* a folder with more than 500 songs is added whole ([#30](https://github.com/mattijsf/andamp/issues/30)) ([bceea3f](https://github.com/mattijsf/andamp/commit/bceea3f072a626277dfdc552d1af8f2bb6ec392a))
* a skin opened in the museum fits a tablet's screen ([#25](https://github.com/mattijsf/andamp/issues/25)) ([376092e](https://github.com/mattijsf/andamp/commit/376092e4348cd77d82608e56ac1428a6563044f9))
* more MilkDrop presets draw as they should, with projectM 4.1.8 ([#35](https://github.com/mattijsf/andamp/issues/35)) ([e0d1feb](https://github.com/mattijsf/andamp/commit/e0d1febff4542621ce60f97e8e1d054e3a7efa6d))
* the equalizer comes back as you left it after closing the app ([#32](https://github.com/mattijsf/andamp/issues/32)) ([eb0eba3](https://github.com/mattijsf/andamp/commit/eb0eba3edbd6ec8aa529315fb10dcc7c38590f75))

## [0.11.1](https://github.com/mattijsf/andamp/compare/v0.11.0...v0.11.1) (2026-10-09)


### Fixed

* Always On Top no longer crashes the player ([#26](https://github.com/mattijsf/andamp/issues/26)) ([97aa5b0](https://github.com/mattijsf/andamp/commit/97aa5b0df43384a5cf48b29a560740ad4e8085ae))

## [0.11.0](https://github.com/mattijsf/andamp/compare/v0.10.0...v0.11.0) (2026-10-09)


### New

* leave a tip from Preferences &gt; Support Andamp ([#17](https://github.com/mattijsf/andamp/issues/17)) ([55c235a](https://github.com/mattijsf/andamp/commit/55c235aed839944c85edb7ec08a12508e1be921b))
* music from the phone's storage plays even when Andamp can no longer read its folder ([#24](https://github.com/mattijsf/andamp/issues/24)) ([d2ed725](https://github.com/mattijsf/andamp/commit/d2ed725a8034fea6046000473763bf6a67dcb8b8))


### Fixed

* a paused track from a source no longer keeps the phone's processor busy ([#22](https://github.com/mattijsf/andamp/issues/22)) ([1cafc5f](https://github.com/mattijsf/andamp/commit/1cafc5f92a124424f2d3fa72b92bf1adb8c12871))
* a saved list or a bookmark keeps playing after you play something else ([#24](https://github.com/mattijsf/andamp/issues/24)) ([8c2f168](https://github.com/mattijsf/andamp/commit/8c2f1686d96a8ffa500ceed8cb5a3ce2ed064c9b))
* Next and Previous follow shuffle ([#23](https://github.com/mattijsf/andamp/issues/23)) ([6de9e4a](https://github.com/mattijsf/andamp/commit/6de9e4a9511c667130a43392feab8378aca38572))
* the visualizers move with the music a source plays, not ahead of it ([#22](https://github.com/mattijsf/andamp/issues/22)) ([3855255](https://github.com/mattijsf/andamp/commit/3855255bb576076df4185d91a6729ba0139e3750))
* the visualizers move with the music you hear, not ahead of it ([#21](https://github.com/mattijsf/andamp/issues/21)) ([5dc127f](https://github.com/mattijsf/andamp/commit/5dc127f8a4a8007f1d0819fa6f41b179fc3e9e1c))

## [0.10.0](https://github.com/mattijsf/andamp/compare/v0.9.0...v0.10.0) (2026-10-07)


### New

* delete a saved list, from the library's LISTS tab or from LIST &gt; LOAD LIST ([#18](https://github.com/mattijsf/andamp/issues/18)) ([e6a22f2](https://github.com/mattijsf/andamp/commit/e6a22f2ecbc93ff8cb5b37bb40c614daaa20b58a))


### Fixed

* a music source installed while the player was in the background shows up without a reinstall ([#14](https://github.com/mattijsf/andamp/issues/14)) ([302a492](https://github.com/mattijsf/andamp/commit/302a492ea27459ce4cc7e7564372ce4cdb5a7422))
* Always On Top no longer crashes the player on Android 8, 9 and 10 ([#12](https://github.com/mattijsf/andamp/issues/12)) ([2828f37](https://github.com/mattijsf/andamp/commit/2828f3792aa470e2040431d5eae6d8d5ce678c95))
* large album covers and oversized skin pictures no longer use up the phone's memory ([#13](https://github.com/mattijsf/andamp/issues/13)) ([aeaa770](https://github.com/mattijsf/andamp/commit/aeaa7700b9a38e31b5086f439a550849b0c56d36))
* the widget menu and the floating player use the whole screen on every Android version ([#11](https://github.com/mattijsf/andamp/issues/11)) ([e4868fd](https://github.com/mattijsf/andamp/commit/e4868fd51c06f53ac49bc232a2273d0f8d01e676))

## [0.9.0](https://github.com/mattijsf/andamp/compare/v0.8.0...v0.9.0) (2026-10-06)


### New

* the D in the clutter bar fills the screen with the player (Double Size) ([#8](https://github.com/mattijsf/andamp/issues/8)) ([3bf3619](https://github.com/mattijsf/andamp/commit/3bf3619c1a24e413563b54c694e9a183c2201002))

## [0.8.0](https://github.com/mattijsf/andamp/compare/v0.7.2...v0.8.0) (2026-10-05)


### New

* install effect plug-ins from andamp.nl ([#6](https://github.com/mattijsf/andamp/issues/6)) ([ab7653e](https://github.com/mattijsf/andamp/commit/ab7653ecef64005fad0a54d53c4fd383baf3461f))

## [0.7.2](https://github.com/mattijsf/andamp/compare/v0.7.1...v0.7.2) (2026-10-02)


### Fixed

* show when a source needs a newer Andamp, and the right kbps for untagged and 144 kbit/s mp3 files ([#2](https://github.com/mattijsf/andamp/issues/2)) ([b70d8a2](https://github.com/mattijsf/andamp/commit/b70d8a25cdbd3b07755b0d17921df046bd7becf6))

## 0.7.1

- The first release from this repository.
