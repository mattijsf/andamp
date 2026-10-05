# Extend Andamp

Andamp is a Winamp 2.8 replica for Android. It plays the files on the phone. There are
two ways to extend it, and both are open to anyone.

| | |
|---|---|
| **[A music source](source-packs.md)** | a separate app that Andamp binds over AIDL. It answers what to play, what the library holds and who is signed in, and hands over decoded audio through a pipe. Examples: a streaming service, a server on the network |
| **[A DSP plug-in](dsp-plugins-intro.md)** | a `.lua` file the listener adds to the effect rack. It declares controls and a graph of built-in primitives, which Andamp compiles and runs on the audio path |

A source holds an account and decodes audio, so it runs in its own process and its
credential stays there. An effect is arithmetic over samples, so it is a file that can be
passed around like a skin.

## What holds for both

### Found without registration

A source is found by resolving an intent action. A plug-in is a file the listener picks.
There is no key to request, no list of approved authors and nothing to submit. Andamp does
not request `QUERY_ALL_PACKAGES`. Its manifest has `<queries><intent>` entries for the two
actions a source answers, the service it binds and the settings screen it opens, and those
are the only other apps it can see.

### Neither runs inside the player

A source runs in its own process. Andamp binds its service and loads none of its code. A
plug-in's Lua runs when the plug-in is loaded, on a worker thread, in a sandbox with no
file system and no way to load further code. It returns a description of the effect, which
Kotlin runs. No plug-in code runs on the audio thread.

### Identity is shown

A source's page in Preferences names the app, its package and the first four bytes of the
SHA-256 of its signing certificate. Andamp refuses nothing on that basis. The fingerprint
lets a listener see when a source has been replaced by one from a different author.
Android requires an update to carry the signature of the installed version, so a source's
versions are tied together by its author's key.

### Andamp installs nothing

For a source, Andamp reads an `update.json` that you publish, tells the listener that a
newer version exists and opens your page in their browser. The listener installs it.

### The documentation is checked against the code

The source contract is a public AIDL interface with an [API reference](api/index.html)
rendered from its KDoc. The link works in the built site; `./gradlew apiDocs` renders the
reference locally. `CatalogueMatchesSpecTest` reads the primitive catalog from the
plug-in [reference](dsp-plugin-spec.md) and compares it with the primitives `:core:dsp`
accepts.

## Where the code is

A source is written against the SDK, from Maven Central:

```kotlin
implementation("nl.mattix.andamp:source-api:0.1.0")     // the contract
implementation("nl.mattix.andamp:source-common:0.1.0")  // the source side that is the same in every source
```

| | |
|---|---|
| `nl.mattix.andamp:source-api` | the source contract: one AIDL interface, one callback interface, the parcelable types that cross (`core/packapi` in [the Andamp repository](https://github.com/mattijsf/andamp)) |
| `nl.mattix.andamp:source-common` | the source side of the wire that is not about any one source: the audio pipe, the state relay, paged answers, and HTTP stream playback (`pack/common`) |
| `backend/pack` | the player's end of that wire |
| [andamp-source-subsonic](https://github.com/mattijsf/andamp-source-subsonic), [andamp-source-jellyfin](https://github.com/mattijsf/andamp-source-jellyfin), [andamp-source-plex](https://github.com/mattijsf/andamp-source-plex) | complete sources for a server on the network, over its own HTTP API, each with its own settings screen |
| [andamp-source-template](https://github.com/mattijsf/andamp-source-template) | the smallest source that builds |
| `core/plugin` | the plug-in loader, the sandbox and the UI binding |
| `core/dsp` | the graph compiler and the primitives a plug-in may use |
| `docs/examples` | plug-ins that load, and two that must be rejected |
