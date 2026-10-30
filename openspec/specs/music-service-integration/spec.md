## Purpose
Radio Guaka includes optional helpers for exporting or mapping listening data through Spotify, Apple Music, and Music Assistant without requiring those integrations for baseline radio playback.

## Requirements

### Requirement: Music export configuration
The backend SHALL expose client-safe music export configuration without leaking private server secrets.

#### Scenario: Export config request
- **WHEN** a client requests `GET /api/music-export-config`
- **THEN** the backend returns public configuration needed by the export UI.

#### Scenario: Apple developer token configured
- **WHEN** Apple Music team id, key id, and private key path are configured successfully
- **THEN** the backend can provide a MusicKit developer token through the export configuration flow to authenticated friends.

### Requirement: Music service proxying
The backend SHALL proxy selected Spotify, Apple Music, and Music Assistant API requests through same-origin endpoints.

#### Scenario: Spotify account proxy
- **WHEN** a client requests `/api/music-proxy/spotify-accounts/<path>`
- **THEN** the backend forwards the request to the Spotify accounts API path.

#### Scenario: Spotify API proxy
- **WHEN** a client requests `/api/music-proxy/spotify-api/<path>`
- **THEN** the backend forwards the request to the Spotify Web API path.

#### Scenario: Apple Music proxy
- **WHEN** a client requests `/api/music-proxy/applemusic/<path>`
- **THEN** the backend forwards the request to the Apple Music API path.

#### Scenario: Music Assistant proxy
- **WHEN** an authenticated friend requests `/api/music-proxy/music-assistant/<path>`
- **THEN** the backend forwards the request to the configured Music Assistant base URL.

### Requirement: Music Assistant exploration
The backend SHALL provide lightweight Music Assistant probe and quick-map endpoints for validating local integration behavior.

#### Scenario: Probe request
- **WHEN** a client requests `/api/music-assistant/probe`
- **THEN** the backend validates Music Assistant connectivity using the configured base URL and optional token.

#### Scenario: Quick map request
- **WHEN** a client requests `/api/music-assistant/quick-map`
- **THEN** the backend returns static example operations and payloads without contacting Music Assistant.

#### Scenario: Missing Music Assistant base URL
- **WHEN** Music Assistant endpoints require a base URL but none is configured
- **THEN** the backend reports configuration failure instead of attempting an arbitrary outbound request.
