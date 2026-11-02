package httpapi

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	yt "github.com/kkdai/youtube/v2"
	"io"
	"log"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strings"
	"time"
)

// Public mirror bases used by YouTube resolve — kept in one place for external link hints.
var (
	pipedAPIInstances = []string{
		"https://pipedapi.kavin.rocks",
		"https://pipedapi.ducks.party",
		"https://pipedapi.adminforge.de",
		"https://pipedapi.syncpundit.io",
		"https://pipedapi.nosebs.ru",
	}
	invidiousInstances = []string{
		"https://inv.nadeko.net",
		"https://invidious.fdn.fr",
		"https://invidious.privacyredirect.com",
	}
)

type pipedAudioStream struct {
	URL     string `json:"url"`
	Bitrate int    `json:"bitrate"`
}

type pipedStreamsPayload struct {
	Title        string             `json:"title"`
	Uploader     string             `json:"uploader"`
	ThumbnailURL string             `json:"thumbnailUrl"`
	AudioStreams []pipedAudioStream `json:"audioStreams"`
}

type resolveAttempt struct {
	Instance string `json:"instance"`
	OK       bool   `json:"ok"`
	Error    string `json:"error,omitempty"`
}

func bestYouTubeThumbnail(video *yt.Video) string {
	if video == nil || len(video.Thumbnails) == 0 {
		return ""
	}
	best := video.Thumbnails[0]
	bestScore := best.Width * best.Height
	for i := 1; i < len(video.Thumbnails); i++ {
		t := video.Thumbnails[i]
		score := t.Width * t.Height
		if score > bestScore {
			best = t
			bestScore = score
		}
	}
	return strings.TrimSpace(best.URL)
}

func resolveViaDirectYouTube(ctx context.Context, videoID string) (streamURL, title, artist, artwork string, err error) {
	client := &yt.Client{}
	videoURL := "https://www.youtube.com/watch?v=" + url.QueryEscape(videoID)
	video, err := client.GetVideoContext(ctx, videoURL)
	if err != nil {
		return "", "", "", "", err
	}
	audioFormats := video.Formats.WithAudioChannels()
	if len(audioFormats) == 0 {
		return "", strings.TrimSpace(video.Title), strings.TrimSpace(video.Author), bestYouTubeThumbnail(video), errors.New("no direct audio formats available")
	}
	sort.Slice(audioFormats, func(i, j int) bool {
		return audioFormats[i].Bitrate > audioFormats[j].Bitrate
	})
	var lastErr error
	for i := 0; i < len(audioFormats); i++ {
		f := audioFormats[i]
		u, err := client.GetStreamURL(video, &f)
		if err != nil {
			lastErr = err
			continue
		}
		if strings.TrimSpace(u) == "" {
			lastErr = errors.New("empty stream url")
			continue
		}
		return strings.TrimSpace(u), strings.TrimSpace(video.Title), strings.TrimSpace(video.Author), bestYouTubeThumbnail(video), nil
	}
	if lastErr == nil {
		lastErr = errors.New("could not produce stream url from direct formats")
	}
	return "", strings.TrimSpace(video.Title), strings.TrimSpace(video.Author), bestYouTubeThumbnail(video), lastErr
}

// buildYouTubeExternalLinks returns human-checkable URLs (Piped API JSON, approximate Piped web UI, Invidious, YouTube).
func buildYouTubeExternalLinks(videoID string) []map[string]string {
	out := []map[string]string{}
	out = append(out, map[string]string{
		"label": "YouTube (original)",
		"url":   "https://www.youtube.com/watch?v=" + url.QueryEscape(videoID),
	})
	for _, base := range pipedAPIInstances {
		base = strings.TrimRight(base, "/")
		apiURL := base + "/streams/" + url.PathEscape(videoID)
		u, err := url.Parse(base)
		short := strings.TrimPrefix(strings.TrimPrefix(base, "https://"), "http://")
		if err == nil && u.Host != "" {
			short = u.Host
		}
		out = append(out, map[string]string{
			"label": "Piped API JSON (" + short + ")",
			"url":   apiURL,
		})
		if u != nil && u.Host != "" {
			watchHost := strings.Replace(u.Host, "pipedapi.", "piped.", 1)
			if watchHost != u.Host {
				watchURL := u.Scheme + "://" + watchHost + "/watch?v=" + url.QueryEscape(videoID)
				out = append(out, map[string]string{
					"label": "Piped web UI (" + watchHost + ")",
					"url":   watchURL,
				})
			}
		}
	}
	for _, base := range invidiousInstances {
		base = strings.TrimRight(base, "/")
		watchURL := base + "/watch?v=" + url.QueryEscape(videoID)
		u, _ := url.Parse(base)
		short := base
		if u != nil && u.Host != "" {
			short = u.Host
		}
		out = append(out, map[string]string{
			"label": "Invidious (" + short + ")",
			"url":   watchURL,
		})
	}
	return out
}

func (h *Handler) handleYouTubeExternalLinks(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	videoID := strings.TrimSpace(r.URL.Query().Get("videoId"))
	if videoID == "" {
		http.Error(w, "videoId required", http.StatusBadRequest)
		return
	}
	if !youtubeVideoIDPattern.MatchString(videoID) {
		http.Error(w, "invalid videoId", http.StatusBadRequest)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":            true,
		"videoId":       videoID,
		"externalLinks": buildYouTubeExternalLinks(videoID),
	})
}

func newOutboundHTTPClient(timeout time.Duration, disableKeepAlives bool) *http.Client {
	tr := &http.Transport{
		Proxy:             http.ProxyFromEnvironment,
		DisableKeepAlives: disableKeepAlives,
		ForceAttemptHTTP2: false, // Some public instances are flaky with HTTP/2/TLS combos.
		TLSClientConfig: &tls.Config{
			MinVersion: tls.VersionTLS12,
		},
	}
	return &http.Client{
		Timeout:   timeout,
		Transport: tr,
	}
}

func (h *Handler) handleYouTubeResolve(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	videoID := strings.TrimSpace(r.URL.Query().Get("videoId"))
	if videoID == "" {
		http.Error(w, "videoId required", http.StatusBadRequest)
		return
	}
	// Minimal format guard to avoid pointless upstream requests.
	if !youtubeVideoIDPattern.MatchString(videoID) {
		http.Error(w, "invalid videoId", http.StatusBadRequest)
		return
	}
	started := time.Now()
	ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
	defer cancel()
	log.Printf("[youtube-resolve] start videoId=%s", videoID)
	externalLinks := buildYouTubeExternalLinks(videoID)
	type resolveResult struct {
		StreamURL string
		Title     string
		Artist    string
		Artwork   string
		SourceAPI string
	}
	var out resolveResult
	var lastErr error
	attempts := make([]resolveAttempt, 0, len(pipedAPIInstances))

	// First try direct YouTube extraction to avoid long mirror fallback delays.
	{
		directURL, directTitle, directArtist, directArtwork, err := resolveViaDirectYouTube(ctx, videoID)
		if err != nil {
			lastErr = err
			attempts = append(attempts, resolveAttempt{Instance: "youtube-direct#attempt-1", OK: false, Error: err.Error()})
			log.Printf("[youtube-resolve] fail videoId=%s instance=youtube-direct err=%v", videoID, err)
		} else {
			attempts = append(attempts, resolveAttempt{Instance: "youtube-direct#attempt-1", OK: true})
			out = resolveResult{
				StreamURL: strings.TrimSpace(directURL),
				Title:     strings.TrimSpace(directTitle),
				Artist:    strings.TrimSpace(directArtist),
				Artwork:   strings.TrimSpace(directArtwork),
				SourceAPI: "youtube-direct",
			}
			log.Printf("[youtube-resolve] ok videoId=%s instance=youtube-direct title=%q", videoID, out.Title)
		}
	}

	if strings.TrimSpace(out.StreamURL) == "" {
		for i := 0; i < len(pipedAPIInstances); i++ {
			base := pipedAPIInstances[i]
			for attempt := 1; attempt <= 1; attempt++ {
				disableKeepAlives := attempt > 1
				client := newOutboundHTTPClient(3*time.Second, disableKeepAlives)
				u := strings.TrimRight(base, "/") + "/streams/" + url.PathEscape(videoID)
				req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
				if err != nil {
					lastErr = err
					attempts = append(attempts, resolveAttempt{Instance: base + "#piped-attempt-" + fmt.Sprint(attempt), OK: false, Error: err.Error()})
					continue
				}
				resp, err := client.Do(req)
				if err != nil {
					lastErr = err
					attempts = append(attempts, resolveAttempt{Instance: base + "#piped-attempt-" + fmt.Sprint(attempt), OK: false, Error: err.Error()})
					log.Printf("[youtube-resolve] fail videoId=%s instance=%s attempt=%d err=%v", videoID, base, attempt, err)
					continue
				}
				if resp.StatusCode != http.StatusOK {
					lastErr = fmt.Errorf("piped status %d from %s", resp.StatusCode, base)
					attempts = append(attempts, resolveAttempt{Instance: base + "#piped-attempt-" + fmt.Sprint(attempt), OK: false, Error: lastErr.Error()})
					log.Printf("[youtube-resolve] fail videoId=%s instance=%s attempt=%d status=%d", videoID, base, attempt, resp.StatusCode)
					_ = resp.Body.Close()
					continue
				}
				var payload pipedStreamsPayload
				if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
					lastErr = err
					attempts = append(attempts, resolveAttempt{Instance: base + "#piped-attempt-" + fmt.Sprint(attempt), OK: false, Error: err.Error()})
					log.Printf("[youtube-resolve] fail videoId=%s instance=%s attempt=%d decodeErr=%v", videoID, base, attempt, err)
					_ = resp.Body.Close()
					continue
				}
				_ = resp.Body.Close()
				bestURL := ""
				bestBitrate := -1
				for ai := 0; ai < len(payload.AudioStreams); ai++ {
					s := payload.AudioStreams[ai]
					if strings.TrimSpace(s.URL) == "" {
						continue
					}
					if s.Bitrate > bestBitrate {
						bestBitrate = s.Bitrate
						bestURL = strings.TrimSpace(s.URL)
					}
				}
				if bestURL == "" {
					lastErr = fmt.Errorf("no playable audio stream from %s", base)
					attempts = append(attempts, resolveAttempt{Instance: base + "#piped-attempt-" + fmt.Sprint(attempt), OK: false, Error: lastErr.Error()})
					log.Printf("[youtube-resolve] fail videoId=%s instance=%s attempt=%d noPlayableAudio", videoID, base, attempt)
					continue
				}
				attempts = append(attempts, resolveAttempt{Instance: base + "#piped-attempt-" + fmt.Sprint(attempt), OK: true})
				out = resolveResult{
					StreamURL: bestURL,
					Title:     strings.TrimSpace(payload.Title),
					Artist:    strings.TrimSpace(payload.Uploader),
					Artwork:   strings.TrimSpace(payload.ThumbnailURL),
					SourceAPI: base + " (piped)",
				}
				log.Printf("[youtube-resolve] ok videoId=%s instance=%s attempt=%d title=%q", videoID, base, attempt, out.Title)
				break
			}
			if strings.TrimSpace(out.StreamURL) != "" {
				break
			}
		}
	}

	// Fallback provider path: Invidious instances.
	if strings.TrimSpace(out.StreamURL) == "" {
		for i := 0; i < len(invidiousInstances); i++ {
			base := invidiousInstances[i]
			for attempt := 1; attempt <= 1; attempt++ {
				disableKeepAlives := attempt > 1
				client := newOutboundHTTPClient(3*time.Second, disableKeepAlives)
				u := strings.TrimRight(base, "/") + "/api/v1/videos/" + url.PathEscape(videoID)
				req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
				if err != nil {
					lastErr = err
					attempts = append(attempts, resolveAttempt{Instance: base + "#invidious-attempt-" + fmt.Sprint(attempt), OK: false, Error: err.Error()})
					continue
				}
				resp, err := client.Do(req)
				if err != nil {
					lastErr = err
					attempts = append(attempts, resolveAttempt{Instance: base + "#invidious-attempt-" + fmt.Sprint(attempt), OK: false, Error: err.Error()})
					log.Printf("[youtube-resolve] fail videoId=%s instance=%s attempt=%d err=%v", videoID, base, attempt, err)
					continue
				}
				if resp.StatusCode != http.StatusOK {
					lastErr = fmt.Errorf("invidious status %d from %s", resp.StatusCode, base)
					attempts = append(attempts, resolveAttempt{Instance: base + "#invidious-attempt-" + fmt.Sprint(attempt), OK: false, Error: lastErr.Error()})
					log.Printf("[youtube-resolve] fail videoId=%s instance=%s attempt=%d status=%d", videoID, base, attempt, resp.StatusCode)
					_ = resp.Body.Close()
					continue
				}
				var payload map[string]any
				if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
					lastErr = err
					attempts = append(attempts, resolveAttempt{Instance: base + "#invidious-attempt-" + fmt.Sprint(attempt), OK: false, Error: err.Error()})
					_ = resp.Body.Close()
					continue
				}
				_ = resp.Body.Close()
				title := strings.TrimSpace(fmt.Sprintf("%v", payload["title"]))
				author := strings.TrimSpace(fmt.Sprintf("%v", payload["author"]))
				thumb := strings.TrimSpace(fmt.Sprintf("%v", payload["videoThumbnails"]))
				bestURL := ""
				bestBitrate := -1
				adaptive, _ := payload["adaptiveFormats"].([]any)
				for ai := 0; ai < len(adaptive); ai++ {
					item, _ := adaptive[ai].(map[string]any)
					if item == nil {
						continue
					}
					tp := strings.ToLower(strings.TrimSpace(fmt.Sprintf("%v", item["type"])))
					if !strings.HasPrefix(tp, "audio/") {
						continue
					}
					rawURL := strings.TrimSpace(fmt.Sprintf("%v", item["url"]))
					if rawURL == "" {
						continue
					}
					br := 0
					switch v := item["bitrate"].(type) {
					case float64:
						br = int(v)
					case int:
						br = v
					}
					if br > bestBitrate {
						bestBitrate = br
						bestURL = rawURL
					}
				}
				if bestURL == "" {
					lastErr = fmt.Errorf("no playable invidious audio stream from %s", base)
					attempts = append(attempts, resolveAttempt{Instance: base + "#invidious-attempt-" + fmt.Sprint(attempt), OK: false, Error: lastErr.Error()})
					continue
				}
				attempts = append(attempts, resolveAttempt{Instance: base + "#invidious-attempt-" + fmt.Sprint(attempt), OK: true})
				out = resolveResult{
					StreamURL: strings.TrimSpace(bestURL),
					Title:     title,
					Artist:    author,
					Artwork:   thumb,
					SourceAPI: base + " (invidious)",
				}
				log.Printf("[youtube-resolve] ok videoId=%s instance=%s attempt=%d title=%q", videoID, base, attempt, out.Title)
				break
			}
			if strings.TrimSpace(out.StreamURL) != "" {
				break
			}
		}
	}
	if strings.TrimSpace(out.StreamURL) == "" {
		msg := "youtube resolve unavailable"
		if lastErr != nil {
			msg = msg + ": " + lastErr.Error()
		}
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"ok":            false,
			"error":         msg,
			"videoId":       videoID,
			"attempts":      attempts,
			"externalLinks": externalLinks,
		})
		log.Printf("[youtube-resolve] final-fail videoId=%s err=%v attempts=%d elapsed=%s", videoID, lastErr, len(attempts), time.Since(started).Truncate(time.Millisecond))
		return
	}
	if out.Title == "" {
		out.Title = "YouTube audio"
	}
	if out.Artist == "" {
		out.Artist = "YouTube"
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":            true,
		"videoId":       videoID,
		"streamUrl":     out.StreamURL,
		"proxyUrl":      "/api/youtube/stream?url=" + url.QueryEscape(out.StreamURL),
		"title":         out.Title,
		"artist":        out.Artist,
		"artwork":       out.Artwork,
		"sourceApi":     out.SourceAPI,
		"attempts":      attempts,
		"externalLinks": externalLinks,
	})
	log.Printf("[youtube-resolve] final-ok videoId=%s source=%s attempts=%d elapsed=%s", videoID, out.SourceAPI, len(attempts), time.Since(started).Truncate(time.Millisecond))
}

func (h *Handler) handleYouTubeStreamProxy(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	raw := strings.TrimSpace(r.URL.Query().Get("url"))
	if raw == "" {
		http.Error(w, "url required", http.StatusBadRequest)
		return
	}
	u, err := url.Parse(raw)
	if err != nil || u == nil || u.Scheme != "https" || strings.TrimSpace(u.Host) == "" {
		http.Error(w, "invalid url", http.StatusBadRequest)
		return
	}
	host := strings.ToLower(u.Hostname())
	log.Printf("[youtube-stream] start method=%s host=%s path=%s", r.Method, host, u.Path)
	// Prevent turning this into an open proxy.
	if !allowedYouTubeStreamURL(u) {
		http.Error(w, "upstream host not allowed", http.StatusBadRequest)
		return
	}
	req, err := http.NewRequestWithContext(r.Context(), r.Method, u.String(), nil)
	if err != nil {
		http.Error(w, "request build failed", http.StatusBadGateway)
		return
	}
	// Preserve range requests for seek behavior.
	if rg := strings.TrimSpace(r.Header.Get("Range")); rg != "" {
		req.Header.Set("Range", rg)
	}
	req.Header.Set("User-Agent", "radio-guaka/yt-proxy")
	req.Header.Set("Accept", "*/*")
	client := newOutboundHTTPClient(0, false)
	client.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		if len(via) >= 5 || !allowedYouTubeStreamURL(req.URL) {
			return errors.New("stream redirect not allowed")
		}
		return nil
	}
	resp, err := client.Do(req)
	if err != nil {
		log.Printf("[youtube-stream] fail host=%s err=%v", host, err)
		http.Error(w, "stream proxy error: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	for _, hk := range []string{
		"Content-Type",
		"Content-Length",
		"Accept-Ranges",
		"Content-Range",
		"Cache-Control",
		"ETag",
		"Last-Modified",
	} {
		if hv := resp.Header.Get(hk); hv != "" {
			w.Header().Set(hk, hv)
		}
	}
	w.WriteHeader(resp.StatusCode)
	log.Printf("[youtube-stream] upstream-status host=%s status=%d contentType=%q", host, resp.StatusCode, resp.Header.Get("Content-Type"))
	if r.Method == http.MethodHead {
		return
	}
	_, _ = io.Copy(w, resp.Body)
}

var youtubeVideoIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{11}$`)

func allowedYouTubeStreamURL(u *url.URL) bool {
	if u == nil || u.Scheme != "https" || u.User != nil || (u.Port() != "" && u.Port() != "443") {
		return false
	}
	host := strings.ToLower(u.Hostname())
	if host == "googlevideo.com" || strings.HasSuffix(host, ".googlevideo.com") {
		return true
	}
	for _, base := range pipedAPIInstances {
		api, err := url.Parse(base)
		if err == nil && host == strings.Replace(api.Hostname(), "pipedapi.", "pipedproxy.", 1) {
			return true
		}
	}
	return false
}
