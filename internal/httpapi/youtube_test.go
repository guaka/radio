package httpapi

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
)

func TestYouTubeStreamHostValidation(t *testing.T) {
	for _, tc := range []struct {
		url     string
		allowed bool
	}{
		{"https://rr1.googlevideo.com/videoplayback", true},
		{"https://pipedproxy.kavin.rocks/videoplayback", true},
		{"https://googlevideo.com.evil.example/", false},
		{"https://evil-googlevideo.com/", false},
		{"https://pipedproxy.evil.example/", false},
		{"https://user:pass@rr1.googlevideo.com/", false},
		{"https://rr1.googlevideo.com:8443/", false},
		{"http://rr1.googlevideo.com/", false},
		{"https://127.0.0.1/", false},
	} {
		u, err := url.Parse(tc.url)
		if err != nil {
			t.Fatal(err)
		}
		if got := allowedYouTubeStreamURL(u); got != tc.allowed {
			t.Errorf("%s: allowed=%v", tc.url, got)
		}
	}
}

func TestYouTubeRejectsInvalidVideoIDs(t *testing.T) {
	h := &Handler{}
	for _, endpoint := range []http.HandlerFunc{h.handleYouTubeExternalLinks, h.handleYouTubeResolve} {
		for _, id := range []string{"", "too-short", "1234567890/", "abcdefghij?"} {
			w := httptest.NewRecorder()
			endpoint(w, httptest.NewRequest(http.MethodGet, "/?videoId="+url.QueryEscape(id), nil))
			if w.Code != http.StatusBadRequest {
				t.Errorf("%q: got %d", id, w.Code)
			}
		}
	}
}
