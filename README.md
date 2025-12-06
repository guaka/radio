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

Channels are defined in `channels.js`. You're welcome to add channels and metadata by editing (i.e. fork and send pull requests).

Active channels are in the main `channels` object. Broken/non-working channels are kept in comments for reference.


## Features

- Keyboard shortcuts:
  - Arrow keys ← → : Change channels
  - Arrow keys ↑ ↓ : Change volume
  - Space : Stop/pause
  - Letter key : Jump to channel starting with that letter (no modifier needed)

## Chat

Radio Guaka includes a Nostr-based chat feature built with [NDK (Nostr Dev Kit)](https://github.com/nostr-dev-kit/ndk).

**Protocol:**
- Messages use kind 1 (short text notes) with `#radioguaka` hashtag (`['t', 'radioguaka']`)
- Reactions use kind 7
- Delete requests use kind 5
- Messages expire after 30 days via NIP-40 (`['expiration', timestamp]`)

**Relays:**
- `wss://relay.nomadwiki.org`
- `wss://relay.trustroots.org`

These are community relays aligned with the nomad/travel community.

**Authentication options:**
- Browser extension (nos2x, Alby, etc.) - auto-detected
- Manual key entry (nsec or hex private key)
- Generate anonymous keypair

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
