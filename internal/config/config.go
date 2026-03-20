package config

import (
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Addr              string
	SessionSecret     string
	OwnerPubKey       string
	NostrRelays       []string
	NostrCacheTTL     time.Duration
	PlexBaseURL       string
	PlexToken         string
	PlexClientID      string
	ShareTokenBytes   int
	SessionCookieName string
}

func Load() Config {
	ttl := durationEnv("NOSTR_CONTACTS_TTL", 10*time.Minute)
	shareBytes := intEnv("SHARE_TOKEN_BYTES", 24)
	if shareBytes < 16 {
		shareBytes = 16
	}

	relays := splitCSV(os.Getenv("NOSTR_RELAYS"))
	if len(relays) == 0 {
		relays = []string{
			"wss://relay.nomadwiki.org",
			"wss://relay.trustroots.org",
		}
	}

	return Config{
		Addr:              envDefault("ADDR", ":8080"),
		SessionSecret:     envDefault("SESSION_SECRET", "dev-change-me"),
		OwnerPubKey:       strings.TrimSpace(os.Getenv("OWNER_PUBKEY")),
		NostrRelays:       relays,
		NostrCacheTTL:     ttl,
		PlexBaseURL:       strings.TrimRight(strings.TrimSpace(os.Getenv("PLEX_BASE_URL")), "/"),
		PlexToken:         strings.TrimSpace(os.Getenv("PLEX_TOKEN")),
		PlexClientID:      envDefault("PLEX_CLIENT_ID", "plextr-go-gateway"),
		ShareTokenBytes:   shareBytes,
		SessionCookieName: envDefault("SESSION_COOKIE_NAME", "plextr_session"),
	}
}

func envDefault(key, fallback string) string {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	return v
}

func intEnv(key string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback
	}
	n, err := strconv.Atoi(raw)
	if err != nil {
		return fallback
	}
	return n
}

func durationEnv(key string, fallback time.Duration) time.Duration {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback
	}
	d, err := time.ParseDuration(raw)
	if err != nil {
		return fallback
	}
	return d
}

func splitCSV(raw string) []string {
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}
