# Permissions

Why CreoleCast asks for what it asks for. The notification-access one alarms people, so it
gets most of this page.

## The shape of the problem

CreoleCast is not a media app. It is a **system-wide capture tool**: `MediaProjection` hands it
an anonymous PCM stream mixed from whatever happens to be playing, and it re-streams that over
AirPlay, DLNA, Snapcast or its own protocol.

Android has no first-class model for that. The sanctioned model is "an app casts *its own*
content", as with the Google Cast SDK — where the app owns the media and owns the
`MediaSession`, so metadata and transport controls come along for free.

CreoleCast owns neither. Spotify owns the session. That single fact explains nearly every
awkward corner of this app, including the permission below.

## `BIND_NOTIFICATION_LISTENER_SERVICE`

**Optional.** Casting works without it. You lose metadata, artwork and remote control.

### What it is actually for

Not reading notifications. It is the key that unlocks `MediaSessionManager.getActiveSessions()`,
which requires the `ComponentName` of an *enabled* `NotificationListenerService`. There is no
other route: reading another app's media session is gated on this and nothing else.

From that session CreoleCast gets:

- track title, artist, album and artwork, forwarded to the receiver
- playback position, for progress reporting
- a `MediaController` whose `transportControls` let a receiver's play/pause/skip buttons act on
  the app that is actually playing

Without it, a HomePod shows a blank tile and its buttons do nothing.

### What it does *not* read

`MediaNotificationListener` inspects exactly one field of any notification:

```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (sbn?.notification?.category == "transport") {
        handleMediaSessionsChanged()   // re-queries sessions; sbn discarded
    }
}
```

`category == "transport"` means "this is a media notification". It is used purely as a hint to
re-query the session list. Title, text, extras and sender are never touched.
`onNotificationRemoved` is identical.

The permission technically grants far more than that — it would allow reading every
notification on the device, message contents included. CreoleCast does not, and the listener is
~290 lines you can audit in one sitting.

### Why MediaRoute2 does not remove the need

A reasonable assumption is that registering as a media route would bundle everything: stream
out, metadata out, controls in. It does not.

`MediaRoute2ProviderService` is a **control plane, not a data plane**. It carries "here are
routes", "the user picked one", "set volume to 12", "session ended". It carries no audio and no
metadata, and the actual streaming is entirely the app's own problem.

So the pieces split across unrelated mechanisms:

| Want | Mechanism | Needs |
|---|---|---|
| Audio out | MediaProjection + our own RTSP/RTP | screen-capture consent |
| Metadata out | `MediaSessionManager.getActiveSessions` | **notification listener** |
| Control in | `MediaController.transportControls` | **notification listener** |
| Route and volume UI | MediaRoute2 | nothing extra |

This holds even if CreoleCast *is* the route the media app plays through. SystemUI can read
that session without notification access only because it uses `MediaRouter2Manager`, which is
`@SystemApi` and platform-signed.

### One non-obvious consequence

Some receivers — the Music Assistant AriaCast Receiver, for one — only begin playback after
being told playback has *started*, and that signal is derived from the media session. Without
notification access the cast can look connected and audio can be flowing while nothing plays.
This is why the in-app dialog pushes harder than "it's just for track info".

## Everything else

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | MediaProjection audio capture is gated on it, even though no microphone is used |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` | capture must run in a foreground service with the mediaProjection type |
| `POST_NOTIFICATIONS` | the ongoing cast notification, which is also how you stop a cast |
| `INTERNET` | streaming to receivers |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | local-network reachability checks |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS/Bonjour discovery needs a multicast lock |
| `WAKE_LOCK` | a `PARTIAL_WAKE_LOCK` held for the session so streaming survives screen-off |

No location permission is requested. Discovery is mDNS only.

## If Android blocks the toggle

Sideloaded and F-Droid installs hit Android's "restricted setting" guard: the notification
access switch appears greyed out with *"This setting is currently unavailable"*.

Settings → Apps → CreoleCast → ⋮ (top right) → **Allow restricted settings**, then enable it
again. This is an Android measure against sideloaded apps grabbing sensitive access, not
something the app can work around.
