# Music Assistant quickstart (Radio Guaka)

This is the quick-win integration path for Music Assistant (MA) in this repo.

## Recommended call path

Use the Go server as a same-origin proxy for MA:

- Browser/UI calls `GET|POST /api/music-proxy/music-assistant/...`
- Server forwards to `MUSIC_ASSISTANT_BASE_URL`
- Server adds `Authorization: Bearer <MUSIC_ASSISTANT_TOKEN>` when configured

The probe and proxy require an authenticated Nostr friend session (the same policy as `/libraries`). Send the session cookie from the Nostr challenge/verify flow. The quick-map route is public and returns example payloads without making upstream calls. The examples below are exploratory and still need validation against your Music Assistant instance.

Why this path first:

- Avoids browser CORS issues against MA host.
- Keeps MA bearer tokens out of browser storage and JS logs.
- Reuses the existing same-origin proxy pattern in the Go backend.

## Endpoints added in this repo

- `GET /api/music-assistant/probe`
  - Checks local config and probes MA reachability (`/api-docs`, `/api/players`).
- `GET /api/music-assistant/quick-map`
  - Returns a quick contract with the first 3 operations and example payloads.
- `ALL /api/music-proxy/music-assistant/*`
  - Pass-through proxy to MA.

## Quick operation contract (MVP)

These are the first operations to validate:

1. Players
   - Proxy call: `GET /api/music-proxy/music-assistant/api/players`
2. Search (command-style)
   - Proxy call: `POST /api/music-proxy/music-assistant/api/command`
   - Body example:
     - `{"message_id":1,"command":"music/search","args":{"query":"boards of canada","limit":5}}`
3. Queue/play (command-style)
   - Proxy call: `POST /api/music-proxy/music-assistant/api/command`
   - Body example:
     - `{"message_id":2,"command":"player_queues/play_media","args":{"queue_id":"...","media":[{"item_id":"...","media_type":"track","provider":"library"}]}}`

Note: command names/args can vary by MA version. Always verify with your MA `api-docs`.

## UI hook

No dedicated MA UI page is required for this quickstart; use API calls directly:

- `GET /api/music-assistant/probe`
- `GET /api/music-assistant/quick-map`
- `GET|POST /api/music-proxy/music-assistant/*`

## Risks and fallback

- CORS/auth drift: browser-direct calls may break or leak tokens.
  - Fallback: keep server proxy as default path.
- MA command schema/version mismatch:
  - Fallback: read MA `api-docs`, then adapt payload shape.
- WebSocket complexity for realtime updates:
  - Fallback: begin with REST/HTTP validation and add WS subscriptions later.

## Phased next steps

1. Validate probe + players on your MA instance.
2. Adjust search/queue command payloads to exactly match your MA version.
3. Add a tiny reusable MA client module in frontend JS.
4. Add optional WebSocket read-only status stream (`/ws`) for now-playing updates.
