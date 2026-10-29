## Purpose
Radio Guaka provides a browser-first internet radio player that can run from static assets and expose optional richer pleXtr tabs when the Go backend is available.

## Requirements

### Requirement: Static site boot
The system SHALL serve a usable radio player from the files in `public/` without requiring a frontend build step.

#### Scenario: User opens the home page
- **WHEN** a browser requests `/`
- **THEN** the application loads `public/index.html`
- **AND** channel data is available from `public/channels.js`.

#### Scenario: Unknown non-API route
- **WHEN** a browser requests an unknown route that is not reserved for backend APIs
- **THEN** the server returns the static application shell.

### Requirement: Channel playback controls
The radio player SHALL allow users to change channels, control volume, and pause playback using both visible controls and keyboard shortcuts.

#### Scenario: Keyboard channel navigation
- **WHEN** the user presses the left or right arrow key
- **THEN** the player selects the previous or next configured channel.

#### Scenario: Keyboard volume control
- **WHEN** the user presses the up or down arrow key
- **THEN** the player raises or lowers volume.

#### Scenario: Keyboard pause
- **WHEN** the user presses the space key
- **THEN** playback pauses or stops without navigating away.

### Requirement: Development freshness
The backend SHALL prevent stale local-development assets by disabling browser caching for HTML and static assets served through the Go server.

#### Scenario: HTML response
- **WHEN** the server responds with an HTML file
- **THEN** it sends no-store cache headers.

#### Scenario: Static asset response
- **WHEN** the server responds with an existing non-HTML static asset
- **THEN** it sends no-store cache headers before serving the file.

### Requirement: Runtime status
The backend SHALL expose basic health and build-info endpoints for local and deployed diagnostics.

#### Scenario: Health check
- **WHEN** a client requests `GET /api/health`
- **THEN** the response includes `ok`, current time, owner configuration, Plex configuration, direct Plex proxy readiness, and Wrapster readiness flags.

#### Scenario: Build info
- **WHEN** a client requests `GET /api/build-info`
- **THEN** the response includes best-effort modification times for Go sources and public HTML assets.
