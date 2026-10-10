# Modernization: Android target SDK (36 now, 37 next)

Status as of 2026-10-10. Verified against the sources linked below, not from memory.

## Current state

| | Value | Where |
|---|---|---|
| `compileSdk` | 37 | `app/build.gradle.kts` |
| `targetSdk` | **36** (raised from 35 in this change) | `app/build.gradle.kts` |
| `minSdk` | 34 | `app/build.gradle.kts` |
| Distribution | GitHub Releases, F-Droid `Binaries:`, IzzyOnDroid | `.github/workflows/release.yml`, `docs/fdroid/com.creolecast.app.yml` |
| Play tooling | `fastlane deploy` → `upload_to_play_store`, `Appfile` has an empty `json_key_file` | `fastlane/` |

None of the shipping channels enforce a target API level. Google Play does:
"Starting August 31, 2026: New apps and app updates must target Android 16 (API
level 36) or higher", with an extension available until November 1, 2026
([Play Console Help 11926878](https://support.google.com/googleplay/android-developer/answer/11926878)).
At `targetSdk = 35` the `deploy` lane could not ship an update.

## Step 1: target 36 (done in this change)

Applicable target-36 changes
([behavior-changes-16](https://developer.android.com/about/versions/16/behavior-changes-16)),
checked against the code:

| Change | Exposure in CreoleCast |
|---|---|
| Predictive back on by default, `onBackPressed`/`KEYCODE_BACK` no longer delivered | None: no `onBackPressed`, `onKeyDown`, `OnBackPressedDispatcher` or `OnBackInvokedCallback` anywhere in `app/src/main` |
| `windowOptOutEdgeToEdgeEnforcement` disabled | None: not used. Edge-to-edge has been enforced since target 35; layouts already use `fitsSystemWindows="true"` |
| Orientation/resizability/aspect-ratio ignored on sw≥600dp | None declared: no `screenOrientation`, `resizeableActivity`, `min/maxAspectRatio`, `setRequestedOrientation` |
| `elegantTextHeight` ignored | Not used |
| `scheduleAtFixedRate` runs at most one missed execution | Not used |
| Safer Intents | Opt-in via `intentMatchingFlags`; not opted in |

Verification: `assembleDebug assembleRelease lintDebug testDebugUnitTest` pass. `aapt2 dump badging`
on the release APK reports `targetSdkVersion:'36'`. Lint reports the same 124 warnings, by issue ID, as
at target 35, and 0 errors. The remaining `OldTargetApi` warning is expected because 37 is the latest.

## Step 2: release tooling (needs a decision)

The `beta` lane is removed in this change. Crashlytics Beta stopped working on 2020-05-04, and fastlane
marks the `crashlytics` action deprecated in favour of Firebase App Distribution
([fastlane docs](https://docs.fastlane.tools/actions/crashlytics/)). The lane's only upload call was
already commented out.

The `deploy` lane is left alone because whether CreoleCast ships on Play is a product decision:

- **Keep Play.** Provision a Play service-account JSON as a CI secret and point
  `fastlane/Appfile` `json_key_file` at it. Also decide how Play signing interacts with
  `release.yml`, which deliberately builds unsigned and signs with `apksigner` so the APK stays
  byte-identical to F-Droid's rebuild. Play App Signing would re-sign. Bundle format requirements
  for this app's Play listing (APK vs AAB) are not verified here.
- **Drop Play.** Delete the `deploy` lane and `fastlane/Appfile`. Keep `fastlane/metadata/`,
  because F-Droid and `release.yml` read changelogs from it. Fastlane's only remaining job would
  then be `lane :test`, which duplicates `test.yml`.

## Step 3: target 37 (the real work)

### What changes

From the [local network permission guide](https://developer.android.com/privacy-and-security/local-network-permission)
and [behavior-changes-17](https://developer.android.com/about/versions/17/behavior-changes-17):

- Apps targeting 37 have **local network access blocked by default** until the user grants
  `ACCESS_LOCAL_NETWORK`, a runtime permission in the `NEARBY_DEVICES` group. Apps targeting
  36 or lower get an implicit grant via `INTERNET`. The guide says: "Don't request
  `ACCESS_LOCAL_NETWORK` at runtime prior to targeting SDK 37".
- The block covers outgoing and incoming TCP, UDP unicast/multicast/broadcast in both directions,
  `.local` resolution and `NsdManager`, across all networking APIs (raw sockets, OkHttp, …).
  Blocked UDP fails with `EPERM`. Blocked TCP typically **times out**.
- An alternative to the permission is a system picker: `DiscoveryRequest.FLAG_SHOW_PICKER` with
  `NsdManager.registerServiceInfoCallback`. Addresses the user picks there can be connected to
  without the permission. The Output Switcher exemption applies to Google Cast senders.
- `ACCESS_LOCAL_NETWORK` and `android.net.nsd.DiscoveryRequest` are both present in the locally
  installed `platforms/android-37.0/android.jar`, so the code can be written against the current
  `compileSdk`.

### Why the picker cannot carry this app alone

Every protocol CreoleCast speaks is LAN traffic, and several paths are not mDNS-from-the-app:

| Path | File | Traffic | Picker-compatible? |
|---|---|---|---|
| NSD discovery and resolve (`_audiocast`, `_airplay`, `_googlecast`, `_snapcast-ctrl`) | `DiscoveryManager.kt` | mDNS via system | Yes, but one service per pick. The picker returns one user-chosen device and cannot populate the server list |
| JmDNS `_raop._tcp` | `raop/RaopDiscovery.kt` | raw multicast from the app | No |
| SSDP M-SEARCH plus device-description HTTP fetch | `DiscoveryManager.kt` | UDP multicast, TCP | No |
| `DISCOVER_AUDIOCAST` broadcast to 255.255.255.255:12888 | `DiscoveryManager.kt` | UDP broadcast | No |
| AirPlay 1/2 RTSP, timing/control/audio UDP, event channel | `AudioCastService.kt`, `airplay2/AirPlay2Client.kt` | TCP and UDP, both directions | Only if the host came from the picker; inbound timing/control replies unverified |
| Snapcast JSON-RPC and TCP audio | `AudioCastService.kt` | TCP | Only if the host came from the picker |
| Ktor WebSocket to AriaCast receivers | `AudioCastService.kt` | TCP | Only if the host came from the picker |
| DLNA stream server `:8091` and artwork server `:8090` | `AudioCastService.kt` | **inbound** TCP from renderers | No: the renderer connects to the phone |
| Google Cast DIAL/`:8008` HTTP | `AudioCastService.kt` | TCP | Only if the host came from the picker |
| Background discovery for the system output switcher | `CastRouteProvider.kt` → `DiscoveryManager.startDiscovery()` | all of the above | No UI to ask from |

**Recommendation: Path B (runtime permission)**, with the picker as an optional later extra.

### Migration steps, in order

1. **Manifest:** declare `android.permission.ACCESS_LOCAL_NETWORK`. This is harmless at target 36
   because it is implicitly granted. Do not request it at runtime yet, per the guide.
2. **Gate on a single check:** add one `hasLocalNetworkAccess(context)` helper.
   It returns `true` below target 37, and otherwise returns the `checkSelfPermission` result.
   `DiscoveryManager.startDiscovery()`, `AudioCastService` session start and
   `CastRouteProvider.onCreate` check it first. If it returns false, they publish an explicit
   state such as `DiscoveryState.NO_LOCAL_NETWORK_PERMISSION` instead of scanning. Without this,
   blocked TCP times out after 5–15 s and the user just sees an empty list.
3. **Request UX in `MainActivity`:** request before the first `startDiscovery()` (`onStart`, line ~390,
   and the refresh path, line ~236), not in `beginCast()`, because discovery is the first thing
   that needs the LAN. Reuse the existing `RequestMultiplePermissions` and "open Settings" pattern
   from the `RECORD_AUDIO` flow (`requestCastPermissions` and `showOpenSettingsForPermissionDialog`).
   Show a rationale screen first. The permission sits in the `NEARBY_DEVICES` group, so the system
   dialog wording ("nearby devices") will not obviously mean "your speakers" to users.
4. **Revocation:** re-check in `onStart`. In `AudioCastService`, map `EPERM` from UDP sends to a
   distinct error state instead of the generic `CastState.ERROR`.
5. **`CastRouteProvider`:** when the permission is missing, publish no routes. It cannot prompt.
6. **Docs:** add a section to `docs/permissions.md` in the same style as the notification-access
   section. Explain why the permission is needed: every protocol is LAN, and DLNA renderers
   connect back inbound.
7. **Bump `targetSdk = 37`**, then handle the other target-37 items below.
8. **Optional:** add an NSD picker entry ("Add device…") for users who refuse the broad
   permission. It would cover the WebSocket, AirPlay 2 and Snapcast paths only, and only to one
   picked host.

### Other target-37 changes to check

- **Background audio hardening:** at target 37, background audio interactions, including volume
  changes, need a foreground service with while-in-use capability. `AudioCastService` calls
  `setStreamVolume` to mute local output on start (line ~370) and to restore it on stop or error
  (lines ~407, ~1959). Those calls can run from the notification "stop" action while the app is
  in the background. Whether a `mediaProjection` FGS counts as while-in-use is **not verified**.
  Test it on an API 37 device.
- **Large-screen opt-out removed:** this app has no opt-out, so there is nothing to do.
- **Certificate Transparency on by default:** affects HTTPS only. The LAN protocols here are
  plain HTTP or app-level crypto. Recheck `network_security_config` before bumping.
- **Static final fields immutable via reflection:** there is no `getDeclaredField` or
  `isAccessible = true` in app code. Dependencies (BouncyCastle, JmDNS, Ktor) are unchecked;
  run the app.

### What breaks and for whom

- **Target 36 (this change):** users on Android 16+ get predictive back animations. Users on
  tablets and foldables get a resizable window if they had been letterboxed. There are no API
  removals.
- **Target 37:** every user on Android 17+ must grant a new "nearby devices" permission before
  anything works. Users who deny it get an app that does nothing until they grant it, which is
  why step 2's explicit state matters. Users on Android 16 and below see no change, because the
  permission is implicitly granted there.

### Effort

- Steps 1–6: about 1–2 days, including strings for the 9 translated locales (`values-de` … `values-zh`).
- Step 7 plus device testing: about 1 day. This needs an API 37 emulator or device with real
  AirPlay, DLNA and Snapcast receivers on the LAN.
- Step 8: about 2–3 days. It is a separate UX feature.

### Rollback

Lower `targetSdk` back to 36. The permission declaration and the gating helper are inert below 37.
