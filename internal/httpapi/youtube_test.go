package httpapi

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"strings"
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
		{"https://rr1.googlevideo.com/#fragment", false},
		{"https://rr1.googlevideo.com./", false},
		{"https://rr1.googlevideo.com:443/videoplayback?token=a%2Fb", true},
		{"https://rr1.googlevideo.com@127.0.0.1/", false},
		{"https://rr1.googlevideo.com.evil.example/videoplayback", false},
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

func TestYouTubeDialerRejectsNonPublicDNSAnswers(t *testing.T) {
	for _, address := range []string{
		"127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254",
		"0.0.0.0", "0.1.2.3", "100.64.0.1", "192.0.2.1", "198.18.0.1", "224.0.0.1", "240.0.0.1",
		"::1", "::", "fc00::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1", "2001:db8::1",
	} {
		t.Run(address, func(t *testing.T) {
			called := false
			dial := youtubeStreamDialer(func(context.Context, string, string) ([]netip.Addr, error) {
				// Even a mixed public/private DNS answer must fail before any connection.
				return []netip.Addr{netip.MustParseAddr("8.8.8.8"), netip.MustParseAddr(address)}, nil
			}, func(context.Context, string, string) (net.Conn, error) {
				called = true
				return nil, nil
			})
			if _, err := dial(context.Background(), "tcp", "rr1.googlevideo.com:443"); err == nil || called {
				t.Fatalf("unsafe address accepted: err=%v dialed=%v", err, called)
			}
		})
	}
}

func TestYouTubeDialerPinsPublicIPAndRejectsInvalidPortAndDNSFailures(t *testing.T) {
	lookups := 0
	var dialed string
	var answers = []netip.Addr{netip.MustParseAddr("8.8.8.8")}
	var lookupErr error
	dial := youtubeStreamDialer(func(context.Context, string, string) ([]netip.Addr, error) {
		lookups++
		return answers, lookupErr
	}, func(_ context.Context, _, address string) (net.Conn, error) {
		dialed = address
		return nil, nil
	})
	if _, err := dial(context.Background(), "tcp", "rr1.googlevideo.com:443"); err != nil || dialed != "8.8.8.8:443" || lookups != 1 {
		t.Fatalf("must dial validated IP without resolving twice: %s %v %d", dialed, err, lookups)
	}
	if _, err := dial(context.Background(), "tcp", "rr1.googlevideo.com:80"); err == nil || lookups != 1 {
		t.Fatal("invalid port accepted")
	}
	answers = nil
	if _, err := dial(context.Background(), "tcp", "rr1.googlevideo.com:443"); err == nil {
		t.Fatal("empty DNS response accepted")
	}
	lookupErr = errors.New("DNS failed")
	if _, err := dial(context.Background(), "tcp", "rr1.googlevideo.com:443"); !errors.Is(err, lookupErr) {
		t.Fatalf("DNS failure was not propagated: %v", err)
	}
}

type streamTestTransport func(*http.Request) (*http.Response, error)

func (f streamTestTransport) RoundTrip(req *http.Request) (*http.Response, error) { return f(req) }

func TestYouTubeClientValidatesEveryRedirect(t *testing.T) {
	for _, target := range []string{
		"https://127.0.0.1/private", "http://rr1.googlevideo.com/audio", "https://evil.example/",
		"https://rr1.googlevideo.com:8443/audio", "https://user:pass@rr1.googlevideo.com/audio",
	} {
		t.Run(target, func(t *testing.T) {
			client := newYouTubeStreamClient()
			requests := 0
			client.Transport = youtubeStreamTransport{streamTestTransport(func(req *http.Request) (*http.Response, error) {
				requests++
				return &http.Response{StatusCode: 302, Header: http.Header{"Location": {target}}, Body: io.NopCloser(strings.NewReader("")), Request: req}, nil
			})}
			if _, err := client.Get("https://rr1.googlevideo.com/audio"); err == nil || requests != 1 {
				t.Fatalf("unsafe redirect followed: err=%v requests=%d", err, requests)
			}
		})
	}
}

func TestYouTubeProxyRejectsUntrustedURLBeforeNetwork(t *testing.T) {
	h := &Handler{}
	for _, target := range []string{"https://127.0.0.1/", "http://rr1.googlevideo.com/", "https://rr1.googlevideo.com.evil.example/"} {
		w := httptest.NewRecorder()
		h.handleYouTubeStreamProxy(w, httptest.NewRequest(http.MethodGet, "/?url="+url.QueryEscape(target), nil))
		if w.Code != http.StatusBadRequest {
			t.Errorf("%s: got %d", target, w.Code)
		}
	}
}

func TestYouTubeProxyPreservesRangeAndHead(t *testing.T) {
	previous := youtubeStreamHTTPClient
	t.Cleanup(func() { youtubeStreamHTTPClient = previous })
	for _, method := range []string{http.MethodGet, http.MethodHead} {
		t.Run(method, func(t *testing.T) {
			youtubeStreamHTTPClient = &http.Client{Transport: youtubeStreamTransport{streamTestTransport(func(req *http.Request) (*http.Response, error) {
				if req.Method != method || req.Header.Get("Range") != "bytes=5-9" {
					t.Fatalf("lost method or range: %s %s", req.Method, req.Header.Get("Range"))
				}
				return &http.Response{
					StatusCode: http.StatusPartialContent,
					Header:     http.Header{"Content-Type": {"audio/mp4"}, "Content-Range": {"bytes 5-9/20"}},
					Body:       io.NopCloser(strings.NewReader("audio")), Request: req,
				}, nil
			})}}
			r := httptest.NewRequest(method, "/?url="+url.QueryEscape("https://rr1.googlevideo.com/videoplayback?signature=abc"), nil)
			r.Header.Set("Range", "bytes=5-9")
			w := httptest.NewRecorder()
			(&Handler{}).handleYouTubeStreamProxy(w, r)
			if w.Code != http.StatusPartialContent || w.Header().Get("Content-Range") != "bytes 5-9/20" {
				t.Fatalf("lost upstream response: %d %v", w.Code, w.Header())
			}
			if method == http.MethodGet && w.Body.String() != "audio" || method == http.MethodHead && w.Body.Len() != 0 {
				t.Fatalf("unexpected %s body %q", method, w.Body.String())
			}
		})
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
