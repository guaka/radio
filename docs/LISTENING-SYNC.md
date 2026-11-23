# Listening sync

Radio, Plex, YouTube, Favorites and History share the same navigation on phone and desktop. The website starts on Radio. The Android app and the website use the same station IDs.

## Radio favorites

Connect the same Nostr account on Android and on the web. Open Connections on the web and enable **Share and sync radio favorites publicly**. On Android, connecting a signer enables Radiostr sharing. Local stars are retained on first import. Subsequent radio stars and unstars are public. Plex favorites use their existing platform settings.

## Private listening history

Connect a NIP-44 capable signer. The web player supports a NIP-07 browser extension, a NIP-46 remote signer bunker URL, or a NIP-55 installed signer when opened in an Android browser. The Android app supports an installed or remote signer. Connect the same account in each player. Choose the same secure relay URL in Connections on both devices and enable **Sync listening history** separately from public sharing.

Radio and YouTube plays recorded after opt-in sync by stable media ID. Earlier plays stay available locally. Plex recents keep their existing storage. YouTube playback on the static site requires opening a Go-served instance.

Each device publishes a signed NIP-78 kind 30078 event with an opaque per-installation address and a fixed topic tag. Its NIP-44 payload is encrypted to the connected account and contains only the version, up to 100 media IDs with playback timestamps, and a clear cutoff. Clear history advances that cutoff so older plays from an offline device remain cleared after it reconnects. The relay can see the signing account and publication times and may retain previous encrypted snapshots.

Pending updates remain available for retry if the relay or signer is offline. A compatible relay must accept kind 30078 and support reading the user's events from both devices. History sync itself uses WebSockets from the browser and Android.

The web player and chat use external signers. They remove private keys saved by older versions. A NIP-46 session stores an encrypted disposable transport key, while NIP-55 stores the public account identifier.
