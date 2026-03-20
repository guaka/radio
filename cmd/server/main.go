package main

import (
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/guaka/radio-guaka/internal/config"
	"github.com/guaka/radio-guaka/internal/httpapi"
	nostrsvc "github.com/guaka/radio-guaka/internal/nostr"
	"github.com/guaka/radio-guaka/internal/plex"
	"github.com/guaka/radio-guaka/internal/policy"
	"github.com/guaka/radio-guaka/internal/share"
	"github.com/guaka/radio-guaka/internal/store"
)

func main() {
	cfg := config.Load()
	st := store.New()
	auth := nostrsvc.NewAuthService(cfg.SessionSecret)
	contacts := nostrsvc.NewContactsService(cfg.OwnerPubKey, cfg.NostrRelays, cfg.NostrCacheTTL)
	pol := policy.New(contacts)
	px := plex.NewClient(cfg.PlexBaseURL, cfg.PlexToken, cfg.PlexClientID)
	pin := plex.NewPINClient(cfg.PlexClientID)
	shares := share.New(st, cfg.ShareTokenBytes)
	h := httpapi.New(cfg, st, auth, contacts, pol, px, pin, shares)

	mux := http.NewServeMux()
	h.Register(mux)
	registerStatic(mux)

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           withLogging(mux),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("pleXtr server listening on %s", cfg.Addr)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}

func registerStatic(mux *http.ServeMux) {
	root, err := os.Getwd()
	if err != nil {
		log.Fatalf("cwd error: %v", err)
	}
	staticRoot := filepath.Join(root, "public")
	if info, err := os.Stat(staticRoot); err != nil || !info.IsDir() {
		log.Fatalf("missing static directory: %s", staticRoot)
	}
	fs := http.FileServer(http.Dir(staticRoot))

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		p := strings.TrimPrefix(filepath.Clean(r.URL.Path), "/")
		if p == "" {
			http.ServeFile(w, r, filepath.Join(staticRoot, "index.html"))
			return
		}
		// Keep SPA-like behavior for unknown non-API paths.
		if strings.HasPrefix(p, "api/") || strings.HasPrefix(p, "auth/") || strings.HasPrefix(p, "libraries") || strings.HasPrefix(p, "tracks") || strings.HasPrefix(p, "stream/") || strings.HasPrefix(p, "share/") || strings.HasPrefix(p, "s/") || strings.HasPrefix(p, "me") || strings.HasPrefix(p, "admin/") {
			http.NotFound(w, r)
			return
		}
		full := filepath.Join(staticRoot, p)
		if info, err := os.Stat(full); err == nil && !info.IsDir() {
			fs.ServeHTTP(w, r)
			return
		}
		http.ServeFile(w, r, filepath.Join(staticRoot, "index.html"))
	})
}

func withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s %s", r.Method, r.URL.Path, time.Since(start).Truncate(time.Millisecond))
	})
}
