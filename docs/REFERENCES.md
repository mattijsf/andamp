# References

Ground truth and research sources for Winamp behavior. When implementing a
classic feature, check these before trusting memory.

## Pixel and behavior ground truth

- [webamp](https://github.com/captbaritone/webamp) — the reverse-engineered browser Winamp. Authoritative for:
  - Sprite source rects: `packages/webamp/js/skinSprites.ts`
  - Sprite destinations: `packages/webamp/css/{main,equalizer,playlist}-window.css`
  - Visualizer painting: `packages/webamp/js/components/Vis.tsx` and `VisPainter.ts` (the analyzer's FFT in `FFTNullsoft.ts`)
  - EQ factory presets: `packages/webamp/presets/builtin.json` (the parsed `winamp.q1`; 1..64 EQF scale = our 0..63 slider scale plus one)
  - EQF/q1 file format: `packages/winamp-eqf/`
- [webamp.org](https://webamp.org) — live comparison target; load the same skin and screenshot.

## Feature and menu documentation

- [Winamp Heritage help](https://winampheritage.com/help) — official-era help pages, feature descriptions per window.
- [Winamp Lite 2.72 User's Manual](https://www.oocities.org/mcocrocks/menu/winamp_manual_menu.html) — archived manual with per-menu walkthroughs:
  - [Main menu](https://www.oocities.org/mcocrocks/menu/winamp_manual_menu.html)
  - [Equalizer](http://www.oocities.org/mcocrocks/operation/equalizer/winamp_manual_equalizer.html) — source for the PRESETS menu structure (Load/Save/Delete > Preset, Auto-load Preset, Default) and the "17 presets" count
  - [Player](http://www.oocities.org/mcocrocks/operation/player/winamp_manual_player.html)
  - [Playlist](http://www.oocities.org/mcocrocks/operation/playlist/winamp_manual_playlist.html)

## Assets

- [Winamp Skin Museum](https://skins.webamp.org) — classic `.wsz` skins for testing.
- [Liberation fonts](https://github.com/liberationfonts/liberation-fonts) — bundled playlist font (SIL OFL, license in this folder).
