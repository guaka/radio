package httpapi

import (
	"log"
	"net/http"
	"strings"
)

func (h *Handler) handlePlexProxy(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if r.URL.Query().Get("X-Plex-Token") == "" && r.Header.Get("X-Plex-Token") == "" {
		h.withFriendHTTP(http.HandlerFunc(h.proxyPlexPath)).ServeHTTP(w, r)
		return
	}
	h.proxyPlexPath(w, r)
}

func (h *Handler) proxyPlexPath(w http.ResponseWriter, r *http.Request) {
	plexPath := strings.TrimPrefix(r.URL.Path, "/api/plex")
	if strings.TrimSpace(plexPath) == "" {
		http.Error(w, "missing plex path", http.StatusBadRequest)
		return
	}
	if err := h.plex.ProxyPath(r.Context(), w, r, plexPath, r.URL.RawQuery); err != nil {
		log.Printf("plex proxy error path=%s err=%v", plexPath, err)
		http.Error(w, "plex proxy error", http.StatusBadGateway)
	}
}
