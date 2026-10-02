# Andamp — Privacy Policy

*Last updated 2 October 2026. Applies to Andamp for Android (`nl.mattix.andamp`) and to the music source apps published for it.*

Andamp is a music player that runs on your phone. It has no account of its own and no
server that collects anything. **Nothing about you or what you listen to is sent to the
developer.** The only thing the app ever fetches from the developer's website is a file
you asked for or a version number, described below, and neither request carries anything
about you.

The rest of this document gives the details, and lists which other computers the app
connects to and when.

## What the developer receives

Nothing. There is no analytics, no crash reporting, no advertising, no tracking, and no
third-party SDK that does any of those things. The app contains no code that reports usage
anywhere.

## What leaves your phone, and when

**1. The skin museum.** Opening *Skins → Get more skins!* asks
[`skins.webamp.org`](https://skins.webamp.org) for pages of the catalog and downloads
previews and skin files from `r2.webampskins.org`. If you type in the search box, what you
typed is part of that request. Those servers are run by the Webamp project, not by the
developer of Andamp; like any web request, they can see the IP address the request came
from. Andamp adds no identifier of its own to it. Close the browser and the app stops
asking.

**2. Internet radio.** A station is a stream address you entered yourself. Playing it means
connecting to that address, so whoever runs the station can see the connection, the same as
any other player or browser would show them. Andamp sends nothing besides the request for
the stream, which asks the station to include the titles of the songs it plays.

**3. Installing an effect plug-in from mattix.nl.** Tapping *Install* on
[mattix.nl/andamp/extensions/plugins](https://mattix.nl/andamp/extensions/plugins) opens
Andamp, which downloads that one `.lua` file from `mattix.nl` over https and shows you what
it is before anything is added. The request carries no identifier. Like any website, the
site's hosting provider sees the IP address a request comes from.

**4. Update checks for music sources.** When you open the page of an installed music source
in *Preferences → Music sources*, Andamp fetches a small file that holds that source's
latest version number, to tell you if a newer one exists. The address is the one the source
names: `mattix.nl` for the sources published with Andamp, the author's own site for any
other. It carries no
identifier. Andamp never downloads or installs an update itself; it only offers a link.

**5. Cover art for music sources.** When a music source is playing, Andamp loads the cover
art that source points it to, from the service or server that source is connected to.

**6. Links you tap.** The mattix.nl link in the about box, *More effects*, *More sources*, a
source's download page, and the source links on the open-source licenses page hand the
address to your browser. What happens next is between you and your browser.

That is the complete list.

## What stays on your phone

Kept in the app's own private storage, which no other app can read and which Android
deletes when you uninstall Andamp:

- your playlist, the track you were on and where you were in it;
- equalizer settings, effects and their values, and which visualizer you were using;
- where you dragged each window and which ones were open;
- bookmarks and any playlists you saved;
- skins, `.lua` effect plug-ins and visualizer preset packs you installed;
- a cache of skin-museum thumbnails, so scrolling back is instant.

**Your music never leaves the device.** Titles, artists, album art and file paths are read
to show them and to remember your place, and are stored only in that private storage.

**Your phone's backup.** If you have backup turned on in Android, it includes Andamp's
settings and playlists like those of any other app, so they come back on a new phone. That
backup is Android's, stored in your own Google account and end-to-end encrypted where your
phone supports it; the developer has no access to it. The thumbnail cache is never part of
it. You can switch backup off in Android's settings.

## Music sources

A music source is a separate app you install yourself, only if you want it, that brings
music from a streaming service or from a server of your own into Andamp. Each one:

- connects only to the service or server you connect it to, and hands the music to Andamp
  on the phone; it sends nothing to the developer;
- keeps what it needs to stay connected - the server address, your user name, and a sign-in
  token or, where the service's protocol requires it, your password - in its own private
  storage, excluded from Android's backup;
- forgets your sign-in when you sign out, and Android deletes everything it kept when you
  uninstall it.

What a streaming service does with your listening is covered by that service's own privacy
policy.

## Permissions, and what each is for

| Permission | Why |
| --- | --- |
| Music and audio (`READ_MEDIA_AUDIO`; on Android 12 and older, storage `READ_EXTERNAL_STORAGE`) | To list and play the music already on your phone. Decline it and the app still plays files you pick by hand. |
| Display over other apps (`SYSTEM_ALERT_WINDOW`) | Only for the floating player, which is off until you switch it on. It draws the player over other apps; it does not read them. |
| Internet | Everything listed under *What leaves your phone*. |
| Network state and Wi-Fi state | To notice when the connection comes back, so a radio stream can reconnect, and to keep Wi-Fi awake while a stream plays. |
| Keep awake (`WAKE_LOCK`) | So the phone does not sleep in the middle of a track. |
| Foreground service (media playback) | So playback continues, with a notification, when the app is not on screen. |
| Notifications (`POST_NOTIFICATIONS`) | Declared, but never asked for. The playback controls in the notification shade and on the lock screen work without it. |

Music source apps ask for Internet and network state only.

## Children

Andamp is not directed at children and asks for no personal information from anyone.

## Content from other people

The skin museum shows images uploaded by other people. Skins the museum has flagged as
not-safe-for-work are never shown; their images are not even loaded. The developer does not
host or moderate that catalog.

## Your rights

Under the GDPR you may ask a controller for access to your personal data, its correction or
its erasure. In this case there is nothing to ask for: the developer holds no data about
you. The data on your phone is yours, and uninstalling the app removes it; a backup you made
stays in your Google account until you delete it.

If you contacted the developer by email, that email is held for as long as it takes to
answer you, and you can ask for it to be deleted.

## Changes

If this policy changes, the date at the top changes with it, and the previous wording stays
in the app's public git history.

## Contact

[mattijs@mattix.nl](mailto:mattijs@mattix.nl)

---

*Andamp is an independent replica of the Winamp 2.8 interface. It is not affiliated with,
endorsed by, or connected to Winamp, Nullsoft, or Llama Group.*
