package httpapi

import (
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

func latestModTimeByExt(root, ext string) (time.Time, error) {
	var latest time.Time
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d == nil {
			return nil
		}
		if d.IsDir() {
			name := strings.ToLower(strings.TrimSpace(d.Name()))
			if name == ".git" || name == "node_modules" {
				return filepath.SkipDir
			}
			return nil
		}
		if !strings.EqualFold(filepath.Ext(path), ext) {
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
	if walkErr != nil {
		return time.Time{}, walkErr
	}
	return latest, nil
}

func fileModTime(path string) time.Time {
	info, err := os.Stat(path)
	if err != nil || info == nil {
		return time.Time{}
	}
	return info.ModTime()
}

func findProjectRoot() string {
	candidates := make([]string, 0, 2)
	if cwd, err := os.Getwd(); err == nil && strings.TrimSpace(cwd) != "" {
		candidates = append(candidates, cwd)
	}
	if exe, err := os.Executable(); err == nil && strings.TrimSpace(exe) != "" {
		candidates = append(candidates, filepath.Dir(exe))
	}
	if _, srcFile, _, ok := runtime.Caller(0); ok && strings.TrimSpace(srcFile) != "" {
		candidates = append(candidates, filepath.Dir(srcFile))
	}
	for i := 0; i < len(candidates); i++ {
		cur := candidates[i]
		for {
			goMod := filepath.Join(cur, "go.mod")
			publicDir := filepath.Join(cur, "public")
			if info, err := os.Stat(goMod); err == nil && !info.IsDir() {
				if pInfo, pErr := os.Stat(publicDir); pErr == nil && pInfo.IsDir() {
					return cur
				}
			}
			parent := filepath.Dir(cur)
			if parent == cur {
				break
			}
			cur = parent
		}
	}
	return ""
}

func (h *Handler) handleBuildInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	root := findProjectRoot()
	var goMod time.Time
	var htmlMod time.Time
	if strings.TrimSpace(root) != "" {
		if t, err := latestModTimeByExt(root, ".go"); err == nil {
			goMod = t
		}
		if t, err := latestModTimeByExt(filepath.Join(root, "public"), ".html"); err == nil {
			htmlMod = t
		}
	}
	// Fallback for deployed/containerized runtimes without source tree.
	if exe, err := os.Executable(); err == nil && strings.TrimSpace(exe) != "" {
		if goMod.IsZero() {
			goMod = fileModTime(exe)
		}
		if htmlMod.IsZero() {
			htmlMod = fileModTime(filepath.Join(filepath.Dir(exe), "public", "index.html"))
		}
	}
	if htmlMod.IsZero() {
		if cwd, err := os.Getwd(); err == nil && strings.TrimSpace(cwd) != "" {
			htmlMod = fileModTime(filepath.Join(cwd, "public", "index.html"))
		}
	}
	formatLocal := func(t time.Time) string {
		if t.IsZero() {
			return ""
		}
		return t.Local().Format("2006-01-02 15:04")
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"go":   formatLocal(goMod),
		"html": formatLocal(htmlMod),
	})
}
