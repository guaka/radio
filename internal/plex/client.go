package plex

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path"
	"strings"
	"time"
)

type Client struct {
	baseURL  string
	token    string
	clientID string
	http     *http.Client
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

func NewClient(baseURL, token, clientID string) *Client {
	return &Client{
		baseURL:  strings.TrimRight(baseURL, "/"),
		token:    token,
		clientID: clientID,
		http:     &http.Client{Timeout: 15 * time.Second},
	}
}

func (c *Client) Enabled() bool {
	return c.baseURL != "" && c.token != ""
}

func (c *Client) ListMusicLibraries(ctx context.Context) ([]Library, error) {
	if !c.Enabled() {
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
	if !c.Enabled() {
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
	if !c.Enabled() {
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

func (c *Client) proxy(ctx context.Context, w http.ResponseWriter, r *http.Request, partKey string) error {
	u, _ := url.Parse(c.baseURL)
	u.Path = path.Join(u.Path, partKey)
	q := u.Query()
	q.Set("X-Plex-Token", c.token)
	u.RawQuery = q.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
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
