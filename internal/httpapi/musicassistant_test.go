package httpapi

import (
	"github.com/guaka/radio-guaka/internal/config"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestMusicAssistantRoutesRequireSession(t *testing.T) {
	h := &Handler{cfg: config.Config{SessionCookieName: "session", MusicAssistantToken: "secret"}}
	mux := http.NewServeMux()
	h.Register(mux)
	for _, path := range []string{"/api/music-assistant/probe", "/api/music-proxy/music-assistant/api/players"} {
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, httptest.NewRequest(http.MethodGet, path, nil))
		if w.Code != http.StatusUnauthorized {
			t.Fatalf("%s: got %d", path, w.Code)
		}
		if strings.Contains(w.Body.String(), "secret") {
			t.Fatal("token exposed")
		}
	}
}

func TestMusicAssistantProxyForwardsBodyAndFiltersHeaders(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil || string(body) != `{"command":"players/all"}` {
			t.Errorf("unexpected request body: %s, %v", body, err)
		}
		if r.URL.Path != "/api/command" || r.URL.RawQuery != "test=1" {
			t.Errorf("unexpected URL: %s", r.URL)
		}
		if r.Header.Get("Authorization") != "Bearer server-token" {
			t.Error("missing server token")
		}
		w.Header().Set("Set-Cookie", "upstream=secret")
		w.Header().Set("Connection", "keep-alive")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer upstream.Close()
	h := &Handler{cfg: config.Config{MusicAssistantBaseURL: upstream.URL, MusicAssistantToken: "server-token"}}
	w := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodPost, "/api/music-proxy/music-assistant/api/command?test=1", strings.NewReader(`{"command":"players/all"}`))
	h.handleMusicAssistantAPIProxy(w, r)
	if w.Code != http.StatusOK || w.Body.String() != `{"ok":true}` {
		t.Fatalf("unexpected response: %d %s", w.Code, w.Body)
	}
	if w.Header().Get("Set-Cookie") != "" || w.Header().Get("Connection") != "" || w.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Fatal("unsafe response headers forwarded")
	}
}
