# Writing a music source for Andamp

Andamp plays the files on the phone. Anything else, such as a streaming service or a
server on the listener's network, is a **source**: a separate Android app that Andamp
binds and drives over AIDL. The code calls a source a pack (`PackDescriptor`,
`PackServiceBase`). This page describes what a source has to implement.

- Andamp finds sources by querying Android for apps that answer a bind action. There is
  no registration, no list of approved authors and no key to request.
- Every installed app that answers the action is its own source, with its own row in
  Preferences and its own entry in Media Library. A queue can mix rows from several
  sources and from the phone.
- Two sources for the same service work side by side when they have different application
  ids.
- When a source is uninstalled, its rows stay in the playlist and are marked as missing.

## What a source is

| Part | Required | Purpose |
|---|---|---|
| A bound service answering `nl.mattix.andamp.source.BIND` | yes | The contract: playback, the library, the account and the audio |
| An activity answering `nl.mattix.andamp.source.SETTINGS` | no | Your own settings screen, opened from Andamp's Preferences. The place to sign in, if the source has an account |
| An `update.json` on the web | no | Lets Andamp tell the listener that a newer version exists |

Both components must be `exported`, because another app binds and starts them. They run in
your app's process. Andamp loads none of your code.

```xml
<service
    android:name=".PackService"
    android:exported="true">
    <intent-filter>
        <action android:name="nl.mattix.andamp.source.BIND" />
    </intent-filter>
</service>

<activity
    android:name=".SettingsActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="nl.mattix.andamp.source.SETTINGS" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

### Permissions

A source that reaches a network declares `android.permission.INTERNET` in its own
manifest. `source-common` depends on `nl.mattix.andamp:network`, whose manifest adds
`ACCESS_NETWORK_STATE`, so a source built on `source-common` ends up with both. A source
needs no foreground service and no notification permission; see
[What Andamp does](#what-andamp-does).

### The launcher icon

Put the `MAIN`/`LAUNCHER` filter on an `<activity-alias>` that targets your settings
activity and is declared `android:enabled="false"`. Enable the alias from code.

The reason is an Android rule. From Android 10 on, when an app requests any permission and
its manifest declares a launcher activity as enabled, disabling that activity at run time
does not remove the icon: launchers show an icon that opens the app's system info page
instead. An alias that the manifest declares disabled can be turned on and off freely.

`PackLauncherEntry` in `source-common` does this. `PackServiceBase` calls its `restore()`
in `onCreate` with the alias name you give in `launcherAlias`. The icon is shown unless
the listener hid it, and the choice is kept in the source's own preferences.

### Application id and signing key

Pick an application id that no other app uses, and keep it. Two apps with the same id
cannot be installed together, so a fork that keeps the original's id cannot be installed
beside it. Sign with your own key and keep the key: Android only installs an update that
carries the same signature as the installed version.

## The contract

The contract is one AIDL interface (`IMusicSourcePack`), one callback interface
(`IPackListener`) and the parcelable types in `Wire.kt`. It is published on Maven Central:

```kotlin
implementation("nl.mattix.andamp:source-api:0.1.0")     // the contract (:core:packapi)
implementation("nl.mattix.andamp:source-common:0.1.0")  // the service side shared by every source (:pack:common)
```

Most sources depend on `source-common` and extend `PackServiceBase`. You can also copy the
`aidl` directory and the classes in `Wire.kt` into your project and implement the service
yourself. In that case:

- The package must stay `nl.mattix.andamp.core.packapi`, because it is part of the
  interface descriptor that both ends compare.
- A copied parcel must have the same fields in the same order as `Wire.kt`. Parcels are
  read by position.

With the repository checked out, `./gradlew apiDocs` renders the KDoc of the contract and
of the player's side into `build/api-docs`.

```
IMusicSourcePack
  int apiVersion()                     the contract version; 2 today (PackApi.PACK_API)
  PackDescriptor describe()            who you are and what you can do
  PackAccount account()                whether this phone holds an account
  void listen(IPackListener)           register for state and account changes
  oneway setQueue/play/pause/stop/…    the transport
  PackAnswer ask(PackQuestion)         the library, one page at a time
  ParcelFileDescriptor openAudio()     the decoded audio, as a pipe

IPackListener (oneway)
  void onState(PackState)              playback state, whenever it changes
  void onAccount(PackAccount)          somebody signed in or out
```

### Versions

Andamp calls `apiVersion()` first. If the number differs from its own `PACK_API`, it asks
nothing else of the source. Preferences show it as too old for this version of Andamp,
with a button that opens the page listing sources.

Parcels are positional and carry no field names. Adding, removing or reordering a field in
any parcel changes the bytes, so every such change needs a new `PACK_API`, and a source
and a player only work together when both are built against the same version. Enum values
cross as names (`PackState.transport`, `PackAlbum.kind`); a name the reader does not know
falls back to a default.

### Calls

The transport calls are `oneway`: they return at once, and the result arrives on
`IPackListener.onState`. `apiVersion`, `describe`, `account` and `ask` block until you
answer. They arrive on a binder thread in your process.

`PackServiceBase` runs every transport call on the main thread, in the order sent. `ask`
is answered on the binder thread and may block there.

### The queue

The source owns the queue. Andamp sends rows with `setQueue` and `enqueue` and then sends
transport calls. What plays next, what shuffle and repeat do, and where the cursor is are
decided by the source and reported in `PackState`. Andamp draws what `PackState` says.

### Audio

`openAudio` returns the read end of a pipe. Write raw PCM into the other end: 44.1 kHz,
stereo, 16-bit, four bytes a frame, with no framing around it. That is the only format the
player renders (`PcmProvider.SAMPLE_RATE_HZ`, `CHANNELS`, `BYTES_PER_FRAME`).

On a seek, a change of track or a stop, close your end of the pipe. Everything already
written is discarded with it. Andamp reads end-of-stream, flushes its own chain and calls
`openAudio` again for a pipe that starts at the new position. The stream has no markers
and no counters.

A write blocks while the player is not reading. That is the back-pressure that keeps the
decoder from running ahead of what is heard.

A source that plays its audio somewhere else, such as on another device, sets
`handsOverAudio = false`. Andamp then does not call `openAudio`.

`PackAudioOut` in `source-common` implements the source's end of the pipe.

### The library

`ask` answers one page of one question (`PackQuestion.kind`: artists, albums, tracks,
playlists, playlist tracks, search, find artists). A page holds at most
`PackQuestion.PAGE` (500) items; set `PackAnswer.more` when there are more.

Return `PackAnswer(failed = true)` when you cannot answer, for example because the
connection is down. An answer with empty lists means there is nothing there, and Andamp
shows it as an empty library.

### The account

Andamp learns about the account in two ways, and a source implements both:

- It calls `account()` when it binds and whenever it needs to know again. Answer from what
  is stored on the phone, without contacting the server, because the call blocks.
- The source calls `IPackListener.onAccount` on every registered listener when somebody
  signs in or out on its settings screen. With `PackServiceBase`, the settings screen
  calls `PackServiceBase.signedIn(context, service)` or `signedOut(context, service)`,
  and the service tells the listeners.

## What Andamp does

- It renders the source's audio through its own chain: the equalizer, balance, the effect
  rack, volume, and the tap the visualizers read. This is the same chain a file on the
  phone goes through.
- It draws the source's rows in the same playlist as the phone's music and plays a queue
  mixed from both.
- It holds the media session, the notification and the foreground service while anything
  plays.
- It binds the source with `BIND_INCLUDE_CAPABILITIES`, which keeps the source's process
  from being frozen while it decodes.

A source therefore needs no foreground service and no notification of its own.

## Identity

Andamp binds whatever source the listener installed. It has no pinned key and no list of
approved authors.

The source's page in Preferences shows the app's label, its package name and the first
four bytes of the SHA-256 of its signing certificate. A source that was replaced by one
from a different author shows a different fingerprint. Android requires an update to be
signed with the same key as the installed version, so only the holder of your key can
publish an update to your source.

Your service answers any app that binds it. If your source holds something that another
app on the phone should not use, such as an account with a subscription, check the caller
yourself, for example by asking the listener once for each calling package. The Subsonic
and Jellyfin sources do not check the caller. Their credentials are not sent over the
contract.

## The descriptor

```kotlin
PackDescriptor(
    scheme = "example",        // your rows are example:track:…; also your source's id
    label = "Example",         // shown in menus and Preferences
    version = "1.0.0",         // your own version, in the form your update.json uses
    canSeek = true,
    canEditQueue = true,
    canSearch = true,
    hasPlaylists = true,
    hasCatalogue = true,
    skinnable = true,          // whether a skin may be chosen for your tracks
    updates = "https://…/update.json",
    home = "https://…",        // where a listener gets your source
    handsOverAudio = true,     // false for a source that plays somewhere else
)
```

Set the capability fields to what the source can do.

**`scheme`.** A row's address starts with the scheme, and the scheme routes the row to
your source: `example:track:123` goes to the source whose scheme is `example`. A phone
without that source shows the row as `[Missing source: Example]` and keeps it. Saved
playlists record the scheme, so do not change it between versions: rows saved under the
old scheme would no longer reach your source. The scheme may be written with or without a
trailing colon.

**`home`.** Andamp writes `home` into a saved playlist beside the rows. A phone without
your source, or a person who was given the playlist, can then be sent to the page where
the source is available.

**First install.** Andamp stores each source's scheme and label after it has reached the
source. On the first launch after installing, the source appears once the first binding
has completed. On later launches it is drawn from the stored values at once. A source that
never appears has not answered `describe()`.

## Updating and uninstalling

Publish an `update.json` and put its URL in `updates`:

```json
{ "version": "1.1.0", "page": "https://example.org/source/download" }
```

Andamp's Preferences then tell the listener that a newer version exists and open `page` in
the browser. Andamp does not download or install anything.

Test uninstalling your source. Andamp must keep the rows, mark them as missing and go on
playing everything else.

## Publishing

A source is an ordinary Android app. You can hand out the APK from your own page, put it
on F-Droid or publish it on Google Play. The contract does not depend on how it is
installed.

## Sources to read

Two complete sources, each in its own repository. Both connect to a server on the
listener's network and stream its files over HTTP.

- [andamp-source-subsonic](https://github.com/mattijsf/andamp-source-subsonic)
- [andamp-source-jellyfin](https://github.com/mattijsf/andamp-source-jellyfin)

Read their `PackService`, which extends `PackServiceBase`, for the service side, and
`:backend:pack` in this repository for the player's side.

Playback for such a source is `StreamPlayback` in `source-common`. The source supplies a
`locate` function that returns a `StreamRequest` for a row: the URL, the headers to send
with it, and whether the response can be sought in.

Set `seekable` correctly:

- A file the server sends unchanged answers byte ranges. Leave `seekable = true`; the
  decoder seeks inside the open response.
- A stream the server transcodes while sending is a 200 response without ranges, and
  `MediaExtractor` cannot seek in it. Set `seekable = false`, and put the position
  `locate` is given into the request as the server's own offset (`timeOffset` for
  Subsonic, `StartTimeTicks` for Jellyfin). A seek then opens the row again at the new
  position.

A transcode marked `seekable = true` keeps playing from where it was while the seek bar
shows the new position.

The other kind of source has a decoder of its own. It implements `PcmProvider` and hands
its samples to `PackAudioOut`.

[andamp-source-template](https://github.com/mattijsf/andamp-source-template) is the
smallest source that builds, and a starting point for a new one.
