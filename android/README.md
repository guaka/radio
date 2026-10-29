# Radio Guaka for Android — personal beta

Tracks [issue #86](https://github.com/guaka/radio/issues/86). Target acceptance device: Pixel 8a. This is a native Kotlin/Compose app, independent of the web/Go server. Media3 owns phone/background playback; Android Auto browses the same MediaLibraryService. Music Assistant owns speaker playback.

For signed beta installation, stable-key updates and backup instructions, see [BETA.md](BETA.md). Developer debug builds below use a different signing certificate. Playback recovery behavior and remaining device checks are described in [RELIABILITY.md](RELIABILITY.md).

## Build and install

Use Android Studio or JDK 17+, Android SDK platform 36 and `ANDROID_HOME` (or an untracked `local.properties` containing `sdk.dir=...`).

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The CI workflow uploads a debug-signed APK for development. Beta builds use a stable signing identity and can update one another while preserving app data. Follow [BETA.md](BETA.md) to create and protect the key, build a signed beta APK, install updates, and troubleshoot signing errors. Debug and beta APKs use different keys; switching between them requires uninstalling first and removes the app's local data. Never commit signing keys, password files, `local.properties` or access tokens.

Stations are bundled for cold startup/offline browsing; streams need a network. After changing the web catalogue, run `node android/scripts/sync-stations.mjs` from the repository root. CI verifies that the snapshot is current.

## Listening flows

- **Radio:** tap a station, lock the phone, use notification/lock-screen controls. Background service owns the player, not the activity.
- **Plex:** tap Plex → Sign in to Plex, complete the Plex browser flow, return, choose a server and music library, then Artists, Albums or Tracks. Artists open albums; albums open tracks. Lists load 50 items at a time; scroll near the bottom to load the next page automatically. Search filters the current source or Plex collection. Discovery uses each resource's access token and probes HTTPS connections concurrently. Remote playback requires working Plex Remote Access and supported direct-play audio; transcoding is deferred.
- **Favorites:** use the star beside a station or track, then open Favorites. Up to 500 favorites are available to the phone and Android Auto. Missing or inaccessible Plex favorites stay visible as unavailable so they can be removed; signing out or changing Plex credentials clears Plex listening data.
- **Phone queue:** radio is single-station playback. For Plex tracks, use +Q beside an item, Queue listed for the currently loaded tracks, or Play listed to replace the queue and play those tracks. Open Queue to select, reorder, remove or clear items. The phone UI permits up to 500 items. Queue changes and the current seekable Plex position are saved periodically and on pause; radio resumes live. The saved queue remains available to media-session resumption; the phone screen has no separate resume button. Starting the app alone does not start playback. Emptying the queue removes the saved resume choice.
- **Artwork:** radio rows show available station graphics, including SomaFM channel artwork, with a fallback icon when unavailable. Public station images are fetched without Plex credentials and gradually preloaded while the app is foregrounded into an 8 MiB memory cache backed by a 32 MiB disk cache. Downloaded station images survive process restarts, expire after 30 days, and are written atomically; Plex images remain outside this public-image cache. Plex artwork loads on visible phone rows and on the current notification/session item. It uses authenticated HTTPS requests and small embedded session thumbnails; credentials never appear in artwork URLs exposed to controllers.
- **Phone controls:** the selected star/Radio/Plex source is highlighted; the top-right gear opens full-screen Connections. The star beside the current title adds/removes favorites. The unlabeled filter updates as you type (immediately for radio, briefly debounced for remote collections); Enter or its embedded icon dismisses the keyboard. Buffering appears in a fixed-height footer so the controls do not shift. New in-app playback selections prepare separately and retain the current audio until the new stream is ready; a failed selection keeps the current audio.
- **Speakers:** Setup → enter the local Music Assistant URL and access token (MA Settings → Profile). While on home Wi-Fi, choose a track/station, then the bottom output selector and a player. The selector shows the active Bluetooth device name when Android provides it, or This phone for local output. For Plex, explicitly confirm the corresponding Music Assistant search result. No fragile assumption is made that Plex rating keys equal MA library IDs. On successful remote start the phone pauses. There is no seamless handoff or remote speaker queue/control UI yet; use Music Assistant to control/stop speakers.
- **Android Auto:** enable Android Auto developer mode and its “Unknown sources” setting for a sideloaded media app. Connect to the car or Desktop Head Unit and choose Radio Guaka. Radio and Plex expose browse trees; Plex sign-in happens on the phone while parked. Test cold startup before opening the phone activity. Voice search is deferred. Wired/wireless car setup is not yet specified.

## Security and network boundaries

Plex tokens are kept in Android Keystore-encrypted preferences and sent as headers over HTTPS. A fresh data source is used per item; every credential-bearing HTTP request, including HLS child playlists, keys and segments, must match the original Plex scheme, host and port. Redirects on those requests are refused. Tokenless radio can follow redirects across HTTP/HTTPS. Browser/Auto clients supply media IDs; the service resolves them itself and does not accept arbitrary controller stream URLs. MA tokens are bearer headers and endpoints are restricted to private IPs/local hostnames over Wi-Fi/Ethernet. Cleartext is enabled for legacy radio and local MA HTTP; only use a trusted home network. App backup is disabled. No analytics, credential logs or bundled secrets.

## Validation

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5582 ./gradlew connectedDebugAndroidTest # use your own device serial
# Optional network test: public SomaFM stream, 35 seconds backgrounded with screen off.
ANDROID_SERIAL=emulator-5582 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.liveRadio=true
```

Unit tests cover resource discovery, HTTPS/token routing, local endpoint validation and response parsing. Instrumentation checks the cold service browse tree without launching an activity. The optional live-radio test starts the phone activity, plays a bundled station, backgrounds the activity and turns the screen off, then verifies playback continues and advances for 35 seconds. It is skipped unless `liveRadio=true` is supplied. Milestone-1 baseline recorded on 2026-10-24: the debug APK and instrumentation APK built successfully, all six unit tests passed, and lint reported no errors. On a dedicated Android 16/API 36 ARM64 emulator, both the cold-service browse test and live SomaFM background/screen-off test passed. These short emulator checks do not establish long-session or physical-device reliability.

Milestone 2 validated on 2026-10-24: 11 JVM tests and six API 36 emulator instrumentation tests passed, with zero lint errors. Coverage includes favorites persistence/limits, Plex account-data clearing, queue reordering/removal/clearing, preservation across an empty service restart, and the live screen-off radio test. A manual phone UI smoke check covered favorite selection, adding/clearing stations in the queue, and an explicit Resume choice after restarting the process. Real Plex browsing/search/artwork and seek/resume still need an account-backed acceptance run.

Milestones 3–4 validated on 2026-10-24: 15 JVM tests and eight API 36 emulator tests passed, including saved-queue resumption, audio-focus interruption/user-pause handling and settings-schema preservation. The signed beta installed successfully; an in-place update from version code 4 to 5 retained the app UID, original install time, favorite, endpoint setting and an encrypted dummy MA token. A local fake MA server confirmed the updated app decrypted and used that token. Unsigned releases and a different signing certificate are rejected. This does not establish real-account or physical-device acceptance.

## Review across milestones (2026-10-25)

The combined implementation was reviewed across all four stacked milestones, rather than reviewing only the latest PR diff. Implementation and acceptance remain separate:

| Milestone | Automated evidence | Acceptance still required |
| --- | --- | --- |
| 1 · Prototype | Reproducible debug builds/CI; cold local MediaBrowser tree; screen-off live radio; integration parser, token-header and endpoint tests | Real Plex sign-in/discovery/local and mobile-data playback; actual Android Auto cold browse/controls; actual MA radio/Plex speaker start |
| 2 · Listening | Favorites and queue persistence/limits; bounded Favorites hydration and missing-item isolation; deterministic saved queue order/index/seek and stale-account tests; artwork bounds | Account-backed Plex browsing/search/artwork and seek/resume; real speaker output confirmation |
| 3 · Reliability | Bounded retry policy and typed controller errors; cancellation and account-generation guards; emulator audio focus/user pause and process/service checks | Real calls/Bluetooth/noisy-route behavior, Wi-Fi/mobile transitions, doze/battery saver and long Pixel/car sessions |
| 4 · Beta | Fail-closed signing with a pinned identity; schema preservation; recorded same-key update retaining settings and encrypted dummy credentials | Encrypted off-device signing-key backup and tester/device installation acceptance |

This review fixed an MA URL validation bypass involving private-looking numeric hostname labels, Plex sign-out clearing radio playback, misleading/silent controller errors, sequential Favorites lookups, and the loss of entered MA settings after validation failure. Production resume and Favorites resolution now have hermetic tests for their asynchronous behavior. No private credentials or real accounts were used.

The combined suite passed 31 JVM tests and 12 API 36 emulator tests, including the opt-in live screen-off radio check. Debug and signed release builds pass; lint passes with warnings. The final CI run is recorded in the PR. None of the four milestones is claimed fully accepted until the remaining account and hardware checks are completed.

A further adversarial review fixed cross-origin Plex token forwarding through HLS child resources, Retry failing on unrelated unavailable queue entries, and stale speaker requests publishing after dismissal, sign-out or switching back to phone playback. Retry now re-resolves only the current item while preserving the queue. Speaker requests use cancellation plus a shared publication guard; routine player events do not cancel an active picker. The latest review passed 37 JVM tests and 13 API 36 emulator tests, including live radio and controller replacement, plus lint and debug builds. The reviewed build was subsequently delivered as signed version 0.4.2 and installed on the Pixel 8a over USB.

The subsequent player-controls update passed 53 JVM tests and 14 API 36 emulator tests, including real MediaBrowser handoff between live stations and preservation of the old station after a failed selection. Lint passes. Manual emulator checks covered keyboard search with IME dismissal, current-item favorite/unfavorite synchronization, Radio queue-control removal, and visible SomaFM artwork.

The next UI update passed 60 JVM tests and lint. Manual emulator checks verified full-screen Connections with retained validation errors, Radio isolation from Plex controls, and automatic paging beyond the first page. Public artwork preloads gradually into a bounded cache; the bottom output selector observes the selected Bluetooth route. The launcher uses a dark adaptive icon, with the radio mark also shown in the compact top navigation. Physical Bluetooth naming still requires a connected headset check.

The following acceptance checks require actual accounts/devices and must be recorded separately:

- [ ] Pixel 8a: 30 minutes of radio with screen off and notification/headset controls.
- [ ] Plex: sign-in, local playback and mobile-data playback with Wi-Fi disabled.
- [ ] Actual Android Auto: cold start, browse radio/Plex, playback and steering-wheel controls.
- [ ] Home MA: list actual speakers, play one station and one Plex track, confirm phone pauses.
- [ ] Away from home: speakers are unavailable, while radio/Plex remain usable.

Bounded retry and explicit network/credential recovery are implemented; broader process/network transition hardening still requires the hardware acceptance runs above. Voice search remains deferred. These changes build on the milestone-1 foundation in PR #87; hardware acceptance remains separate from emulator checks.

References: [Media3 background playback](https://developer.android.com/media/media3/session/background-playback), [MediaLibraryService](https://developer.android.com/media/media3/session/serve-content), [Android Auto testing](https://developer.android.com/training/cars/testing), [Plex sign-in](https://forums.plex.tv/t/authenticating-with-plex/609370), [Music Assistant API](https://www.music-assistant.io/api/).
