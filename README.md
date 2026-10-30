Radio Guaka
===========

**Radio Guaka** is an Internet Radio player based on HTML5 and vanilla JavaScript.
It's not Guaka's music, but radio stations that Guaka likes listening to, such as FIP and SomaFM.

Started in 2013 using meteorjs, completely revibed in 2025.


## About

This is a static site that can be deployed on GitHub Pages. The current implementation uses:
- Alpine.js for reactivity
- TypeScript compiled to browser JavaScript
- Plain CSS
- History API for routing

## Channels

Channels are defined in `src/frontend/channels.ts`. You're welcome to add channels and metadata by editing (i.e. fork and send pull requests).

Active channels are in the main `channels` object. Broken/non-working channels are kept in comments for reference.


## Features

- Keyboard shortcuts:
  - Arrow keys ← → : Change channels
  - Arrow keys ↑ ↓ : Change volume
  - Space : Stop/pause
  - Letter key : Jump to channel starting with that letter (no modifier needed)
- YouTube tab in pleXtr (MVP):
  - Paste a single YouTube video URL/ID to play audio through direct extraction, with Piped and Invidious fallbacks.
  - Resolution is done through the Go backend endpoint (`/api/youtube/resolve`) to avoid browser CORS issues.
  - Playback uses a same-origin stream proxy endpoint (`/api/youtube/stream`) to reduce browser network/CORS/TLS failures with public instances.
  - The YouTube tab lists **external check links** (YouTube, Piped API JSON, approximate Piped web UI, Invidious) from `GET /api/youtube/external-links?videoId=…` so you can verify mirrors in the browser.
  - v1 scope is playback-only (no favorites/sharing/chat coupling for YouTube items).

## Go Backend (same-origin pleXtr)

The repo now includes an optional Go server that serves `public/index.html` and API endpoints from the same origin.

- Entry point: `cmd/server/main.go`
- Health check: `GET /api/health`
- Nostr auth: `POST /auth/nostr/challenge`, `POST /auth/nostr/verify`
- Plex browse/stream (friend-gated): `GET /libraries`, `GET /tracks`, `GET /stream/:trackId`
- Wrapster-backed Plex search/stream (optional): set `WRAPSTER_MEDIA_URL` and `WRAPSTER_MEDIA_NSEC` to route the friend-gated Plex search/stream endpoints through Wrapster's NIP-98 protected media gateway.
- Public share links (revokable): `POST /share/song/:trackId`, `DELETE /share/:token`, `GET /s/:token`
- **Music Assistant quick exploration**: same-origin proxy + probe endpoints for validating API connectivity and command payloads. See [MUSIC-ASSISTANT-QUICKSTART.md](MUSIC-ASSISTANT-QUICKSTART.md). Optional env: `MUSIC_ASSISTANT_BASE_URL`, `MUSIC_ASSISTANT_TOKEN`.

### Run

Set these env vars as needed:

- `OWNER_PUBKEY` (required for friend access policy)
- `PLEX_BASE_URL` (e.g. `http://127.0.0.1:32400`)
- `PLEX_TOKEN` (server-side Plex token)
- Optional: `NOSTR_RELAYS`, `SESSION_SECRET`, `ADDR`
- Optional Wrapster media gateway mode:
  - `WRAPSTER_MEDIA_URL` (e.g. `https://relay.example.org`; base URL that serves `/media/api/services/plex/*`)
  - `WRAPSTER_MEDIA_NSEC` (Nostr private key, `nsec...` or raw hex, granted for Wrapster media access)

When `WRAPSTER_MEDIA_URL` and `WRAPSTER_MEDIA_NSEC` are set, the backend's friend-gated `GET /libraries`, `GET /tracks`, and `GET /stream/:trackId` routes use Wrapster for Plex search and streaming. The same-origin `/api/plex/*` proxy still requires direct `PLEX_BASE_URL` access because Wrapster's media gateway intentionally exposes only fixed search and stream routes, not arbitrary Plex library APIs.

Build the frontend with Node.js 22.18 or newer before running the server locally:

`npm ci && npm run build`

TypeScript sources are in `src/frontend`; the generated browser scripts in `public/js` are ignored by Git. The compiler currently transpiles the migrated code without type-checking (`noCheck`), so type safety is not yet enforced. Rebuild after changing frontend sources. Docker and the Pages workflow build these scripts automatically.

Then start:

`go run ./cmd/server`

The server defaults to `http://localhost:3030`. When `SESSION_SECRET` is unset, it generates a random secret and stores it in `data/plextr.sqlite3`. Sessions and share links remain in memory and expire on restart. Keep `.env`, private `.p8` keys, and `data/` out of version control.

The same-origin `/api/plex/*` proxy accepts a caller's `X-Plex-Token` query parameter or header. Requests without a caller token require an authenticated friend session before using the server's Plex token. Music Assistant proxy/probe calls and Apple developer token issuance also require a friend session.

### CSV playlists

Open the Playlists tab and select Spotify CSV exports using the file picker. CSV files stay in the current browser tab; matching sends track searches to your configured Plex server. Match results can be played or downloaded as JSON and missing-track CSV reports. Reloading the page clears imported playlists.

### Validation

Run `go test -race ./...`, `go vet ./...`, and `npm test`. Live Plex, Wrapster, Music Assistant, and YouTube playback require separately configured services; the automated suite uses local test servers and fixtures.

### Docker Compose

Run the same-origin server on `http://localhost:3030`:

`docker compose up --build`

Set env vars in your shell or `.env`:

- `OWNER_PUBKEY`
- `PLEX_TOKEN`
- optional overrides: `PLEX_BASE_URL`, `SESSION_SECRET`, `NOSTR_RELAYS`

Compose persists the generated session secret in the `plextr-data` named volume. Music Assistant can be configured with `MUSIC_ASSISTANT_BASE_URL` and `MUSIC_ASSISTANT_TOKEN`; those values are passed through by Compose. Optional Spotify/Apple helpers use `SPOTIFY_CLIENT_ID`, `APPLE_MUSIC_TEAM_ID`, `APPLE_MUSIC_KEY_ID`, and `APPLE_MUSIC_PRIVATE_KEY_PATH`. Mount the Apple `.p8` file read-only into the container and set its container path when enabling MusicKit.

## Chat

Radio Guaka includes a Nostr-based chat feature built with [NDK (Nostr Dev Kit)](https://github.com/nostr-dev-kit/ndk).

**Protocol:**
- Messages use kind 1 (short text notes) with `#plextr` and legacy `#radioguaka` tags (`['t', 'plextr']`, `['t', 'radioguaka']`) so pleXtr and radio.guaka.org share the same room; favorites also use `['t', 'favorite']` plus structured tags for cross-device sync — see [PLEXTR-FAVORITES-NOSTR.md](PLEXTR-FAVORITES-NOSTR.md)
- Reactions use kind 7
- Delete requests use kind 5
- Messages expire after 30 days via NIP-40 (`['expiration', timestamp]`)

**Relays:**
- `wss://relay.nomadwiki.org`
- `wss://relay.trustroots.org`

These are community relays aligned with the nomad/travel community.

### Nostr relay DNS noise (`nr-relay-to-rabbit`)

Some browser/Nostr library builds may occasionally emit non-fatal promise rejections like:

`HTTP request failed: Post "http://nr-relay-to-rabbit": dial tcp: lookup nr-relay-to-rabbit ... server misbehaving`

This comes from an internal relay transport path and does **not** break chat/playback. To keep this from resurfacing as noisy uncaught errors:

- Keep the unhandled rejection + error guards in `src/frontend/index-1.ts` and `src/frontend/chatiframe1-1.ts` in sync.
- When upgrading Nostr libraries, test chat startup and posting with DevTools open to verify those errors stay downgraded to warnings.
- Keep the relay list fixed to the two production relays above unless there is an explicit migration plan.

**Authentication options:**
- **NIP-07 browser extension** (nos2x, Alby, etc.) - Recommended, most secure. The website never accesses your private key - the extension handles signing securely.
- Manual key entry (nsec) - Fallback option. Stores key in localStorage (less secure).
- Generate anonymous keypair - Creates new keypair, stores in localStorage.

See [NOSTR.md](NOSTR.md) for detailed documentation on NIP-07 and security considerations.

**Implementation:**
- `chatiframe1.html` - Standalone chat component (can be embedded as iframe)
- Uses Alpine.js for reactivity
- Messages cached in localStorage for instant loading
- Current channel passed via postMessage from parent window
- Keys stored in localStorage (`radio_guaka_chat_private_key`, `radio_guaka_chat_pubkey`)

## Background

Radio Guaka was inspired by [somafm-popup](https://github.com/joe-roth/somafm-popup) and the need for a nice way to play Soma.fm streams, [Radio Paradise](http://www.radioparadise.com/), and more.

## Links

* [radio.guaka.org](https://radio.guaka.org/)
