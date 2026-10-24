# Radio Guaka for Android — milestone 1 prototype

Tracks [issue #86](https://github.com/guaka/radio/issues/86). Target acceptance device: Pixel 8a. This is a native Kotlin/Compose app, independent of the web/Go server. Media3 owns phone/background playback; Android Auto browses the same MediaLibraryService. Music Assistant owns speaker playback.

## Build and install

Use Android Studio or JDK 17+, Android SDK platform 36 and `ANDROID_HOME` (or an untracked `local.properties` containing `sdk.dir=...`).

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The CI workflow uploads a debug-signed APK. Debug builds are for development: different machines/CI jobs may generate different debug keys, requiring uninstall before installation. Stable signed beta updates and release-key storage are milestone 4. Never commit signing keys, local.properties or access tokens.

Stations are bundled for cold startup/offline browsing; streams need a network. After changing the web catalogue, run `node android/scripts/sync-stations.mjs` from the repository root. CI verifies that the snapshot is current.

## Prototype flows

- **Radio:** tap a station, lock the phone, use notification/lock-screen controls. Background service owns the player, not the activity.
- **Plex:** tap Plex → Sign in to Plex, complete the Plex browser flow, return, choose a server and music library, then a track. The prototype shows the first 100 tracks. Discovery uses each resource's access token and probes HTTPS connections concurrently. Remote playback requires working Plex Remote Access and supported direct-play audio; transcoding is deferred.
- **Speakers:** Setup → enter the local Music Assistant URL and access token (MA Settings → Profile). While on home Wi-Fi, choose a track/station, then Speakers and a player. For Plex, explicitly confirm the corresponding Music Assistant search result. No fragile assumption is made that Plex rating keys equal MA library IDs. On successful remote start the phone pauses. There is no seamless handoff or remote speaker queue/control UI yet; use Music Assistant to control/stop speakers.
- **Android Auto:** enable Android Auto developer mode and its “Unknown sources” setting for a sideloaded media app. Connect to the car or Desktop Head Unit and choose Radio Guaka. Radio and Plex expose browse trees; Plex sign-in happens on the phone while parked. Test cold startup before opening the phone activity. Voice search is deferred. Wired/wireless car setup is not yet specified.

## Security and network boundaries

Plex tokens are kept in Android Keystore-encrypted preferences and sent as headers over HTTPS. A fresh data source is used per item; redirects on credential-bearing playback requests are refused. Browser/Auto clients supply media IDs; the service resolves them itself and does not accept arbitrary controller stream URLs. MA tokens are bearer headers and endpoints are restricted to private IPs/local hostnames over Wi-Fi/Ethernet. Cleartext is enabled for legacy radio and local MA HTTP; only use a trusted home network. App backup is disabled. No analytics, credential logs or bundled secrets.

## Validation

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5582 ./gradlew connectedDebugAndroidTest # use your own device serial
# Optional network test: public SomaFM stream, 35 seconds backgrounded with screen off.
ANDROID_SERIAL=emulator-5582 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.liveRadio=true
```

Unit tests cover resource discovery, HTTPS/token routing, local endpoint validation and response parsing. Instrumentation checks the cold service browse tree without launching an activity. The optional live-radio test starts the phone activity, plays a bundled station, backgrounds the activity and turns the screen off, then verifies playback continues and advances for 35 seconds. It is skipped unless `liveRadio=true` is supplied. Recorded on 2026-10-24: the debug APK and instrumentation APK built successfully, all six unit tests passed, and lint reported no errors. On a dedicated Android 16/API 36 ARM64 emulator, both the cold-service browse test and live SomaFM background/screen-off test passed. These short emulator checks do not establish long-session or physical-device reliability.

The following acceptance checks require actual accounts/devices and must be recorded separately:

- [ ] Pixel 8a: 30 minutes of radio with screen off and notification/headset controls.
- [ ] Plex: sign-in, local playback and mobile-data playback with Wi-Fi disabled.
- [ ] Actual Android Auto: cold start, browse radio/Plex, playback and steering-wheel controls.
- [ ] Home MA: list actual speakers, play one station and one Plex track, confirm phone pauses.
- [ ] Away from home: speakers are unavailable, while radio/Plex remain usable.

Remaining milestones add full browsing/search/favorites/queues, playback-state persistence, resilient network/credential recovery, and signed beta delivery. This PR is the working foundation, not a claim that those later features or hardware acceptance are complete.

References: [Media3 background playback](https://developer.android.com/media/media3/session/background-playback), [MediaLibraryService](https://developer.android.com/media/media3/session/serve-content), [Android Auto testing](https://developer.android.com/training/cars/testing), [Plex sign-in](https://forums.plex.tv/t/authenticating-with-plex/609370), [Music Assistant API](https://www.music-assistant.io/api/).
