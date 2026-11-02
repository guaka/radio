# Android playback recovery (milestone 3)

The player owns background audio, audio focus, becoming-noisy handling and its network wake lock. Android/Media3 pauses on focus loss or a disconnected audio route. Returning focus must not override an explicit user pause. Device-specific Bluetooth, calls and battery-saver behavior still need Pixel/car acceptance.

## Connection and playback failures

- Transport interruptions and HTTP 408, 429 or 5xx responses have up to three source-load retries, delayed by 1, 2 and 4 seconds. HTTP API calls retain finite connect/read/whole-call timeouts. Continuous streams have no whole-stream deadline.
- Authentication, missing media, unsupported formats and certificate failures stop retrying. The phone presents an appropriate action without exposing request URLs or tokens. Radio access failures do not ask for Plex sign-in.
- After retries stop, use **Retry playback**. This explicitly re-discovers Plex servers/endpoints and re-resolves queued IDs, preserving the current track position where available. This is also the recovery path after a Wi-Fi/mobile transition leaves the old Plex endpoint unreachable.
- An expired Plex session offers sign-in again; Music Assistant access failures point to its Setup token. Permanent API failures are not repeated as connection probes.
- The app does not automatically start playback when a network returns. This avoids resuming after a user pause, a call/focus interruption, or an unplugged headset. Saved queues are resumed explicitly and stale media are omitted by the service.
- Buffering is visible. Existing favorites/queue state survive a process restart; at most ten seconds of actively playing seekable position may be lost if Android kills the process before the next snapshot.

## Validation and remaining acceptance

Automated tests cover HTTP/auth failure categories, certificate failures, bounded delays/retry exhaustion, and safe error messages. Emulator coverage includes cold service browsing, saved-state/queue behavior, live radio with the screen off, transient audio-focus loss and preserving a user pause when focus returns.

Still required on the user's Pixel 8a/accounts/car: long radio/Plex sessions, real calls, wired/Bluetooth disconnects, Wi-Fi↔mobile transitions, battery saver/doze, real expired Plex/MA credentials, and Android Auto steering-wheel controls. Wired versus wireless Auto remains unspecified. These are acceptance checks, not claimed results.
