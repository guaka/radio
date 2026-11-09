# Local Music Assistant connection

This is separate from the TV layout/artwork update in PR #139. The first implementation adds local discovery and URL/access-token setup on phone and TV, keeping Remote Access available. It does not implement phone approval, QR pairing, or native Home Assistant OAuth.

## Connect

1. Open Connections on the phone, or Settings → Music Assistant on TV, then choose **Local network**.
2. Select **Find local servers**, then a server. If discovery is blocked or finds nothing, enter its direct root URL (usually `http://<server-IP>:8095`). Home Assistant ingress URLs are not direct server URLs.
3. In Music Assistant on your phone, open Settings → Profile and create an access token. Enter it in Radio Guaka, then **Connect and save**. Use a trusted LAN for HTTP, which does not encrypt traffic. This initial version still needs token entry on TV; a phone remote keyboard can help.
4. Select an item, open the output selector, and choose speakers.

The app checks `/info`, authenticates `players/all`, and only then saves one encrypted record containing URL, server ID and token. A blank token reuses the saved token only at the same normalized URL. Every local command checks the server ID before sending the token, detecting accidental address reassignment (not providing cryptographic identity on HTTP). Connection failure leaves the working configuration intact. Switching servers clears stale speaker selection; reconnecting to the same server retains it.

Music Assistant 2.10.9 long-lived tokens expire after one year without renewal; built-in login sessions renew on activity with a 30-day inactivity limit and a 90-day absolute cap. This implementation stores a user-supplied access token and does not mint or renew tokens. Expired/revoked tokens produce an access-denied error; replace the token in Settings. **Forget local connection** deletes the device's saved local credentials. It does not revoke a copied token on the server: revoke it in Music Assistant → Settings → Profile. The saved remote connection is retained and can be selected and validated again explicitly. Remote and local tokens are separate.

## Verified Music Assistant 2.10.9 capabilities

Inspected tag `2.10.9`, commit `912308ecfda2a01985dfcf555978815c7dda11a6`:

- [Discovery controller](https://github.com/music-assistant/server/blob/2.10.9/music_assistant/controllers/discovery/controller.py): advertises `_mass._tcp.local.` with server identity/version and the published port. Android uses NSD to find candidates, with a bounded scan and manual entry fallback. Advertised URLs are not trusted as authenticated endpoints.
- [HTTP controller](https://github.com/music-assistant/server/blob/2.10.9/music_assistant/controllers/webserver/controller.py): `/info` exposes server information; `POST /api` accepts commands and bearer authorization. Local commands do not use WebRTC or the remote portal.
- [Authentication manager](https://github.com/music-assistant/server/blob/2.10.9/music_assistant/controllers/webserver/auth.py) and [providers](https://github.com/music-assistant/server/blob/2.10.9/music_assistant/controllers/webserver/helpers/auth_providers.py): built-in username/password and Home Assistant OAuth are supported. `auth/providers` lists the providers actually enabled. OAuth uses Music Assistant's callback and validated return URLs. The [redirect validator](https://github.com/music-assistant/server/blob/2.10.9/music_assistant/helpers/redirect_validation.py) recognizes `musicassistant://`, configured/server origins, loopback/private addresses and selected Home Assistant URLs; other HTTP(S) destinations need consent. An arbitrary Radio Guaka custom scheme is not registered. Radio Guaka cannot assume access to Home Assistant app cookies.
- Join-code exchange is public and rate-limited, but code generation is guest-only. Codes default to one use, eight-hour validity, and twelve characters. Guest tokens have a fixed one-day lifetime with restricted permissions. The dashboard URL mechanism mints a guest/dashboard code; it is not general-purpose TV authorization. No general authenticated phone-to-TV login-code creation endpoint was found.

A TV QR → phone approval → TV authorization flow needs an explicit session-bound design with cancellation, expiry, replay prevention, wrong-device rejection, and a secure token delivery path. It is deferred rather than borrowing a guest/dashboard flow. Existing Home Assistant login may help authenticate within Music Assistant's own UI; it does not grant Radio Guaka access automatically.

## Validation status

During investigation, a real LAN server was discovered through `_mass._tcp.local.`. Direct `/info` confirmed version 2.10.9; `auth/providers` returned `builtin` and `homeassistant`. These were unauthenticated, read-only checks from the development Mac. A configured opt-in Android emulator test also passed a direct `/info` request to that server. No real credentials were copied or used.

Validation passed: 218 JVM tests, `testDebugUnitTest lintDebug assembleDebug`, and 22 targeted API 36 emulator tests. Automated coverage includes URL validation, server mismatch before credential transmission, authentication/revocation failures, HTTP redirects/errors, persistence/recreation, cancellation, server switching, discovery parsing, and phone/TV form states. Android tests also verify protected storage. Optional device test `LocalAssistantStorageDeviceTest.directServerInfoIsReachableWhenExplicitlyConfigured` accepts instrumentation argument `localAssistantUrl` for a read-only `/info` check.

Physical phone/TV reachability, LAN multicast on those devices, and selecting a real speaker and starting playback require device/account acceptance. They must not be inferred from the mock server or emulator tests.
