# Nostr favorites

Connections offers an Android signer (NIP-55, such as Amber or Signstr) and a remote signer using a `bunker://` URL (NIP-46). The app never requests the account's private key. Android signer identity/package and the disposable remote transport key are encrypted using the existing Android Keystore credential store. Disconnect deletes the active session and its transport key; local favorites remain.

Connecting opts subsequent radio star/unstar changes into public publication. Existing local favorites are retained on first import, but are not automatically published in bulk. Plex favorites never enter this protocol. Sync favorites imports remote radio choices and retries pending local updates. Unsigned pending changes and acknowledged signed receipts are retained per account, so rejected signing, network failures, and a lagging relay cannot silently erase a local change. The initial implementation syncs on connect, on local favorite changes, and on explicit Sync; it does not continuously subscribe in the background.

## Radiostr wire format

The reference is [Radiostr's deployed source](https://nos.trustroots.org/examples/radiostr/index.js). Favorites are ordinary signed kind-1 events:

- `t`: `radiostr`
- `t`: `favorite`
- `client`: `radio-guaka`
- `radiostr_station`: the catalog station ID without the app's `radio:` prefix
- `radiostr_action`: `star` or `unstar`
- `radiostr_title`: the display title
- `expiration`: `created_at + 30 days`

Content is `⭐ <title> #radiostr #favorite` or `☆ <title> #radiostr #favorite`. Import verifies signatures and author, filters recent unexpired radio actions, and applies the most recent action for each station. Equal timestamps have a deterministic event-ID tie break. Unknown stations are not added to the local catalog. Local favorites not mentioned remotely are retained.

The initial relay set matches Radiostr: `wss://relay.trustroots.org` and `wss://relay.nomadwiki.org`. Publishing requires a matching positive relay `OK`; reading requires a completed subscription. Optional NIP-42 relay authentication uses the connected signer. Public relay clients are separate from credential-bearing Plex/Music Assistant HTTP clients.

## Signer validation

Every returned event must have exactly the requested kind, timestamp, content and tags, the connected user's pubkey, a matching canonical event ID, and a valid Schnorr signature. NIP-46 uses NIP-44 encryption and signed kind-24133 transport events; it correlates request IDs and validates the remote author and recipient. HTTPS auth challenges keep the same pending request alive. Restoring a remote session verifies the user identity without replaying the one-time bunker secret.

Quartz 1.05.1 supplies NIP-01/NIP-44 cryptography and relay primitives. The pinned version supports this app's existing compile SDK/Gradle plugin; later releases require a newer toolchain. OkHttp and MockWebServer are aligned at 5.3.2. Native NIP-44 tests run on an Android emulator; host tests use the JVM secp256k1 runtime.

Real signer/account interoperability is an acceptance check beyond fake-intent and local relay tests. No real account events are published by automated tests.
