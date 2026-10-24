## Purpose
The Go backend provides same-origin media APIs for Plex browsing, streaming, Plex PIN authentication, and revokable public share links while keeping upstream tokens server-side.

## Requirements

### Requirement: Plex configuration modes
The media gateway SHALL support direct Plex access and Wrapster-backed Plex access based on environment configuration.

#### Scenario: Direct Plex configured
- **WHEN** `PLEX_BASE_URL` and `PLEX_TOKEN` are configured
- **THEN** the backend can list music libraries, search tracks, proxy Plex paths, and stream track media directly from Plex.

#### Scenario: Wrapster configured
- **WHEN** `WRAPSTER_MEDIA_URL` and `WRAPSTER_MEDIA_NSEC` are configured
- **THEN** friend-gated library, search, and stream routes use Wrapster media gateway endpoints with NIP-98 authorization.

#### Scenario: No media source configured
- **WHEN** no supported media source is configured
- **THEN** media listing returns empty results or media streaming fails without exposing private credentials.

### Requirement: Music library browsing
Friend-gated library browsing SHALL return music-oriented libraries only.

#### Scenario: Direct Plex libraries
- **WHEN** a friend requests `GET /libraries` in direct Plex mode
- **THEN** the backend returns Plex libraries whose type is artist.

#### Scenario: Wrapster library placeholder
- **WHEN** a friend requests `GET /libraries` in Wrapster mode
- **THEN** the backend returns a Wrapster Plex library entry that can be used for search.

### Requirement: Track search
Friend-gated track search SHALL require a library identifier and return normalized track records.

#### Scenario: Missing library identifier
- **WHEN** a friend requests `GET /tracks` without `libraryId`
- **THEN** the backend responds with bad request.

#### Scenario: Direct Plex search
- **WHEN** a friend requests `GET /tracks` with a library identifier and optional query in direct Plex mode
- **THEN** the backend returns track records with rating key, title, and artist.

#### Scenario: Wrapster search
- **WHEN** a friend requests `GET /tracks` with a query in Wrapster mode
- **THEN** the backend searches Wrapster Plex and maps stream identifiers into track rating keys.

### Requirement: Media streaming
Friend-gated streaming SHALL proxy media bytes and preserve range requests for seek support.

#### Scenario: Track stream
- **WHEN** a friend requests `GET /stream/<trackId>`
- **THEN** the backend streams the corresponding media through the configured media source.

#### Scenario: Range request
- **WHEN** the client sends a `Range` header while streaming
- **THEN** the backend forwards the range request to the upstream media source.

#### Scenario: Upstream headers
- **WHEN** upstream media responds with useful media headers
- **THEN** the backend copies those headers except sensitive cookie headers.

### Requirement: Same-origin Plex proxy
The backend SHALL provide a same-origin Plex proxy for GET and HEAD requests when a Plex base URL is configured.

#### Scenario: Proxy request
- **WHEN** a client requests `/api/plex/<path>` with GET or HEAD
- **THEN** the backend proxies the request to the configured Plex base URL with the caller's Plex token, or with the server token only for authenticated friends.

#### Scenario: Unsupported method
- **WHEN** a client uses a method other than GET or HEAD for `/api/plex/<path>`
- **THEN** the backend responds with method not allowed.

### Requirement: Revokable share links
Friends SHALL be able to create and revoke public share URLs for tracks, and public visitors SHALL only stream active share links.

#### Scenario: Create share link
- **WHEN** a friend posts to `/share/song/<trackId>`
- **THEN** the backend creates an opaque token and returns a public `/s/<token>` URL.

#### Scenario: Revoke share link
- **WHEN** a friend deletes `/share/<token>`
- **THEN** the backend marks the share link revoked.

#### Scenario: Public active share playback
- **WHEN** a visitor requests `/s/<token>` for an active share link
- **THEN** the backend streams the referenced track without requiring a session.

#### Scenario: Public revoked share playback
- **WHEN** a visitor requests `/s/<token>` for a missing or revoked share link
- **THEN** the backend responds with not found.

### Requirement: Plex PIN authentication
Authenticated users SHALL be able to start and poll Plex PIN authentication through backend endpoints.

#### Scenario: Start PIN flow
- **WHEN** an authenticated user posts to `/auth/plex/pin/start`
- **THEN** the backend starts a Plex PIN flow and returns the provider response.

#### Scenario: Poll PIN flow
- **WHEN** an authenticated user posts a PIN id to `/auth/plex/pin/poll`
- **THEN** the backend polls Plex and returns the resulting token when available.
