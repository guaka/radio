package httpapi

import (
	"encoding/json"
	"log"
	"net/http"
)

// handleMusicExportConfig exposes optional music integration config for frontend probes.
func (h *Handler) handleMusicExportConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if h.appleDev != nil {
		h.withFriendHTTP(http.HandlerFunc(h.writeMusicExportConfig)).ServeHTTP(w, r)
		return
	}
	h.writeMusicExportConfig(w, r)
}

func (h *Handler) writeMusicExportConfig(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")

	out := map[string]string{}
	if h.cfg.SpotifyClientID != "" {
		out["spotifyClientId"] = h.cfg.SpotifyClientID
	}
	if h.appleDev != nil {
		tok, err := h.appleDev.Token()
		if err != nil {
			log.Printf("music-export-config: apple token: %v", err)
		} else {
			out["appleDeveloperToken"] = tok
		}
	}
	if h.cfg.MusicAssistantBaseURL != "" {
		out["musicAssistantBaseUrl"] = h.cfg.MusicAssistantBaseURL
	}
	if h.cfg.MusicAssistantToken != "" {
		out["musicAssistantTokenMode"] = "server"
	}
	_ = json.NewEncoder(w).Encode(out)
}
