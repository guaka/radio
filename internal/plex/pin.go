package plex

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type PINClient struct {
	clientID string
	http     *http.Client
}

type PinStartResponse struct {
	ID    int    `json:"id"`
	Code  string `json:"code"`
	QR    string `json:"qr"`
	Login string `json:"loginUrl"`
}

func NewPINClient(clientID string) *PINClient {
	return &PINClient{
		clientID: clientID,
		http:     &http.Client{Timeout: 15 * time.Second},
	}
}

func (p *PINClient) Start(ctx context.Context) (PinStartResponse, error) {
	form := url.Values{}
	form.Set("strong", "true")
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://plex.tv/api/v2/pins", strings.NewReader(form.Encode()))
	if err != nil {
		return PinStartResponse{}, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	p.addHeaders(req)
	resp, err := p.http.Do(req)
	if err != nil {
		return PinStartResponse{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return PinStartResponse{}, fmt.Errorf("plex pin start status %d: %s", resp.StatusCode, string(body))
	}
	var raw struct {
		ID   int    `json:"id"`
		Code string `json:"code"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
		return PinStartResponse{}, err
	}
	return PinStartResponse{
		ID:    raw.ID,
		Code:  raw.Code,
		QR:    "https://app.plex.tv/auth#?clientID=" + url.QueryEscape(p.clientID) + "&code=" + url.QueryEscape(raw.Code),
		Login: "https://plex.tv/link",
	}, nil
}

func (p *PINClient) Poll(ctx context.Context, pinID int) (string, error) {
	u := fmt.Sprintf("https://plex.tv/api/v2/pins/%d", pinID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return "", err
	}
	p.addHeaders(req)
	resp, err := p.http.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return "", fmt.Errorf("plex pin poll status %d: %s", resp.StatusCode, string(body))
	}
	var raw struct {
		AuthToken string `json:"authToken"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
		return "", err
	}
	return raw.AuthToken, nil
}

func (p *PINClient) addHeaders(req *http.Request) {
	req.Header.Set("Accept", "application/json")
	req.Header.Set("X-Plex-Product", "pleXtr")
	req.Header.Set("X-Plex-Version", "1")
	req.Header.Set("X-Plex-Client-Identifier", p.clientID)
	req.Header.Set("X-Plex-Platform", "web")
}
