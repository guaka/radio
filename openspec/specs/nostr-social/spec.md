## Purpose
Radio Guaka uses Nostr for social features around pleXtr and radio listening, including chat, favorites, reactions, and identity-backed browser sessions.

## Requirements

### Requirement: Shared Nostr room
The chat experience SHALL publish and read short text notes that identify the shared pleXtr/radio room using Nostr tags.

#### Scenario: Chat message publication
- **WHEN** a user posts a chat message
- **THEN** the event uses a short text note kind
- **AND** includes tags that associate it with the pleXtr and Radio Guaka room.

#### Scenario: Chat message discovery
- **WHEN** the chat UI loads messages
- **THEN** it subscribes to the configured community relays and filters for the room tags.

### Requirement: Nostr key options
The chat UI SHALL support NIP-07 signing, manual private-key entry, and generated anonymous keys.

#### Scenario: Browser extension available
- **WHEN** a NIP-07 extension is available and selected
- **THEN** signing happens through the extension without exposing the private key to the page.

#### Scenario: Manual or generated key
- **WHEN** a user enters or generates a key in the fallback flow
- **THEN** the frontend stores the key material locally for later chat use.

### Requirement: Social event lifecycle
The Nostr social layer SHALL support reactions and deletions using standard Nostr event kinds and SHALL limit ordinary chat message lifetime.

#### Scenario: User reacts
- **WHEN** a user reacts to a message
- **THEN** the client emits a reaction event referencing the target message.

#### Scenario: User deletes
- **WHEN** a user deletes one of their messages
- **THEN** the client emits a deletion request event referencing the target message.

#### Scenario: Message expiration
- **WHEN** a chat message is created
- **THEN** the event includes an expiration tag for approximately 30 days in the future.

### Requirement: Relay noise handling
The frontend SHALL suppress known non-fatal Nostr relay startup errors so chat startup and playback remain usable.

#### Scenario: Known relay transport error
- **WHEN** the browser reports the known internal relay transport DNS error during chat startup
- **THEN** the application suppresses the known error instead of allowing it to break the page.
