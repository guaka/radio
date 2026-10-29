package httpapi

import (
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type musicAssistantOperation struct {
	Name        string `json:"name"`
	Method      string `json:"method"`
	Path        string `json:"path"`
	Description string `json:"description"`
	ExampleBody any    `json:"exampleBody,omitempty"`
}

func (h *Handler) handleMusicAssistantQuickMap(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":             true,
		"generatedAt":    time.Now().UTC().Format(time.RFC3339),
		"baseConfigured": h.cfg.MusicAssistantBaseURL != "",
		"operations": []musicAssistantOperation{
			{
				Name:        "players",
				Method:      "GET",
				Path:        "/api/players",
				Description: "List available Music Assistant players and status.",
			},
			{
				Name:        "search",
				Method:      "POST",
				Path:        "/api/command",
				Description: "Command-style search call. Verify command name with /api-docs on your MA instance.",
				ExampleBody: map[string]any{
					"message_id": 1,
					"command":    "music/search",
					"args": map[string]any{
						"query": "boards of canada",
						"limit": 5,
					},
				},
			},
			{
				Name:        "queue_play",
				Method:      "POST",
				Path:        "/api/command",
				Description: "Queue/play command. Exact command and args can vary by MA version; confirm in /api-docs.",
				ExampleBody: map[string]any{
					"message_id": 2,
					"command":    "player_queues/play_media",
					"args": map[string]any{
						"queue_id": "REPLACE_ME",
						"media": []map[string]any{
							{"item_id": "REPLACE_ME", "media_type": "track", "provider": "library"},
						},
					},
				},
			},
		},
		"notes": []string{
			"Use this as a quick-start contract; your MA api-docs is source of truth.",
			"Proxy calls are exposed under /api/music-proxy/music-assistant/* in this server.",
		},
	})
}

func (h *Handler) handleMusicAssistantProbe(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if strings.TrimSpace(h.cfg.MusicAssistantBaseURL) == "" {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":         false,
			"configured": false,
			"error":      "MUSIC_ASSISTANT_BASE_URL is not set",
		})
		return
	}

	base := strings.TrimRight(strings.TrimSpace(h.cfg.MusicAssistantBaseURL), "/")
	probe := func(path string) map[string]any {
		req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, base+path, nil)
		if err != nil {
			return map[string]any{"path": path, "ok": false, "error": err.Error()}
		}
		if h.cfg.MusicAssistantToken != "" {
			req.Header.Set("Authorization", "Bearer "+h.cfg.MusicAssistantToken)
		}
		resp, err := musicAPIClient.Do(req)
		if err != nil {
			return map[string]any{"path": path, "ok": false, "error": err.Error()}
		}
		defer resp.Body.Close()
		return map[string]any{
			"path":   path,
			"ok":     resp.StatusCode >= 200 && resp.StatusCode < 300,
			"status": resp.StatusCode,
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"ok":         true,
		"configured": true,
		"baseUrl":    base,
		"tokenSet":   h.cfg.MusicAssistantToken != "",
		"checks": []map[string]any{
			probe("/api-docs"),
			probe("/api/players"),
		},
		"wsUrlHint": toWSURL(base) + "/ws",
	})
}

func toWSURL(base string) string {
	u, err := url.Parse(base)
	if err != nil || u == nil {
		return base
	}
	switch u.Scheme {
	case "https":
		u.Scheme = "wss"
	case "http":
		u.Scheme = "ws"
	}
	return strings.TrimRight(u.String(), "/")
}

func (h *Handler) handleMusicAssistantAPIProxy(w http.ResponseWriter, r *http.Request) {
	h.forwardMusicAssistantProxy(w, r, "/api/music-proxy/music-assistant")
}

func (h *Handler) forwardMusicAssistantProxy(w http.ResponseWriter, r *http.Request, stripPrefix string) {
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	base := strings.TrimSpace(h.cfg.MusicAssistantBaseURL)
	if base == "" {
		http.Error(w, "music assistant not configured", http.StatusServiceUnavailable)
		return
	}

	rest := strings.TrimPrefix(r.URL.Path, stripPrefix)
	if rest == "" || !strings.HasPrefix(rest, "/") {
		rest = "/" + strings.TrimPrefix(rest, "/")
	}
	target := strings.TrimRight(base, "/") + rest
	if r.URL.RawQuery != "" {
		target += "?" + r.URL.RawQuery
	}

	req, err := http.NewRequestWithContext(r.Context(), r.Method, target, nil)
	if err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	for _, k := range []string{"Content-Type", "Accept", "Accept-Language"} {
		if v := strings.TrimSpace(r.Header.Get(k)); v != "" {
			req.Header.Set(k, v)
		}
	}
	// Use server-side token by default to avoid exposing tokens in browser state.
	if tok := strings.TrimSpace(h.cfg.MusicAssistantToken); tok != "" {
		req.Header.Set("Authorization", "Bearer "+tok)
	} else if v := strings.TrimSpace(r.Header.Get("Authorization")); v != "" {
		req.Header.Set("Authorization", v)
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
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, io.LimitReader(resp.Body, musicProxyMaxBody))
}
