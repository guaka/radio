package plex

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path"
	"strings"
	"time"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip19"
)

type Client struct {
	baseURL          string
	token            string
	clientID         string
	wrapsterMediaURL string
	wrapsterMediaKey string
	http             *http.Client
}

type Library struct {
	Key   string `json:"key"`
	Title string `json:"title"`
	Type  string `json:"type"`
}

type Track struct {
	RatingKey string `json:"ratingKey"`
	Title     string `json:"title"`
	Artist    string `json:"artist"`
}

func NewClient(baseURL, token, clientID, wrapsterMediaURL, wrapsterMediaKey string) *Client {
	return &Client{
		baseURL:          strings.TrimRight(baseURL, "/"),
		token:            strings.TrimSpace(token),
		clientID:         strings.TrimSpace(clientID),
		wrapsterMediaURL: strings.TrimRight(strings.TrimSpace(wrapsterMediaURL), "/"),
		wrapsterMediaKey: normalizeNostrPrivateKey(wrapsterMediaKey),
		http:             &http.Client{Timeout: 15 * time.Second},
	}
}

func (c *Client) Enabled() bool {
	return c.DirectEnabled() || c.WrapsterEnabled()
}

func (c *Client) DirectEnabled() bool {
	return c.baseURL != "" && c.token != ""
}

func (c *Client) WrapsterEnabled() bool {
	return c.wrapsterMediaURL != "" && c.wrapsterMediaKey != ""
}

func (c *Client) CanProxy() bool {
	return c.baseURL != ""
}

func (c *Client) ListMusicLibraries(ctx context.Context) ([]Library, error) {
	if c.WrapsterEnabled() {
		return []Library{{Key: "wrapster-plex", Title: "Wrapster Plex", Type: "artist"}}, nil
	}
	if !c.DirectEnabled() {
		return []Library{}, nil
	}
	var out struct {
		MediaContainer struct {
			Directory []struct {
				Key   string `json:"key"`
				Title string `json:"title"`
				Type  string `json:"type"`
			} `json:"Directory"`
		} `json:"MediaContainer"`
	}
	if err := c.getJSON(ctx, "/library/sections", nil, &out); err != nil {
		return nil, err
	}
	libs := make([]Library, 0, len(out.MediaContainer.Directory))
	for _, d := range out.MediaContainer.Directory {
		if d.Type != "artist" {
			continue
		}
		libs = append(libs, Library{Key: d.Key, Title: d.Title, Type: d.Type})
	}
	return libs, nil
}

func (c *Client) SearchTracks(ctx context.Context, sectionID, q string) ([]Track, error) {
	if c.WrapsterEnabled() {
		return c.searchWrapsterPlex(ctx, q)
	}
	if !c.DirectEnabled() {
		return []Track{}, nil
	}
	params := map[string]string{}
	if q != "" {
		params["query"] = q
	}
	p := fmt.Sprintf("/library/sections/%s/all", url.PathEscape(sectionID))
	var out struct {
		MediaContainer struct {
			Metadata []struct {
				RatingKey string `json:"ratingKey"`
				Title     string `json:"title"`
				Original  string `json:"originalTitle"`
				Grand     string `json:"grandparentTitle"`
			} `json:"Metadata"`
		} `json:"MediaContainer"`
	}
	if err := c.getJSON(ctx, p, params, &out); err != nil {
		return nil, err
	}
	items := make([]Track, 0, len(out.MediaContainer.Metadata))
	for _, m := range out.MediaContainer.Metadata {
		artist := m.Grand
		if artist == "" {
			artist = m.Original
		}
		items = append(items, Track{
			RatingKey: m.RatingKey,
			Title:     m.Title,
			Artist:    artist,
		})
	}
	return items, nil
}

func (c *Client) StreamTrack(ctx context.Context, w http.ResponseWriter, r *http.Request, trackID string) error {
	if c.WrapsterEnabled() {
		return c.streamWrapsterPlex(ctx, w, r, trackID)
	}
	if !c.DirectEnabled() {
		http.Error(w, "plex not configured", http.StatusBadGateway)
		return nil
	}
	var md struct {
		MediaContainer struct {
			Metadata []struct {
				Media []struct {
					Part []struct {
						Key    string `json:"key"`
						Format string `json:"container"`
					} `json:"Part"`
				} `json:"Media"`
			} `json:"Metadata"`
		} `json:"MediaContainer"`
	}
	mp := fmt.Sprintf("/library/metadata/%s", url.PathEscape(trackID))
	if err := c.getJSON(ctx, mp, nil, &md); err != nil {
		return err
	}
	if len(md.MediaContainer.Metadata) == 0 || len(md.MediaContainer.Metadata[0].Media) == 0 || len(md.MediaContainer.Metadata[0].Media[0].Part) == 0 {
		http.Error(w, "track not streamable", http.StatusNotFound)
		return nil
	}
	partKey := md.MediaContainer.Metadata[0].Media[0].Part[0].Key
	return c.proxy(ctx, w, r, partKey)
}

type wrapsterSearchResponse struct {
	Service string `json:"service"`
	Items   []struct {
		ID       string `json:"id"`
		Name     string `json:"name"`
		Type     string `json:"type"`
		Summary  string `json:"summary"`
		StreamID string `json:"stream_id"`
	} `json:"items"`
}

func (c *Client) searchWrapsterPlex(ctx context.Context, q string) ([]Track, error) {
	if !c.WrapsterEnabled() {
		return []Track{}, nil
	}
	query := strings.TrimSpace(q)
	if query == "" {
		return []Track{}, nil
	}
	u := c.wrapsterURL("/media/api/services/plex/search")
	params := u.Query()
	params.Set("q", query)
	u.RawQuery = params.Encode()
	req, err := c.newWrapsterRequest(ctx, http.MethodGet, u.String())
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return nil, fmt.Errorf("wrapster plex search status %d: %s", resp.StatusCode, string(body))
	}
	var out wrapsterSearchResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	items := make([]Track, 0, len(out.Items))
	for _, item := range out.Items {
		streamID := strings.TrimSpace(item.StreamID)
		if streamID == "" {
			continue
		}
		artist := strings.TrimSpace(item.Summary)
		if artist == "" {
			artist = strings.TrimSpace(item.Type)
		}
		items = append(items, Track{
			RatingKey: streamID,
			Title:     strings.TrimSpace(item.Name),
			Artist:    artist,
		})
	}
	return items, nil
}

func (c *Client) streamWrapsterPlex(ctx context.Context, w http.ResponseWriter, r *http.Request, streamID string) error {
	streamID = strings.TrimSpace(streamID)
	if streamID == "" {
		http.Error(w, "missing stream id", http.StatusBadRequest)
		return nil
	}
	u := c.wrapsterURL("/media/api/services/plex/stream/" + url.PathEscape(streamID))
	req, err := c.newWrapsterRequest(ctx, http.MethodGet, u.String())
	if err != nil {
		return err
	}
	if rng := r.Header.Get("Range"); rng != "" {
		req.Header.Set("Range", rng)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	for k, vals := range resp.Header {
		if strings.EqualFold(k, "Set-Cookie") {
			continue
		}
		for _, v := range vals {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	_, err = io.Copy(w, resp.Body)
	return err
}

func (c *Client) wrapsterURL(p string) *url.URL {
	u, _ := url.Parse(c.wrapsterMediaURL)
	u.Path = path.Join(u.Path, p)
	return u
}

func (c *Client) newWrapsterRequest(ctx context.Context, method, target string) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, method, target, nil)
	if err != nil {
		return nil, err
	}
	auth, err := c.wrapsterAuthHeader(method, target)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", auth)
	return req, nil
}

func (c *Client) wrapsterAuthHeader(method, target string) (string, error) {
	if c.wrapsterMediaKey == "" {
		return "", fmt.Errorf("wrapster media signing key is not configured")
	}
	event := nostr.Event{
		CreatedAt: nostr.Timestamp(time.Now().Unix()),
		Kind:      27235,
		Tags:      nostr.Tags{{"u", target}, {"method", method}},
		Content:   "",
	}
	if err := event.Sign(c.wrapsterMediaKey); err != nil {
		return "", err
	}
	raw, err := json.Marshal(event)
	if err != nil {
		return "", err
	}
	return "Nostr " + base64.StdEncoding.EncodeToString(raw), nil
}

func normalizeNostrPrivateKey(raw string) string {
	value := strings.TrimSpace(raw)
	if strings.HasPrefix(value, "nsec") {
		prefix, decoded, err := nip19.Decode(value)
		if err == nil && prefix == "nsec" {
			if key, ok := decoded.(string); ok {
				return strings.TrimSpace(key)
			}
		}
	}
	return value
}

func (c *Client) ProxyPath(ctx context.Context, w http.ResponseWriter, r *http.Request, plexPath, rawQuery string) error {
	if !c.CanProxy() {
		http.Error(w, "plex not configured", http.StatusBadGateway)
		return nil
	}
	p := strings.TrimSpace(plexPath)
	if p == "" {
		http.Error(w, "missing plex path", http.StatusBadRequest)
		return nil
	}
	if !strings.HasPrefix(p, "/") {
		p = "/" + p
	}
	return c.proxyWithQuery(ctx, w, r, p, rawQuery)
}

func (c *Client) proxy(ctx context.Context, w http.ResponseWriter, r *http.Request, partKey string) error {
	return c.proxyWithQuery(ctx, w, r, partKey, "")
}

func (c *Client) proxyWithQuery(ctx context.Context, w http.ResponseWriter, r *http.Request, partKey, rawQuery string) error {
	u, _ := url.Parse(c.baseURL)
	partPath := strings.TrimSpace(partKey)
	partQuery := ""
	if parsedPart, err := url.Parse(partPath); err == nil && parsedPart != nil {
		if strings.TrimSpace(parsedPart.Path) != "" {
			partPath = parsedPart.Path
		}
		partQuery = strings.TrimSpace(parsedPart.RawQuery)
	}
	u.Path = path.Join(u.Path, partPath)
	q, _ := url.ParseQuery(rawQuery)
	if strings.TrimSpace(partQuery) != "" {
		if pq, err := url.ParseQuery(partQuery); err == nil {
			for k, vals := range pq {
				for _, v := range vals {
					q.Add(k, v)
				}
			}
		}
	}
	token := strings.TrimSpace(q.Get("X-Plex-Token"))
	if token == "" {
		token = strings.TrimSpace(r.Header.Get("X-Plex-Token"))
	}
	if token == "" {
		token = strings.TrimSpace(c.token)
	}
	if token == "" {
		http.Error(w, "missing plex token", http.StatusBadRequest)
		return nil
	}
	q.Set("X-Plex-Token", token)
	u.RawQuery = q.Encode()

	method := r.Method
	if method == "" {
		method = http.MethodGet
	}
	req, err := http.NewRequestWithContext(ctx, method, u.String(), nil)
	if err != nil {
		return err
	}
	if rng := r.Header.Get("Range"); rng != "" {
		req.Header.Set("Range", rng)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	for k, vals := range resp.Header {
		if strings.EqualFold(k, "Set-Cookie") {
			continue
		}
		for _, v := range vals {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	_, err = io.Copy(w, resp.Body)
	return err
}

func (c *Client) getJSON(ctx context.Context, p string, params map[string]string, out any) error {
	u, _ := url.Parse(c.baseURL)
	u.Path = path.Join(u.Path, p)
	q := u.Query()
	q.Set("X-Plex-Token", c.token)
	q.Set("X-Plex-Client-Identifier", c.clientID)
	q.Set("X-Plex-Product", "pleXtr")
	q.Set("X-Plex-Version", "1")
	q.Set("X-Plex-Device-Name", "pleXtr-go")
	q.Set("X-Plex-Platform", "web")
	q.Set("X-Plex-Accept", "application/json")
	for k, v := range params {
		q.Set(k, v)
	}
	u.RawQuery = q.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	res, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(res.Body, 512))
		return fmt.Errorf("plex status %d: %s", res.StatusCode, string(body))
	}
	return json.NewDecoder(res.Body).Decode(out)
}
