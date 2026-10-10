# AirPlay 2 — implementation notes

Where the sender stands, how to test it, and what is deliberately missing.

Code lives in `app/src/main/java/com/creolecast/app/airplay2/`.

## Reference implementation

The protocol is transcribed from **[omarroth/doubletake](https://github.com/omarroth/doubletake)**
(`internal/airplay`, LGPL-3), the Go library the F-Droid app
**[Mirror](https://f-droid.org/packages/io.github.jqssun.displaymirror/)** vendors as a submodule.
This app is GPL-3, so transcription is permitted; keep attributing it in comments.

We do **not** link against it, and should not: it is Go (gomobile/JNI, ~10 MB `.so` per ABI,
F-Droid reproducibility pain), has no released API, and is a *screen-mirroring* sender whose
audio path is tied to a video stream. We took ~1,400 of its ~17,000 lines.

Deliberate divergences for an audio-only sender: we omit `isScreenMirroringSession`,
`usingScreen` and `redundantAudio`, and send `isMedia = true` where the reference sends
`false` (our audio *is* the session's main media, not a mirroring side channel).

## What works

Verified against doubletake's own test receiver:

- HomeKit PIN pair-setup (HKP 5): full M1–M6 followed by pair-verify
- Transient pair-setup (HKP 4) **diverges from the reference**, and is verified against
  shairport-sync rather than doubletake: SRP password `3939`, a 1-byte `0x10` flags TLV, and
  it ends at M4, keying the control channel from the SRP session key. This matches the HAP
  encoding and owntone's client (`pair_ap`). doubletake sends an empty password and a 4-byte
  flag, then runs M5/M6 and pair-verify; its test receiver accepts only that variant, so it
  now rejects our transient setup.
- Persistent Ed25519 pairing identity per receiver DeviceID; reconnect needs only pair-verify
- HAP pair-verify with an enforced M2 server signature
- ChaCha20-Poly1305 framed RTSP control channel
- FairPlay SAP (`/fp-setup`), skipped when feature bit 14 is absent
- PTP/NTP timing negotiation, including an NTP timing responder
- Both audio stream descriptor layouts (`streamConnections` vs `controlPort`)
- ALAC verbatim frames, byte-identical to the reference encoder
- Per-packet audio encryption with the published `shk`

## Testing

There is no unit-test suite for this; the protocol is only meaningfully testable against a
receiver. The `airplay2` package is deliberately pure JVM (no `android.*` imports outside
`AndroidCredentialStore.kt`) so it can run on the desktop.

Build the reference receiver:

```sh
git clone --depth 1 https://github.com/omarroth/doubletake /tmp/doubletake
cd /tmp/doubletake && go build -o /tmp/ap2-receiver ./cmd/doubletake-test-receiver
/tmp/ap2-receiver -listen 127.0.0.1:7010 -profile modern -auth pin -code 1234 -debug
```

Profiles: `modern`, `roku`, `lg`, `appletv3`, `uxplay`, `airserver`.
Auth modes: `none`, `pin`, `password`, `digest`, `combined`.

Then compile the `airplay2` sources with a standalone `kotlinc`, BouncyCastle, and a small
`android.util.Log` / `android.util.Base64` shim, and drive `AirPlay2Client.connect()` at the
listener. Reference vectors worth re-checking after any change to the audio path:

| Check | Expected |
|---|---|
| ALAC frame, 352 samples 440 Hz stereo | 1416 bytes, SHA-256 `b2020989bbc304b2b5d38188fc40ce4a436d7de4f911a71a4dd1591d13a18c5e` |
| ALAC header prefix | `200012000002c0` |

`cd /tmp/doubletake && go test ./internal/airplay/ -run TestALACVerbatim -v` prints the
reference side.

### Known verification gaps

- **No real Apple hardware has been tested.** Everything below is inference from the reference.
- `SET_PARAMETER` and `POST /feedback` return RTSP 455 against the test receiver. That is a
  harness limitation, not a client bug: its session state machine only reaches `ready` after a
  *video* SETUP, which an audio-only sender never sends. Volume and keepalive are therefore
  unproven.
- **shairport-sync (AirPlay 2 build, 5.0.x)**: transient pair-setup, encrypted control
  channel and FairPlay SAP pass. The session then stops at stream SETUP: shairport-sync only
  accepts PTP timing and expects the *sender* to be the PTP master that nqptp follows. Its
  `timingPeerInfo` has no `ClockID`, and we run no PTP master, so `connect()` fails with
  "PTP SETUP response omitted timingPeerInfo.ClockID". NTP streams are rejected outright
  (`rtsp.c`: "Shairport Sync can not handle NTP streams").

## Backlog

Not implemented. Each entry names the reference file to transcribe from. None is required for
HomePod / Apple TV / AirPort Express, which are the devices we care about.

### Raw (legacy) binary pairing
`pairing.go` — `rawPairSetup`, `completeRawSetupAndVerify`, AES-128-CTR.

Roku- and LG-class receivers reject our HAP pair-setup with HTTP 500 and expect the original
binary pairing protocol instead. Note the key derivation there is plain
`SHA-512("Pair-Verify-AES-Key" || shared)[:16]`, **not** HKDF, and the control channel stays
plaintext afterwards. Probably not worth it: the existing AirPlay 1 (RAOP) path already covers
those devices.

### HTTP Digest auth
`digest.go` — RFC 2069 MD5, username always `"AirPlay"`, ~100 lines.

For receivers that protect playback with a password rather than a pairing PIN. A 401 with a
`WWW-Authenticate: Digest` challenge is currently a hard failure.

### AAC-ELD audio
`audio.go` — `ct=8`, `spf=480`, `audioFormat=0x1000000`.

We send uncompressed ALAC: 1416 bytes every 8 ms, roughly 1.4 Mbit/s. AAC-ELD would cut that
substantially. Needs an encoder — `MediaCodec` should manage it on Android. Note the reference
forbids `redundantAudio` with AAC-ELD.

### Audio FEC / retransmission
`audio.go` — `redundantAudio: 2`, retransmit request PT `0xd5`, response PT `0xd6`, 8-frame
depth.

Packet-loss resilience on congested WiFi. Only offered for ALAC without ChaCha encryption in
the reference, so check the interaction with our encrypted path before adopting it.

### Encrypted event channel
`event_channel.go`.

We connect the event channel and parse volume and transport commands from it, but never
enable HAP framing on that socket after pair-verify. Receivers that encrypt it will just look
like garbage and get dropped — harmless today, but it means remote volume and play/pause from
the speaker may silently not work. This is the most likely of these to actually bite a user.

### PTP media clock (receiver timeline)
`mirror.go` — `mediaClock`: `configureFromSetup`, `reanchor`, `configureFromLocalClock`.

Not ported, and it's the main timing gap. The reference maps local monotonic time onto the
receiver's PTP timeline. It anchors on the SETUP response's `X-Apple-RequestReceivedTimestamp`
plus `X-Apple-ProcessingTime` (the receiver's boot-relative clock), re-anchors from every
`/feedback` response without ever moving backwards, and falls back to the local boot clock when
those headers are missing. Our `sendSyncPacket` instead stamps `System.currentTimeMillis()`
converted to the NTP epoch. That's the wrong timescale for a PTP timeline, so anchors land far
from the receiver's "now".

### PTP master
Not in the reference either: it always needs the receiver's `ClockID`. Receivers that follow the
sender's clock (shairport-sync + nqptp) need the app to run as an IEEE 1588 master
(Announce/Sync/Follow_Up on UDP 319/320). It would then advertise its own `ClockID` in
`timingPeerInfo` and stamp sync packets on that timeline. owntone does this with its own PTP
daemon.
