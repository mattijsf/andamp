# DSP plug-in reference

What a plug-in author writes, and what the host does with it. The script runs off the audio
thread, once for each audio format a stream turns out to have, and returns two things: a
description of the controls and a graph of built-in primitives. No Lua runs while audio is
playing. The reasons for that design, with measurements, are in the design note,
[`docs/dsp-plugins.md`](https://github.com/mattijsf/andamp/blob/main/docs/dsp-plugins.md).

## The shape of a file

```lua
plugin { ... }          -- metadata, required
local p = param.xxx {}  -- parameters, zero or more
ui { ... }              -- optional; omitted means "lay my parameters out for me"
presets { ... }         -- optional; named settings
function build(g, ctx)  -- the effect, required
  ...
end
```

| The plug-in | What it writes |
| --- | --- |
| an on/off effect, no controls | `plugin` and `build` |
| one slider | `plugin`, one `param`, `build` |
| several sliders | as above, more `param`s |
| grouped or switched sections | all of it, with `ui` for the grouping |

**The host owns the plug-in's on/off switch.** It is drawn on the effect's card in the rack,
switching it fades the rack through the dry signal so it does not click, and it costs the
author nothing. Do not declare a Power parameter: an author who does ends up writing the
bypass twice, in the UI and again in the graph, where the two can disagree.

## 1. Metadata

```lua
plugin {
  id      = "org.example.tapestop",  -- reverse-DNS; identity for settings and presets
  name    = "Tape Stop",             -- shown in the rack and the plug-in list
  version = "1.2.0",
  author  = "Jane Example",          -- optional
  about   = "Slows the transport like a tape machine losing power.",  -- optional
  api     = 1,                       -- optional; the host refuses what it cannot run
}
```

`id`, `name` and `version` are required, and so is a `build` function. `api` means the
current schema version, 1, when it is left out, which is what the examples do; any other
number is refused. `author` and `version` are shown on the plug-in's card under
**Preferences → Plug-ins** and in the dialog that confirms an install; `about` is shown there
too, and again under the effect's name in the rack. Any other key, `url` included, is
accepted and not shown anywhere.

The `id` cannot be one Andamp already uses for an effect of its own, bundled plug-ins
included.

## 2. Parameters

A parameter is declared once and referred to by both the UI and the graph. It is the only
channel through which a control reaches audio.

```lua
local mix = param.number {
  id      = "mix",          -- stable; the key its value is stored under
  name    = "Mix",          -- the label; the id when omitted
  min     = 0, max = 1,     -- engineering units: what the graph receives; 0 and 1 when omitted
  default = 0.5,            -- required; see below
  unit    = "pc",           -- LV2 unit symbol; see below
  smooth  = 20,             -- ms of smoothing before the graph sees a change; 0 to jump
  help    = "Wet against dry.",  -- a sentence behind a mark; see below
  display = {               -- optional, readout only; never changes what the graph gets
    scale    = 100,         -- multiply for display: a 0..1 mix reads 0..100
    decimals = 0,           -- worked out from the range when omitted
    zero     = "OFF",       -- text to show at the minimum
  },
}

local mode = param.choice {
  id = "mode", name = "Mode",
  options = { Chorus = 0, Flanger = 1, Phaser = 2 },  -- label to the value the graph reads
  default = "Flanger",                                 -- a label; required
}

local ebs = param.toggle { id = "ebs", name = "Enhanced Bass", default = false }
```

Those are the keys the host reads. A key it does not know is ignored rather than refused, so
a misspelled optional key does nothing; check a new control on the device.

**`default` is required.** A parameter without one stops the plug-in from loading. The
default is where the slider is drawn and the value the graph receives until the listener
moves the control. A `default` outside `min`..`max` is refused too, since no drag could
bring the slider back to it.

**Units are display only.** The graph always receives the value in the range declared by
`min` and `max`. A percentage that should reach the graph as 0..1 is declared `min = 0,
max = 1` with `display = { scale = 100 }`, not `min = 0, max = 100`.

`unit` takes an LV2 unit symbol rather than free text, so the host knows how to write it:
`db`, `hz`, `khz`, `ms`, `s`, `pc`, `semitone12TET`, `coef`, `degree`, `bar`, or omitted for
a bare number. Anything else, "Hz" and "%" included, is refused at load.

The readout is worked out in this order:

1. At its minimum, a control with `display.zero` reads as that text.
2. When `display.scale` is omitted or 1, and the control either has the unit `pc` or has no
   unit and a `max` of 1 or less, the readout is the value times 100, rounded to a whole
   number, with no symbol. `display.decimals` is not used.
3. Otherwise the readout is the value times `display.scale`, followed by the unit's symbol.
   It has `display.decimals` decimals when that is given. When it is not, it has one decimal
   if `max - min` times the scale is 50 or less, and none if it is more.

So a `pc` control with `display = { scale = 100 }` reads `50 %`, and the same control
without a scale reads `50`.

**`help` is where a control's trade goes.** A label has room for a word, and some controls
have a consequence that cannot be guessed from one: Andamp's own Karaoke has a Width that
puts a floor on how much of the singer can be removed. Declare the sentence and the host
draws a small circled i beside the control; tapping it shows the text in a popup, and
tapping away dismisses it.

```lua
param.number {
  id = "width", name = "Width", min = 0, max = 1, default = 0,
  help = "Mixes the untouched stereo back over the result. It carries the voice with it, " ..
         "so any Width at all is a floor on how much of the singer goes.",
}
```

Rules, so the mark stays worth noticing:

- **Omitted means no mark.** A control with nothing to add does not carry a dead touch
  target.
- **It is a sentence or two, not a manual.** The popup wraps it at a readable width and does
  not scroll.
- **Plain text.** No markup, no links, no images, the same rule as everywhere else in the UI.
- **It never reaches the graph.** Like `unit` and `display`, it is a readout: changing it
  cannot change a sample.

`group` takes `help` too, on the same terms, for a trade that belongs to a whole section
rather than to one slider. `label` does not: it is already free text, and a line of text
with its own footnote is a sign the line should have said more.

**A choice is a map, not a list.** The stored value is the number, not the position, so
inserting a mode between two existing ones does not change what every stored setting means.
This is Ardour's `scalepoints`. A Lua table with named keys has no order, so the host draws
the options by value, lowest first; number them in the order they should appear. A choice
reaches the graph unsmoothed, since the values between two settings are other settings, and
its `min` and `max` are the smallest and largest of its values.

**A toggle is a number.** It reaches the graph as 0 or 1, smoothed like any other value, so
`g.crossfade(dry, wet, ebs)` is a click-free bypass and needs nothing else.

**Every parameter is live.** There is no `structural` key in api 1: `build` runs once per
audio format, `ctx` carries only `sampleRate` and `channels`, and every parameter reaches the
graph as a value that moves while it plays. A plug-in with modes builds every
arrangement it offers and selects between them with arithmetic on the choice's value, such as
a crossfade.

## 3. UI

### The default: no UI block

With no `ui`, the host lays out every parameter in declaration order: sliders for numbers,
switches for toggles, a row of options for choices. Most plug-ins should stop here.

### The UI block

```lua
ui {
  group("Sound control", {
    slider(tempo),
    slider(pitch),
    toggle(disable, { label = "Disable sound tweaking" }),
  }),
  group("Enhanced Bass System", {
    slider(ebsFreq),
    slider(ebsGain),
  }, { enabledBy = ebs }),
}
```

A widget takes the handle `param.*` returned, not the parameter's id.

| Widget | For | Notes |
| --- | --- | --- |
| `slider(p, opts)` | `param.number` | horizontal, label left, value right |
| `toggle(p, opts)` | `param.toggle` | a switch |
| `choice(p, opts)` | `param.choice` | segmented buttons for up to four options of at most 12 characters; radio buttons otherwise |
| `label(text, opts)` | | a line of static text |
| `group(name, items, opts)` | | a titled card; `name` may be `nil` |

`opts`, on every widget:

```lua
{ label = "Disable sound tweaking" }   -- overrides the parameter's name, or a group's
```

and on `slider`, `toggle`, `choice` and `group`, but not on `label`:

```lua
{ help = "What this trades away." }    -- overrides the parameter's own help
```

and on `group` additionally:

```lua
{ enabledBy = ebs }   -- a param.toggle
```

Any other key in `opts` is refused at load, and so is a widget given the wrong kind of
parameter.

**`enabledBy`.** The host draws the toggle in the group's header and grays the group's
body while the toggle is off. The bypass itself is written in `build`, reading the same
toggle. The host refuses a plug-in whose graph never reads a toggle that a group is
enabled by, and names the group and the toggle in the error.

The host cannot bypass the group's part of the graph itself. A group gathers controls,
`build` never mentions groups, and so the host does not know which nodes belong to which
group.

A group's `enabledBy` applies to everything inside it. A group may sit inside another, and
its `enabledBy` is checked against the graph the same way.

### What the host will not do

- No colors, fonts, images or custom drawing. The app's theme decides how a slider looks,
  which keeps a plug-in legible in every skin and readable to a screen reader. Winamp let a
  plug-in draw its own dialog; on a phone that is neither possible nor kind.
- No absolute positions or pixel sizes. Sliders are full width and stack vertically.
- **No hiding.** Controls gray out, they never disappear. A page that reflows as you toggle
  is harder to use than one with a grayed row, and a control that vanishes takes the reason
  it was there with it.
- No modal dialogs and no separate windows. The `help` popup is the host's, not the
  plug-in's: it holds one plain string, it is dismissed by tapping away, and a plug-in cannot
  put a control inside it.

What the host renders is a column of named horizontal sliders, switches and choices, in
titled groups.

## 4. Presets

```lua
presets {
  { name = "Wide",   values = { mix = 0.8, stereo = 1.0 } },
  { name = "Subtle", values = { mix = 0.2, stereo = 0.3 } },
}
```

Keys are parameter **ids**, as strings, not the Lua locals. A value is a number, or `true` or
`false` for a toggle; a choice is set by the number behind the option. A preset that names a
parameter the plug-in does not have is refused at load.

Presets are drawn as a row of chips above the controls, and the chip whose values match the
current settings is lit. Applying one sets all its values in one step, so nothing sweeps on
the way, and a parameter it does not name stays where the listener had it: a preset is a
starting point rather than a reset.

The listener's own settings are the host's business, not the plug-in's: every effect's card
has **Save settings**, **Restore saved settings** and **Reset to defaults** in its menu,
whether or not the author declared any presets. Removing a plug-in removes what was saved
for it too.

## 5. Wiring: controls to graph

### A parameter is an edge, not a number

```lua
plugin { id = "org.example.flanger", name = "Flanger", version = "1.0.0" }

local rate   = param.number { id = "rate", name = "Rate", min = 0.05, max = 8, default = 0.3,
                              unit = "hz" }
local depth  = param.number { id = "depth", name = "Depth", default = 0.6 }
local fback  = param.number { id = "fback", name = "FBack", max = 0.7, default = 0.4 }
local stereo = param.number { id = "stereo", name = "Stereo", default = 0.5 }
local mix    = param.number { id = "mix", name = "Mix", default = 0.5 }

function build(g, ctx)
  -- ctx.sampleRate, ctx.channels
  local out = {}
  for ch = 0, ctx.channels - 1 do
    local dry = g.input(ch)
    local lfo = g.lfo { rate = rate, shape = "sine", phase = g.mul(stereo, (ch % 2) * 0.5) }
    local fb = "fb" .. ch                 -- a feedback name is used once, so one per channel
    local line = g.delay {
      input   = g.add(dry, g.mul(g.tapOut(fb), fback)),
      time    = g.mul(g.add(1.0, g.mul(depth, lfo)), ctx.sampleRate / 1000),
      maxTime = 0.008 * ctx.sampleRate,   -- a build constant, not an edge
      interp  = "linear",
    }
    g.tapIn(fb, line)
    out[ch] = g.crossfade(dry, line, mix)
  end
  return out
end
```

Using a parameter in `build` does not read its value, it wires the control to that input.
Moving the slider moves the audio, with no rebuild and no Lua. `build` returns a table with
one entry per channel, indexed from 0.

Three guarantees, so an author never thinks about threads:

1. **The graph sees engineering units**, in the range declared. `unit` and `display` are the
   readout's business.
2. **Values are smoothed** over `smooth` ms, default 20, so a drag cannot zipper and a toggle
   cannot click. A choice jumps.
3. **Arithmetic on plain numbers happens in Lua, once.** `ctx.sampleRate / 1000` is a number
   by the time the graph sees it and costs nothing while playing; arithmetic through `g`
   becomes nodes, because it has to be recomputed when a parameter moves.

### Control-rate arithmetic

`g.mul(freq, 2)` where `freq` is a parameter is a node whose value changes at most as fast as
a finger moves, and evaluating it 44,100 times a second would be waste. So a node whose
inputs are all parameters, constants or other such nodes is evaluated **once per control
tick**, every 32 frames (0.73 ms at 44.1 kHz), not once per frame. The compiler works this
out; an author does not declare it and cannot get it wrong. Stateful primitives, the ones
whose output depends on their own past (filters, delays, taps, oscillators, envelopes,
noise, feedback pairs), run every frame whatever feeds them, and so does anything they feed.

A biquad's coefficients follow the same rule: a cutoff on a slider costs its trigonometry
once per control tick, and the same cutoff on an oscillator costs it every frame.

### One graph per audio format

A graph is built for one sample rate and channel count. When a stream arrives in a format
the plug-in has not been built for, the host runs the script again, off the audio thread,
with that format in `ctx`, and keeps the result for the next stream in the same format. A
plug-in that cannot be built for a stream's format is left out of the rack for that stream,
and its card under Preferences → Plug-ins says so. Installing a plug-in already builds it at
44.1, 48 and 22.05 kHz in stereo and refuses it if any of them fails.

## 6. The primitives

Naming a primitive that is not in this table is a load failure. The catalog is the spec,
and a test compares this table with what the host accepts.

Every argument marked **edge** accepts a parameter, another node, or a number. Every argument
marked **const** must be a number or a word known at build: it sizes a buffer or picks a
behavior. Options are passed in one table. An unknown or missing option is a load failure
that names the node's number, its primitive and the option. A parameter or a node where a
const belongs is also a load failure, with the interpreter's own message (`bad argument:
double expected, got table`, or `string expected` for a word), which names neither.

| Primitive | Arguments | Notes |
| --- | --- | --- |
| `g.input(ch)` | const channel | the incoming audio; any channel, from anywhere in the graph |
| `g.add(...)`, `g.mul(...)` | edges, two or more | varargs, folded left to right; summing five terms is one call |
| `g.sub(a, b)`, `g.div(a, b)` | edges | |
| `g.min(a, b)`, `g.max(a, b)` | edges | |
| `g.crossfade(a, b, t)` | edges | `t` 0 gives `a`, 1 gives `b`, and both ends are exact |
| `g.gain { input, db }` | edge, edge in dB | the one place dB becomes amplitude |
| `g.math { input, fn }` | edge, const fn | `exp2`, `log2`, `sin`, `cos`, `tan`, `tanh`, `sqrt`, `abs`, `recip`, `neg`, `floor`, `round` |
| `g.biquad { input, kind, freq, q, gainDb }` | edge, const kind, edges | RBJ cookbook. Kinds: `lowpass`, `highpass`, `bandpass`, `notch`, `allpass`, `peaking`, `lowshelf`, `highshelf`. `q` is required and is the cookbook's Q for every kind, shelves included, where 0.707 gives the cookbook's S = 1. `gainDb` is optional, 0 when omitted, and means something only for the last three |
| `g.onepole { input, coeff }` | edges | a raw one-pole, `y = x + (y' - x) * coeff`: 0 passes the input, toward 1 holds the previous output. For a damper inside a loop, where a biquad's coefficients are the wrong knob |
| `g.allpass1 { input, coeff, ceiling }` | edges, const ceiling | one first-order all-pass section; `ceiling`, above 0 and at most 16, bounds its output, which is what a cascade under feedback needs |
| `g.delay { input, time, maxTime, interp }` | edge, edge in samples, const, const | `maxTime` in samples sizes the line; `time` is held between 1 and `maxTime`. `interp`: `none` or `linear`, `linear` when omitted |
| `g.tap { line, time, interp }` | a `g.delay`, edge in samples, const | a second reader on an existing `g.delay`, so several reflections share one buffer. See the note below |
| `g.lfo { rate, shape, phase }` | edge in Hz, const shape, edge 0..1 | shapes: `sine`, `triangle`. `phase` is 0 when omitted. Output -1..1 |
| `g.envelope { input, attack, release, kind }` | edge, consts in ms, const | `kind`: `peak` or `rms`, `peak` when omitted |
| `g.noise { seed }` | const, optional | white noise, -1..1; `g.noise()` also works. The same seed gives the same noise every run; two nodes with one seed are the same noise twice, so give each its own |
| `g.softclip { input, drive, knee }` | edge, edge, const | shapes `input * drive`: up to `knee` it is unchanged, above it bends smoothly toward 1 and never reaches it. `knee` is 0 when omitted. For anything that can boost |
| `g.clip { input, min, max }` | edges | hard, for safety rather than tone |
| `g.sanitise(input)` | edge | zeroes what is not finite and what has gone denormal |
| `g.tapIn(name, source)`, `g.tapOut(name)` | const name and an edge; const name | a feedback pair: `g.tapOut` reads what `g.tapIn` was given one frame earlier. Each name has exactly one of each, or the plug-in is refused |

**Feedback is bounded.** Whatever is written into a delay line or a feedback pair
has non-finite values zeroed and is held between -16 and 16, so a loop whose gain is on a
slider can run away only as far as that and never to infinity. A cycle with no delay line or
feedback pair in it is refused, since the engine would have to evaluate it to evaluate
itself.

**`g.tap` and `line`.** `line` is the handle a `g.delay` returned, and nothing else: a
number there is refused. The tap reads that delay's buffer at its own `time`, so several
reflections share one line's memory.

**Budgets.** A graph may have at most 512 nodes and 262,144 samples of delay memory in total,
counted from every `maxTime`.

The effects built into Andamp, Karaoke, Modulation and Reverb, are graphs over this same
catalog, which is how it is known to be enough for them.

### How the graph is encoded

Three decisions, all borrowed rather than invented:

- **Every input is a tagged reference**: either a constant, or a node's output, the way a
  SuperCollider SynthDef encodes them. Constant folding then falls out of the encoding rather
  than being a rule the engine has to remember, and the loader can tell a mistyped constant
  from a missing node.
- **Every node carries a rate tag**, audio or control, also from SynthDef. The compiler
  infers the rate and refuses a graph whose tag disagrees, so the promise in section 5 that
  a node fed only by parameters runs once per control tick is verified rather than hoped for.
- **Feedback is a named pair**, `g.tapIn("fb", x)` and `g.tapOut("fb")`. An unmatched tap is
  then a load error with a name in it, which is the difference between a fixable message and
  a silent graph.

A node returns exactly one edge, which is why an input reference needs no output index.

**Reading across channels is ordinary.** `build` sees every channel, `g.input(k)` reads the
stage's input frame rather than a partly written one, and Andamp's own Karaoke subtracts one
input channel from the other, so a mid/side split, a mono sum and a stereo widener are all
expressible. So is a stereo-linked detector: `leveler.lua` takes the larger of two envelopes.
What the catalog does not have is a primitive with two signal inputs sharing one piece of
state.

## 7. What this cannot express

**Anything that changes the sample count.** Time stretch, pitch shift, tempo. Winamp's
Pacemaker is the example. The whole engine is frame-synchronous, one input frame in and one
output frame out, which is what makes it cheap and safe. A rate-changing node would need its
own buffering, its own latency reporting, and a conversation with ExoPlayer about the clock.

**A bespoke per-sample nonlinearity.** A tube model, a waveshaper with hysteresis. What can be
built from `g.math`, `g.softclip` and arithmetic can be written; a wavetable filled at load is
not in the catalog.

**Anything spectral.** A vocoder, spectral gating. There is no FFT primitive.

**A plug-in-drawn interface**, permanently. See section 3.

## 8. Failure

A plug-in is rejected at load, with the reason shown, when it:

- declares an `api` other than 1,
- leaves out `id`, `name` or `version`, or has no `build` function,
- declares a parameter without a `default`, a number whose `default` is outside its `min`..`max`,
  a choice with no options or a default that is not one of them, or a `unit` that is not one of
  the symbols in section 2,
- calls a primitive, a widget option or a primitive option that does not exist, or leaves out
  a primitive option that is required,
- puts a widget on the wrong kind of parameter, `help` on a `label`, or something that is not
  a widget in a `ui` block or a group,
- has a preset that names a parameter it does not have, or sets one to something that is not a
  number or a boolean,
- switches a group with a toggle its graph never reads,
- returns nothing for a channel, uses a feedback name more or less than once on each side,
  or loops back on itself with no delay line or feedback pair in the loop,
- exceeds a budget: 256 KiB of source, 3 s to load, 512 nodes, 262,144 samples of delay
  memory,
- throws.

The loader reports every graph error it finds rather than the first.

What the host does not check: keys it does not read in `plugin { }` and `param.*` tables,
which it ignores.

Rejection is a load failure and nothing else. When a listener adds a plug-in, a rejection is
a dialog with the reason in it and nothing is stored; an install is also refused when the
plug-in fails at 48 or 22.05 kHz, or when its `id` belongs to an effect Andamp already has. A
plug-in already installed that cannot be built for a stream is left out of the rack for that
stream. Playback continues either way. Nothing a plug-in does may take the audio down.

## 9. Settings across versions

A plug-in's values are stored flat, one key per parameter, `<plugin id>.<parameter id>`, so a
parameter added later reads as its default instead of invalidating everything the listener
had set up. What that means for a new version of a plug-in:

| What changed in the new version | What the host does |
| --- | --- |
| a parameter was added | it takes its default |
| a parameter was removed | its stored value is ignored |
| `min`/`max` changed | the stored value is used as it is, not clamped |
| `unit`, `display` or `help` changed | the stored value is kept; all three are readouts |
| a `choice` lost an option | the stored number is used as it is |
| a parameter's `id` changed | it is a different parameter: it starts at its default |

So narrowing a range or dropping an option is safest done under a new parameter `id`.
Installing a file whose plug-in `id` is already installed asks the listener before replacing
it, and names both versions.

## 10. What was borrowed, and from where

Parts of the schema come from other systems:

| From | What |
| --- | --- |
| LV2 | the unit symbols |
| Ardour | `scalepoints`, here as a choice being a map rather than a list |
| SuperCollider SynthDef | tagged input references and per-node rate tags |
| Elementary | named feedback taps instead of an anonymous placeholder |

`enabledBy`, with the rule that the graph must read the toggle, is Andamp's own.

## 11. Examples

The plug-ins Andamp ships are in `backend/media3/src/main/resources/plugins/`. They are loaded
through the same loader and sandbox as a listener's own file, so they are examples and
product at once, and each is one job with one or two controls:

- `preamp.lua`: one gain control and presets
- `tone.lua`: two shelves, presets, and an output gain that makes room for a boost
- `virtualbass.lua`: an OFF position, `g.math` tanh on a band-limited signal, and a
  look-ahead limiter built from envelopes and a delay
- `width.lua`: mid/side arithmetic, and a graph that passes mono through
- `leveler.lua`: an envelope turned into a gain in dB, arithmetic over a level
- `crossfeed.lua`: a delay in samples from `ctx.sampleRate`, the other channel as input

The rest are in [`examples/`](https://github.com/mattijsf/andamp/tree/main/docs/examples),
where each exists to show one thing:

- [`gain.lua`](examples/gain.lua): the smallest plug-in with a control
- [`warmth.lua`](examples/warmth.lua): a toggle, and what smoothing does to one
- [`shaped.lua`](examples/shaped.lua): a declared `ui` block, with a switched group
- [`tremolo.lua`](examples/tremolo.lua): an oscillator, and a control in hertz that reaches
  it while it runs
- [`vinyl.lua`](examples/vinyl.lua): `g.noise` as four different things at once: a hiss, a
  threshold-gated crackle, a scratch and a rumble
