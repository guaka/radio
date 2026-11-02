# YouTube audio

The Android YouTube source searches public videos or accepts a video URL. Playback selects only a progressive audio stream; there is no video fallback, account sign-in, download feature, or Android Auto browse tab in this first version. Favorites and queues store video IDs and resolve fresh stream URLs at playback.

Uses NewPipeExtractor v0.26.5 (https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5), GPL-3.0-or-later. Its license is included in app assets/licenses. APKs incorporating this dependency must be distributed in compliance with GPL-3.0-or-later, including corresponding source and notices; the repository's existing license notice is retained.

YouTube can block extraction or change its endpoints. Restricted videos or videos without a supported audio-only stream fail without replacing the current player. No Google credentials are collected.

Validation (2026-10-29): Android JVM tests, debug build, and lint pass. The opt-in `YouTubeAudioDeviceTest` passed on an Android 12/API 32 emulator: public search and stream extraction succeeded, playback advanced, and Media3 reported an audio format with no video format. A separate app smoke test resolved a pasted link, toggled its favorite, added it to the queue, and played the selected video; the app media-session position advanced past 90 seconds. Queue-initiated playback, failed-selection handoff, and physical-device behavior still need a YouTube-specific smoke test. Earlier emulator attempts failed on DNS; network and upstream availability can vary.
