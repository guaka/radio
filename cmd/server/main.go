package main

import (
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"syscall"
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
	staticRoot := registerStatic(mux)
	startPublicAutoRestart(staticRoot)

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

func registerStatic(mux *http.ServeMux) string {
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
			serveHTMLNoCache(w, r, filepath.Join(staticRoot, "index.html"))
			return
		}
		// Keep SPA-like behavior for unknown non-API paths.
		if strings.HasPrefix(p, "api/") || strings.HasPrefix(p, "auth/") || strings.HasPrefix(p, "libraries") || strings.HasPrefix(p, "tracks") || strings.HasPrefix(p, "stream/") || strings.HasPrefix(p, "share/") || strings.HasPrefix(p, "s/") || strings.HasPrefix(p, "me") || strings.HasPrefix(p, "admin/") {
			http.NotFound(w, r)
			return
		}
		full := filepath.Join(staticRoot, p)
		if info, err := os.Stat(full); err == nil && !info.IsDir() {
			if strings.EqualFold(filepath.Ext(full), ".html") {
				serveHTMLNoCache(w, r, full)
				return
			}
			// Prevent stale JS/CSS/assets during development and hard refreshes.
			w.Header().Set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
			w.Header().Set("Pragma", "no-cache")
			w.Header().Set("Expires", "0")
			fs.ServeHTTP(w, r)
			return
		}
		serveHTMLNoCache(w, r, filepath.Join(staticRoot, "index.html"))
	})
	return staticRoot
}

func serveHTMLNoCache(w http.ResponseWriter, r *http.Request, fullPath string) {
	// Always revalidate HTML to avoid stale SPA shells during local dev.
	w.Header().Set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
	w.Header().Set("Pragma", "no-cache")
	w.Header().Set("Expires", "0")
	http.ServeFile(w, r, fullPath)
}

func withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s %s", r.Method, r.URL.Path, time.Since(start).Truncate(time.Millisecond))
	})
}

func startPublicAutoRestart(staticRoot string) {
	last := latestTreeModTime(staticRoot)
	go func() {
		ticker := time.NewTicker(1 * time.Second)
		defer ticker.Stop()
		for range ticker.C {
			current := latestTreeModTime(staticRoot)
			if current.After(last) {
				log.Printf("public/ changed; restarting server")
				// Re-exec in-place so dev servers pick up updated static assets and code paths.
				if err := syscall.Exec(os.Args[0], os.Args, os.Environ()); err != nil {
					log.Printf("auto-restart failed: %v", err)
				}
				return
			}
		}
	}()
}

func latestTreeModTime(root string) time.Time {
	var latest time.Time
	_ = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		info, statErr := d.Info()
		if statErr != nil {
			return nil
		}
		if mod := info.ModTime(); mod.After(latest) {
			latest = mod
		}
		return nil
	})
	return latest
}
