Radio Guaka
===========

**Radio Guaka** is an Internet Radio player based on HTML5 and vanilla JavaScript.
It's not Guaka's music, but radio stations that Guaka likes listening to, such as FIP and SomaFM.

Started in 2013 using meteorjs, completely revibed in 2025.


## About

This is a static site that can be deployed on GitHub Pages. The current implementation uses:
- Alpine.js for reactivity
- Vanilla JavaScript
- Plain CSS
- History API for routing

## Channels

Channels are defined in `public/channels.js`. You're welcome to add channels and metadata by editing (i.e. fork and send pull requests).

Active channels are in the main `channels` object. Broken/non-working channels are kept in comments for reference.


## Features

- Keyboard shortcuts:
  - Arrow keys ← → : Change channels
  - Arrow keys ↑ ↓ : Change volume
  - Space : Stop/pause
  - Letter key : Jump to channel starting with that letter (no modifier needed)

## Go Backend (same-origin pleXtr)

The repo now includes an optional Go server that serves `public/index.html` and API endpoints from the same origin.

- Entry point: `cmd/server/main.go`
- Health check: `GET /api/health`
- Nostr auth: `POST /auth/nostr/challenge`, `POST /auth/nostr/verify`
- Plex browse/stream (friend-gated): `GET /libraries`, `GET /tracks`, `GET /stream/:trackId`
- Public share links (revokable): `POST /share/song/:trackId`, `DELETE /share/:token`, `GET /s/:token`

### Run

Set these env vars as needed:

- `OWNER_PUBKEY` (required for friend access policy)
- `PLEX_BASE_URL` (e.g. `http://127.0.0.1:32400`)
- `PLEX_TOKEN` (server-side Plex token)
- Optional: `NOSTR_RELAYS`, `SESSION_SECRET`, `ADDR`

Then start:

`go run ./cmd/server`

### Docker Compose

Run the same-origin server on `http://localhost:3030`:

`docker compose up --build`

Set env vars in your shell or `.env`:

- `OWNER_PUBKEY`
- `PLEX_TOKEN`
- optional overrides: `PLEX_BASE_URL`, `SESSION_SECRET`, `NOSTR_RELAYS`

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
