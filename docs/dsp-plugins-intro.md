# Writing a DSP plug-in

An effect in Andamp is a `.lua` file. The listener adds it in
**Preferences → Plug-ins → Add plug-in** through the system file picker, or by
opening or sharing the file to Andamp from another app, and it appears in the
effect rack under **Preferences → Effects** beside the built-in effects, with
its own controls. The author has nothing to build, sign or publish to a store.

This page is the outline. The complete reference — every parameter type, the
`ui` block, presets, the primitive catalog, the failure modes — is the
[DSP plug-in reference](dsp-plugin-spec.md).

## The smallest plug-in with a control

```lua
-- Gain: the smallest plug-in with a control.

plugin {
  id      = "nl.mattix.andamp.example.gain",
  name    = "Gain",
  version = "1.0.0",
  author  = "Andamp examples",
  about   = "Turns everything up or down. Nothing else.",
}

local level = param.number {
  id = "level", name = "Level", min = 0, max = 2, default = 1,
  help = "1 leaves the signal alone; 2 is twice as loud, which will clip.",
}

function build(g, ctx)
  local out = {}
  for ch = 0, ctx.channels - 1 do
    out[ch] = g.mul(g.input(ch), level)
  end
  return out
end
```

A file has three parts. `plugin` and `build` are required:

| | |
|---|---|
| `plugin { }` | metadata. `id` (reverse-DNS), `name` and `version` are required; `api` defaults to the current schema version. `version`, `author` and `about` are shown on the plug-in's card under Preferences → Plug-ins |
| `param.xxx { }` | zero or more controls. Each is declared once and referenced by both the `ui` block and the graph |
| `function build(g, ctx)` | the effect, as a graph of primitives. Returns one node per channel, indexed from 0; `ctx` carries `ctx.channels` and `ctx.sampleRate` |

Omit the parameters and you get an on/off effect. Do not declare a power or
bypass parameter: the host draws the on/off switch on the effect's card and
fades the rack through the dry signal when it is flipped, so switching cannot
click.

## Lua describes, Kotlin runs

The script is executed off the audio thread, on a worker, once for each audio
format: a graph is built for one sample rate and channel count, so a stream in a
new format runs the script again. It returns a parameter list and a graph of
primitives; `:core:dsp` checks that graph and lowers it onto a flat program — a
set of integer instruction arrays over one float bus — and runs that. No Lua
executes while audio is playing.

Lua is too slow to process samples. The figures below are one-time measurements
of LuaJ 3.0.1 on a Pixel 9 Pro, at 44.1 kHz stereo. The design note,
[`docs/dsp-plugins.md`](https://github.com/mattijsf/andamp/blob/main/docs/dsp-plugins.md),
describes the design in full.

| | |
|---|---|
| Two biquads and a gain, in Kotlin | 3104× real time |
| The same two biquads, in Lua | 7.9–8.3× real time |
| A ten-band EQ, in Lua | 2.5–3.6× real time |
| A Freeverb-shaped reverb, in Lua | 1.7× real time, with a 29.5 ms 99th-percentile buffer |

Two of the causes are properties of LuaJ. It boxes every number as a
`LuaDouble`, which measured 24 bytes of heap per arithmetic operation on a
desktop JVM: a ten-band EQ produces 194 MB/s of garbage on the audio path. And
the watchdog a host for other people's scripts needs is a LuaJ debug hook.
Loading `DebugLib` into the globals slows that same EQ from 3.59× to 1.96× real
time, and with a 15 ms budget per buffer a Lua reverb was stopped on 136 of 500
buffers.

For an author this means that `build` may loop, branch and compute freely,
because it runs only when a graph is built. An effect that cannot be expressed
with the primitives in the catalog needs a new primitive in `:core:dsp`.

## The sandbox

`Sandbox` builds the globals by adding rather than by removing: the base
library, `string`, `table`, `math` and `bit32`, plus the plug-in functions and
the graph builder. These are absent — `io`, `os`, `require`, `package`, `debug`,
`load`, `loadstring`, `loadfile`, `dofile`, `rawget`, `rawset`, `rawequal`,
`rawlen`, `setmetatable`, `getmetatable`, `collectgarbage`, `coroutine`,
`newproxy` and `_G` — so there is no file system, no clock, no way to load
further code, and no metatable reflection to escape through.

`PluginLoader` bounds the load with two budgets: **256 KiB of source** and
**3 s to finish**. The deadline is there to stop a script that hangs. The graph
it returns may have at most 512 nodes and 262,144 samples of delay memory.

Breaking a budget, or returning a graph that does not check, is a rejection with
the reason shown in a dialog; nothing is stored. Adding a plug-in also builds it
at 44.1, 48 and 22.05 kHz, and refuses it if any of them fails, so a plug-in
that installs is one that runs. The reference lists the errors.

## Examples

In [`docs/examples`](https://github.com/mattijsf/andamp/tree/main/docs/examples):

| | |
|---|---|
| [`gain.lua`](examples/gain.lua) | one slider, one node |
| [`warmth.lua`](examples/warmth.lua) | a low shelf with a toggle. The toggle reaches the graph as 0 or 1 and is smoothed, so the bypass cannot click |
| [`shaped.lua`](examples/shaped.lua) | a `ui` block: two titled groups, the second switched by a toggle in its header |
| [`tremolo.lua`](examples/tremolo.lua) | an LFO on the volume: a stateful primitive, and a rate in hertz that moves it while it runs |
| [`vinyl.lua`](examples/vinyl.lua) | a record on a worn turntable: seeded noise as hiss, crackle, a scratch and rumble, over a wobbling delay, filters and presets |
| [`refused/broken.lua`](examples/refused/broken.lua) | no `plugin` block. Must be rejected with a reason, storing nothing |
| [`refused/runaway.lua`](examples/refused/runaway.lua) | loops forever at load. Must hit the 3 s deadline rather than freeze the app |

The two under `refused/` exercise the rejection paths, and are worth running
against a build before trusting its error reporting. The plug-ins Andamp ships
are written the same way, in `backend/media3/src/main/resources/plugins/`.

## Distribution

Host the `.lua` file yourself. The listener picks it out of their downloads, or
opens or shares it to Andamp from another app. There is nothing to sign or
register. Keep the `id` stable across versions — settings are filed under it, so
a changed `id` is a different plug-in and loses the listener's tuning. Raise the
`version` with each release: adding a file whose `id` is already installed asks
the listener before replacing it, and names both versions.
