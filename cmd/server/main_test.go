package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestStaticFilesStayInsidePublicRoot(t *testing.T) {
	base := t.TempDir()
	public := filepath.Join(base, "public")
	if err := os.MkdirAll(filepath.Join(public, "js"), 0700); err != nil {
		t.Fatal(err)
	}
	for name, body := range map[string]string{
		"index.html": "app shell", "player.html": "player", "js/app.js": "application script",
	} {
		if err := os.WriteFile(filepath.Join(public, name), []byte(body), 0600); err != nil {
			t.Fatal(err)
		}
	}
	secret := filepath.Join(base, "secret.html")
	if err := os.WriteFile(secret, []byte("private credentials"), 0600); err != nil {
		t.Fatal(err)
	}
	for name, target := range map[string]string{"leak.html": secret, "outside": base} {
		if err := os.Symlink(target, filepath.Join(public, name)); err != nil {
			t.Fatal(err)
		}
	}
	root, err := os.OpenRoot(public)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { root.Close() })
	handler := staticHandler(root.FS())
	for _, tc := range []struct {
		path string
		code int
		body string
	}{
		{"/", 200, "app shell"},
		{"/player.html", 200, "player"},
		{"/js/app.js", 200, "application script"},
		{"/unknown-route", 200, "app shell"},
		{"/unknown-route/", 200, "app shell"},
		{"/api/missing", 404, ""},
		{"/../secret.html", 404, ""},
		{"/%2e%2e/secret.html", 404, ""},
		{"/js/../../secret.html", 404, ""},
		{"/..%5csecret.html", 404, ""},
		{"//secret.html", 404, ""},
		{"/leak.html", 404, ""},
		{"/outside/secret.html", 404, ""},
	} {
		t.Run(tc.path, func(t *testing.T) {
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, httptest.NewRequest(http.MethodGet, tc.path, nil))
			if w.Code != tc.code || (tc.body != "" && w.Body.String() != tc.body) {
				t.Fatalf("got %d %q, want %d %q", w.Code, w.Body.String(), tc.code, tc.body)
			}
			if strings.Contains(w.Body.String(), "private credentials") {
				t.Fatal("served content outside public root")
			}
			if tc.code == 200 && !strings.Contains(w.Header().Get("Cache-Control"), "no-store") {
				t.Fatal("missing no-cache headers")
			}
		})
	}
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, httptest.NewRequest(http.MethodHead, "/player.html", nil))
	if w.Code != http.StatusOK || w.Body.Len() != 0 {
		t.Fatalf("HEAD: got %d, body %q", w.Code, w.Body.String())
	}
}
