## Purpose
The backend uses Nostr challenge verification and friend-based access checks to protect private media endpoints while still allowing public static radio playback.

## Requirements

### Requirement: Nostr challenge authentication
The backend SHALL authenticate users by issuing short-lived signed challenges and verifying signed Nostr events that echo those challenges.

#### Scenario: Challenge issuance
- **WHEN** a client posts to `/auth/nostr/challenge`
- **THEN** the backend returns a signed challenge token that expires after a short time window.

#### Scenario: Successful verification
- **WHEN** a client posts a valid challenge and a Nostr event whose content matches `auth:<challenge>`
- **THEN** the backend verifies the event signature
- **AND** creates an HTTP-only session cookie.

#### Scenario: Invalid verification
- **WHEN** the challenge is expired, tampered with, or the event signature is invalid
- **THEN** the backend rejects the request with an unauthorized response.

### Requirement: Session lookup
Protected backend routes SHALL require a known, unexpired session cookie.

#### Scenario: Missing session
- **WHEN** a request to an authenticated route has no valid session cookie
- **THEN** the backend responds with unauthorized.

#### Scenario: Expired session
- **WHEN** a request presents a session whose expiration is in the past
- **THEN** the backend responds with unauthorized.

### Requirement: Friend-gated media access
Private Plex and share-management routes SHALL require both authentication and friend access.

#### Scenario: Authorized friend
- **WHEN** an authenticated session belongs to a pubkey allowed by the contacts policy
- **THEN** the user can browse libraries, search tracks, stream tracks, and create or revoke share links.

#### Scenario: Authenticated non-friend
- **WHEN** an authenticated session is not allowed by the contacts policy
- **THEN** friend-gated routes respond with forbidden.

#### Scenario: Policy lookup failure
- **WHEN** the contacts policy cannot determine access
- **THEN** friend-gated routes respond with a bad gateway error instead of granting access.

### Requirement: User status
The backend SHALL expose the current authenticated user's pubkey, friend access state, and session expiration.

#### Scenario: Me request
- **WHEN** an authenticated client requests `GET /me`
- **THEN** the response includes the session pubkey, friend access boolean, and expiration time.
