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

### Requirement: External Nostr signers
The web player SHALL support browser, remote, and Android signers. Its embedded chat SHALL use the same connected signer.

#### Scenario: Browser extension available
- **WHEN** a NIP-07 extension is selected
- **THEN** the extension signs events for the connected account.

#### Scenario: Remote or Android signer selected
- **WHEN** a user connects a NIP-46 or NIP-55 signer in Connections
- **THEN** the player sends signing requests to that signer and verifies its returned events.

#### Scenario: Prior installation saved a private key
- **WHEN** the current player or chat starts with a legacy private key in browser storage
- **THEN** it removes that key and uses a connected external signer for future events.

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
