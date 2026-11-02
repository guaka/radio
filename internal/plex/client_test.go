package plex

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"
)

func TestWrapsterPlexSearchAndStream(t *testing.T) {
	key := nostr.GeneratePrivateKey()
	var gotSearchAuth, gotStreamAuth, gotRange string

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/media/api/services/plex/search":
			gotSearchAuth = r.Header.Get("Authorization")
			if r.URL.Query().Get("q") != "matrix" {
				t.Fatalf("unexpected query %q", r.URL.RawQuery)
			}
			writeTestJSON(t, w, map[string]any{
				"service": "plex",
				"items": []map[string]string{{
					"id":        "track-1",
					"name":      "Clubbed to Death",
					"type":      "track",
					"summary":   "Rob Dougan",
					"stream_id": "stream-1",
				}},
			})
		case "/media/api/services/plex/stream/stream-1":
			gotStreamAuth = r.Header.Get("Authorization")
			gotRange = r.Header.Get("Range")
			w.Header().Set("Content-Type", "audio/mpeg")
			w.WriteHeader(http.StatusPartialContent)
			_, _ = w.Write([]byte("audio"))
		default:
			t.Fatalf("unexpected path %s", r.URL.Path)
		}
	}))
	defer upstream.Close()

	client := NewClient("", "", "plextr-test", upstream.URL, key)
	tracks, err := client.SearchTracks(t.Context(), "wrapster-plex", "matrix")
	if err != nil {
		t.Fatalf("SearchTracks returned error: %v", err)
	}
	if len(tracks) != 1 || tracks[0].RatingKey != "stream-1" || tracks[0].Title != "Clubbed to Death" || tracks[0].Artist != "Rob Dougan" {
		t.Fatalf("unexpected tracks: %+v", tracks)
	}

	req := httptest.NewRequest(http.MethodGet, "/stream/stream-1", nil)
	req.Header.Set("Range", "bytes=0-3")
	rec := httptest.NewRecorder()
	if err := client.StreamTrack(t.Context(), rec, req, tracks[0].RatingKey); err != nil {
		t.Fatalf("StreamTrack returned error: %v", err)
	}
	if rec.Code != http.StatusPartialContent || strings.TrimSpace(rec.Body.String()) != "audio" {
		t.Fatalf("unexpected stream response status=%d body=%q", rec.Code, rec.Body.String())
	}
	if !strings.HasPrefix(gotSearchAuth, "Nostr ") || !strings.HasPrefix(gotStreamAuth, "Nostr ") {
		t.Fatalf("missing NIP-98 auth search=%q stream=%q", gotSearchAuth, gotStreamAuth)
	}
	if gotRange != "bytes=0-3" {
		t.Fatalf("expected forwarded Range header, got %q", gotRange)
	}
}

func writeTestJSON(t *testing.T, w http.ResponseWriter, body any) {
	t.Helper()
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(body); err != nil {
		t.Fatalf("failed to encode JSON: %v", err)
	}
}
