# YouTube audio

The Android YouTube source searches public videos or accepts a video URL. Playback selects only a progressive audio stream; there is no video fallback, account sign-in, download feature, or Android Auto browse tab in this first version. Favorites and queues store video IDs and resolve fresh stream URLs at playback.

Uses NewPipeExtractor v0.26.5 (https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5), GPL-3.0-or-later. Its license is included in app assets/licenses. APKs incorporating this dependency must be distributed in compliance with GPL-3.0-or-later, including corresponding source and notices; the repository's existing license notice is retained.

YouTube can block extraction or change its endpoints. Restricted videos or videos without a supported audio-only stream fail without replacing the current player. No Google credentials are collected.

Validation: 85 JVM tests and debug lint/build checks pass. The opt-in `YouTubeAudioDeviceTest` initially completed search and stream extraction, but playback failed resolving the Googlevideo host. Retries failed resolving YouTube itself, including after an emulator DNS restart. Actual audio playback is not yet verified; keep this feature in draft until tested on a working network/device.
