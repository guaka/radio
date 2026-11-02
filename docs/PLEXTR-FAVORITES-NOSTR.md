# pleXtr favorites on Nostr (draft spec)

This document describes how **pleXtr** (the Plex front-end in this repo) encodes **favorited tracks, albums, and radio stations** in Nostr events so they can be **restored on another device** when the same identity (`nsec`) is configured.

It is written as a **draft NIP-style specification** for feedback and possible later submission as a formal NIP. Until then, treat this as the **pleXtr reference implementation**.

## Summary

| Field | Value |
|--------|--------|
| **Event kind** | `1` (text note) |
| **Human text** | Existing pleXtr strings: `⭐` / `☆` prefix, hashtags `#plextr`, `#favorite`, optional `#album`, `#radio` |
| **Room / visibility** | Tags `t` = `plextr` and `t` = `radioguaka` (shared with Radio Guaka chat context) |
| **Favorite marker** | Tag `t` = `favorite` |
| **Machine data** | Custom tags (see below), prefixed with `plextr_` |

Clients **must not** rely on parsing `content` alone for sync; **structured tags** are authoritative for cross-device restore.

## Required tags (favorites)

Every favorite-related kind `1` note from pleXtr includes:

- `["t", "plextr"]`
- `["t", "radioguaka"]` — legacy / shared room with Radio Guaka
- `["t", "favorite"]`

## Optional: now-playing

Now-playing notes use only:

- `["t", "plextr"]`
- `["t", "radioguaka"]`

They are **not** favorites and must not be used to infer star state.

## Machine tags (favorites)

All names are lowercase. Values are UTF-8 strings. Omit empty optional values.

| Tag | Required | Meaning |
|-----|----------|---------|
| `client` | Recommended | Application id: `plextr` |
| `plextr_ver` | Recommended | Spec version string; current value `1` |
| `plextr_item` | **Yes** (for sync) | `track` \| `album` \| `station` |
| `plextr_action` | Recommended | `star` \| `unstar` — toggling off produces `unstar` |
| `plextr_rating_key` | For `track` / `album` | Plex `ratingKey` as decimal string |
| `plextr_station_id` | For `station` | Stable station id (same as pleXtr / `channels.js`) |
| `plextr_artist` | Optional | Album artist / track album artist (display + hints) |
| `plextr_title` | Optional | Track title, album title, or station name |
| `plextr_parent_rating_key` | Optional | Plex `parentRatingKey` for a track |
| `plextr_grandparent_rating_key` | Optional | Plex `grandparentRatingKey` for a track |

### Semantics

- **`plextr_action`**: If missing, clients may infer from `content`: leading `☆` → `unstar`, otherwise treat as `star` (legacy).
- **Star state over time**: For each `(plextr_item, id)` where `id` is `plextr_rating_key` or `plextr_station_id`, the **latest** event by `created_at` wins (replace-style semantics).
- **Legacy notes**: Older pleXtr posts had only human-readable `content` and hashtags. They **cannot** be mapped to Plex `ratingKey` without guessing; implementers should **ignore** them for automated library sync.

## Relay policy

pleXtr uses the same fixed relay pair as Radio Guaka:

- `wss://relay.nomadwiki.org`
- `wss://relay.trustroots.org`

## Implementation notes (non-normative)

- Query: `kinds: [1]`, `authors: [<pubkey>]`, `#t: ["favorite"]`, then filter events that also include `t` = `plextr` or `radioguaka`.
- Cap `limit` as needed; large libraries may require pagination with `until` / `since`.
- Merging with **local-only** favorites: apply Nostr timeline only for ids that appear in fetched events; leave other local starred ids unchanged.

## Changelog

- **v1** (2025-03): Initial `plextr_*` tags and pleXtr restore-on-connect behavior.
