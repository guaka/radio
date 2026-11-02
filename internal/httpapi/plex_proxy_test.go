package httpapi

import (
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/guaka/radio-guaka/internal/config"
	"github.com/guaka/radio-guaka/internal/plex"
	"github.com/guaka/radio-guaka/internal/store"
)

func TestPlexProxyDoesNotExposeServerToken(t *testing.T) {
	var received []string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received = append(received, r.URL.Query().Get("X-Plex-Token"))
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()
	s, err := store.New(filepath.Join(t.TempDir(), "state.sqlite3"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	h := &Handler{cfg: config.Config{SessionCookieName: "session"}, store: s,
		plex: plex.NewClient(upstream.URL, "server-secret", "test", "", "")}
	mux := http.NewServeMux()
	h.Register(mux)
	for _, tc := range []struct {
		query, header string
		want          int
	}{
		{"", "", http.StatusUnauthorized},
		{"?X-Plex-Token=caller-token", "", http.StatusOK},
		{"", "header-token", http.StatusOK},
	} {
		r := httptest.NewRequest(http.MethodGet, "/api/plex/library/sections"+tc.query, nil)
		r.Header.Set("X-Plex-Token", tc.header)
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, r)
		if w.Code != tc.want {
			t.Fatalf("got %d, want %d", w.Code, tc.want)
		}
	}
	if len(received) != 2 || received[0] != "caller-token" || received[1] != "header-token" {
		t.Fatalf("unexpected upstream credentials: %v", received)
	}
}
