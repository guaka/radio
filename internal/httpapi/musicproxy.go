package httpapi

import (
	"io"
	"net/http"
	"strings"
	"time"
)

// musicProxyMaxBody limits request/response bodies forwarded through the music API proxies.
const musicProxyMaxBody = 32 << 20 // 32 MiB

func (h *Handler) handleSpotifyAccountsProxy(w http.ResponseWriter, r *http.Request) {
	h.forwardMusicProxy(w, r, "https://accounts.spotify.com", "/api/music-proxy/spotify-accounts")
}

func (h *Handler) handleSpotifyAPIProxy(w http.ResponseWriter, r *http.Request) {
	h.forwardMusicProxy(w, r, "https://api.spotify.com", "/api/music-proxy/spotify-api")
}

func (h *Handler) handleAppleMusicAPIProxy(w http.ResponseWriter, r *http.Request) {
	h.forwardMusicProxy(w, r, "https://api.music.apple.com", "/api/music-proxy/applemusic")
}

func (h *Handler) forwardMusicProxy(w http.ResponseWriter, r *http.Request, base string, stripPrefix string) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, Music-User-Token, Accept")

	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	rest := strings.TrimPrefix(r.URL.Path, stripPrefix)
	if rest == "" || !strings.HasPrefix(rest, "/") {
		rest = "/" + strings.TrimPrefix(rest, "/")
	}
	target := strings.TrimSuffix(base, "/") + rest
	if r.URL.RawQuery != "" {
		target += "?" + r.URL.RawQuery
	}

	req, err := http.NewRequestWithContext(r.Context(), r.Method, target, nil)
	if err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	// Forward selected headers (tokens are bearer / Music-User-Token).
	for _, k := range []string{"Authorization", "Content-Type", "Accept", "Accept-Language", "Music-User-Token"} {
		if v := r.Header.Get(k); v != "" {
			req.Header.Set(k, v)
		}
	}

	if r.Body != nil && (r.Method == http.MethodPost || r.Method == http.MethodPut || r.Method == http.MethodPatch) {
		req.Body = io.NopCloser(io.LimitReader(r.Body, musicProxyMaxBody))
	}

	resp, err := musicAPIClient.Do(req)
	if err != nil {
		http.Error(w, "upstream error", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	for k, v := range resp.Header {
		if !musicResponseHeaderAllowed(k) {
			continue
		}
		for _, vv := range v {
			w.Header().Add(k, vv)
		}
	}
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, io.LimitReader(resp.Body, musicProxyMaxBody))
}

var musicAPIClient = &http.Client{Timeout: 15 * time.Second, CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
	return http.ErrUseLastResponse
}}

func musicResponseHeaderAllowed(name string) bool {
	switch strings.ToLower(name) {
	case "content-type", "cache-control", "etag", "last-modified", "retry-after":
		return true
	default:
		return false
	}
}
