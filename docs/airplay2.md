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

Verified against doubletake's test receiver and/or shairport-sync (see Testing):

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
- PTP media clock (`MediaClock`, port of `mirror.go` `mediaClock`): follows a receiver that
  publishes `timingPeerInfo.ClockID` by anchoring on the SETUP reply's
  `X-Apple-RequestReceivedTimestamp` + `X-Apple-ProcessingTime`. It re-anchors on stream
  SETUP and every `/feedback` reply, never moving backwards, and falls back to the local
  clock when those headers are absent. 0xd7 sync packets are stamped from it.
- PTP master (`PtpMaster`, not in the reference; owntone's approach): for receivers that
  publish no `ClockID` and follow the sender instead (shairport-sync + nqptp). It sends unicast
  Announce every 250 ms and Sync + Follow_Up every 125 ms from UDP 319/320, with clock identity
  = EUI-64 of our device ID. `connect()` waits ~0.9 s for the receiver to lock on before the
  first anchor, otherwise the start of the stream is dropped as out of date.
- Realtime ALAC is sent at 44100 Hz / 352 frames, as `audioFormat` 0x40000 requires; the
  recorder and `AirPlay2Client` share `AudioCastService.captureSampleRate`
- Both audio stream descriptor layouts (`streamConnections` vs `controlPort`)
- ALAC verbatim frames, byte-identical to the reference encoder
- Per-packet audio encryption with the published `shk`

## Testing

Unit tests cover the pure parts (`SRP6aClientTest`, `TlvUtilTest`, `BinaryPlistTest`,
`AirPlay2CryptoTest`, `MediaClockTest`). The protocol itself is only meaningfully testable
against a receiver. The `airplay2` package needs only `android.util.Log` (and
`AndroidCredentialStore.kt`), so it runs on the desktop JVM with a small `Log` shim.

### shairport-sync (end-to-end, sender as PTP master)

Requires shairport-sync built with AirPlay 2 support, plus nqptp. Both the receiver's nqptp
and our `PtpMaster` need UDP 319/320, so they can't share a network namespace. Use rootless
namespaces:

- **Receiver namespace** (`unshare --map-root-user --map-users=1:100000:65535
  --map-groups=1:100000:65535 -nm`; the full uid map lets avahi `chown` its runtime dir):
  mount tmpfs on `/dev/shm` and `/run`, start a private `dbus-daemon` (permissive config
  listening on `/run/dbus/system_bus_socket`), `avahi-daemon --no-drop-root --no-chroot`,
  `nqptp`, then `shairport-sync` with the `pipe` backend. shairport-sync aborts if mDNS
  registration fails.
- **Client namespace**: a nested `unshare -n` joined by a veth pair, e.g. receiver 10.9.0.2,
  client 10.9.0.1. Run the JVM client there. Stop the host Gradle daemon first: its cache-lock
  handshake can't cross network namespaces.

The pipe backend writes S32_LE stereo at 48 kHz. Verified: a 10 s 1 kHz tone arrives complete
(9.993 s audible against 9.993 s sent), at the correct pitch, with no dropped packets and no
discontinuities.

### doubletake test receiver

Build the reference receiver:

```sh
git clone --depth 1 https://github.com/omarroth/doubletake /tmp/doubletake
cd /tmp/doubletake && go build -o /tmp/ap2-receiver ./cmd/doubletake-test-receiver
/tmp/ap2-receiver -listen 127.0.0.1:7010 -profile modern -auth pin -code 1234 -debug
```

Profiles: `modern`, `roku`, `lg`, `appletv3`, `uxplay`, `airserver`.
Auth modes: `none`, `pin`, `password`, `digest`, `combined`.

Then drive `AirPlay2Client.connect()` at the listener with a `Log` shim on the classpath.
Reference vectors worth re-checking after any change to the audio path:

| Check | Expected |
|---|---|
| ALAC frame, 352 samples 440 Hz stereo | 1416 bytes, SHA-256 `b2020989bbc304b2b5d38188fc40ce4a436d7de4f911a71a4dd1591d13a18c5e` |
| ALAC header prefix | `200012000002c0` |

`cd /tmp/doubletake && go test ./internal/airplay/ -run TestALACVerbatim -v` prints the
reference side.

### Known verification gaps

- **No real Apple hardware has been tested.** Following a receiver-owned PTP clock
  (`MediaClock.configureFromSetup`) has been checked only against doubletake's SETUP reply
  (ClockID + clock headers parsed), not with audio playing.
- `RECORD`, `SET_PARAMETER` and `POST /feedback` return RTSP 455 against the doubletake test
  receiver. That is a harness limitation, not a client bug: its session state machine only
  reaches `ready` after a *video* SETUP, which an audio-only sender never sends.
- shairport-sync ignores our metadata: it answers the binary-plist `SET_PARAMETER` with
  "unknown Content-Type". It warns that our 85 ms stream latency (`LATENCY_SAMPLES`, from the
  reference) is shorter than its default 1 s pipe-backend buffer; playback was unaffected.
- `PtpMaster` needs UDP 319/320. Unprivileged Android apps may bind them only from Android 13
  with an updated connectivity mainline module and kernel ≥ 5.15 (issuetracker 218578943).
  Elsewhere, casting to a receiver without a `ClockID` fails at SETUP with a logged reason.

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
