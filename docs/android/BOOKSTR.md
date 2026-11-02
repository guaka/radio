# Bookstr listening

The Books source browses downloaded EPUB, PDF, and FB2 metadata exposed by the companion Bookstr Android app. Listening is available for EPUB. Bookstr owns speech synthesis, voices, and reading progress; Radio Guaka does not copy book files or synthesize an audio file.

Both apps need the integration update. Radio connects explicitly to `app.bookstr/app.bookstr.NarrationService` using Android MediaBrowser. The catalog root is `bookstr-downloaded-books`; stable media IDs are Bookstr download identifiers. Description extras are `app.bookstr.extra.FORMAT`, `app.bookstr.extra.PLAYABLE`, and `app.bookstr.extra.OPEN_READER`.

Selecting an EPUB sends the immutable reader PendingIntent supplied by Bookstr. Its creator must be `app.bookstr`. Bookstr opens its reader and starts narration when the EPUB reader is ready. Return to Radio Guaka to control the active narration through Bookstr’s media session. Unsupported formats stay visible without a listening action.

Bookstr narration is played by the phone through Bookstr's Android media session. It cannot control or pause a Music Assistant remote speaker; playback on that remote speaker remains owned by Music Assistant.

The connection stays on the phone. Nostr favorites and relays do not carry downloaded books, book text, or reading progress through this integration.

Validation uses disposable emulators. Never run connected-test cleanup on a personal device; update installed apps with `adb install -r` only after checking signatures.
