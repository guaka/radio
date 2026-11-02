## Purpose
The optional YouTube tab resolves a single YouTube video into playable audio through backend-assisted resolution and a constrained same-origin stream proxy.

## Requirements

### Requirement: Video resolution
The backend SHALL resolve an 11-character YouTube video identifier into playable audio metadata and a proxy URL when a source is available.

#### Scenario: Missing video id
- **WHEN** a client requests `GET /api/youtube/resolve` without `videoId`
- **THEN** the backend responds with bad request.

#### Scenario: Invalid video id length
- **WHEN** a client requests `GET /api/youtube/resolve` with a video id that is not 11 characters
- **THEN** the backend responds with bad request.

#### Scenario: Successful resolution
- **WHEN** the backend resolves a video through direct YouTube extraction or a public mirror
- **THEN** the response includes `ok`, `videoId`, `streamUrl`, `proxyUrl`, title, artist, artwork, source API, resolution attempts, and external links.

#### Scenario: Failed resolution
- **WHEN** all resolution sources fail
- **THEN** the backend responds with bad gateway and includes resolution attempts and external check links.

### Requirement: Resolution fallback order
The resolver SHALL try direct YouTube extraction before public Piped and Invidious mirror APIs.

#### Scenario: Direct source succeeds
- **WHEN** direct YouTube extraction produces a stream URL
- **THEN** mirror APIs are not required for the final result.

#### Scenario: Direct source fails
- **WHEN** direct YouTube extraction fails
- **THEN** the resolver tries configured Piped API instances and then configured Invidious instances.

### Requirement: External diagnostics links
The backend SHALL provide human-checkable external links for YouTube, Piped, and Invidious mirrors.

#### Scenario: External links request
- **WHEN** a client requests `GET /api/youtube/external-links?videoId=<id>` with a valid id
- **THEN** the response includes links for the original YouTube page and configured public mirror checks.

### Requirement: Constrained stream proxy
The YouTube stream proxy SHALL only proxy HTTPS audio sources from allowed upstream host families and SHALL preserve seek-related headers.

#### Scenario: Missing stream URL
- **WHEN** a client requests `/api/youtube/stream` without `url`
- **THEN** the backend responds with bad request.

#### Scenario: Disallowed upstream host
- **WHEN** a client requests `/api/youtube/stream` for an HTTPS host outside the allowed upstream host families
- **THEN** the backend responds with bad request.

#### Scenario: Allowed upstream host
- **WHEN** a client requests `/api/youtube/stream` for an allowed upstream host
- **THEN** the backend proxies GET or HEAD to the upstream and copies media response headers.

#### Scenario: Range request
- **WHEN** a client sends a `Range` header to `/api/youtube/stream`
- **THEN** the backend forwards that header upstream.
