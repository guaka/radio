package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/guaka/radio-guaka/internal/config"
	nostrsvc "github.com/guaka/radio-guaka/internal/nostr"
	"github.com/guaka/radio-guaka/internal/plex"
	"github.com/guaka/radio-guaka/internal/policy"
	"github.com/guaka/radio-guaka/internal/share"
	"github.com/guaka/radio-guaka/internal/store"
)

type Handler struct {
	cfg      config.Config
	store    *store.Store
	auth     *nostrsvc.AuthService
	contacts *nostrsvc.ContactsService
	policy   *policy.AccessPolicy
	plex     *plex.Client
	pin      *plex.PINClient
	share    *share.LinksService
}

func New(cfg config.Config, st *store.Store, auth *nostrsvc.AuthService, contacts *nostrsvc.ContactsService, pol *policy.AccessPolicy, px *plex.Client, pin *plex.PINClient, sh *share.LinksService) *Handler {
	return &Handler{
		cfg:      cfg,
		store:    st,
		auth:     auth,
		contacts: contacts,
		policy:   pol,
		plex:     px,
		pin:      pin,
		share:    sh,
	}
}

func (h *Handler) Register(mux *http.ServeMux) {
	mux.HandleFunc("/api/health", h.handleHealth)
	mux.HandleFunc("/auth/nostr/challenge", h.handleNostrChallenge)
	mux.HandleFunc("/auth/nostr/verify", h.handleNostrVerify)
	mux.HandleFunc("/me", h.withAuth(h.handleMe))
	mux.HandleFunc("/auth/plex/pin/start", h.withAuth(h.handlePlexPinStart))
	mux.HandleFunc("/auth/plex/pin/poll", h.withAuth(h.handlePlexPinPoll))
	mux.HandleFunc("/libraries", h.withFriend(h.handleLibraries))
	mux.HandleFunc("/tracks", h.withFriend(h.handleTracks))
	mux.HandleFunc("/stream/", h.withFriend(h.handleStream))
	mux.HandleFunc("/share/song/", h.withFriend(h.handleShareSong))
	mux.HandleFunc("/share/", h.withFriend(h.handleRevokeShare))
	mux.HandleFunc("/s/", h.handleSharePlay)
	mux.HandleFunc("/admin/contacts/refresh", h.withAuth(h.handleRefreshContacts))
}

func (h *Handler) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":              true,
		"time":            time.Now().UTC().Format(time.RFC3339),
		"ownerConfigured": h.cfg.OwnerPubKey != "",
		"plexConfigured":  h.plex.Enabled(),
	})
}

func (h *Handler) handleNostrChallenge(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	ch, err := h.auth.BuildChallenge(2 * time.Minute)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"challenge": ch})
}

func (h *Handler) handleNostrVerify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var in struct {
		Challenge string          `json:"challenge"`
		Event     json.RawMessage `json:"event"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	pub, err := h.auth.VerifyEvent(nostrsvc.VerifyPayload{
		Challenge: in.Challenge,
		EventJSON: string(in.Event),
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}
	sid := uuid.NewString()
	now := time.Now()
	sess := store.Session{
		ID:        sid,
		PubKey:    pub,
		CreatedAt: now,
		ExpiresAt: now.Add(24 * time.Hour),
	}
	h.store.PutSession(sess)
	http.SetCookie(w, &http.Cookie{
		Name:     h.cfg.SessionCookieName,
		Value:    sid,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   strings.HasPrefix(r.Host, "https://"),
	})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "pubkey": pub})
}

func (h *Handler) handleMe(w http.ResponseWriter, r *http.Request, sess store.Session) {
	allowed, err := h.policy.Allowed(r.Context(), sess.PubKey)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"pubkey":       sess.PubKey,
		"friendAccess": allowed,
		"expiresAt":    sess.ExpiresAt.UTC().Format(time.RFC3339),
	})
}

func (h *Handler) handlePlexPinStart(w http.ResponseWriter, r *http.Request, _ store.Session) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	out, err := h.pin.Start(r.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, http.StatusOK, out)
}

func (h *Handler) handlePlexPinPoll(w http.ResponseWriter, r *http.Request, _ store.Session) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var in struct {
		ID int `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	token, err := h.pin.Poll(r.Context(), in.ID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"token": token})
}

func (h *Handler) handleLibraries(w http.ResponseWriter, r *http.Request, _ store.Session) {
	libs, err := h.plex.ListMusicLibraries(r.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"libraries": libs})
}

func (h *Handler) handleTracks(w http.ResponseWriter, r *http.Request, _ store.Session) {
	sectionID := strings.TrimSpace(r.URL.Query().Get("libraryId"))
	if sectionID == "" {
		http.Error(w, "libraryId required", http.StatusBadRequest)
		return
	}
	items, err := h.plex.SearchTracks(r.Context(), sectionID, strings.TrimSpace(r.URL.Query().Get("q")))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"tracks": items})
}

func (h *Handler) handleStream(w http.ResponseWriter, r *http.Request, _ store.Session) {
	trackID := strings.TrimPrefix(r.URL.Path, "/stream/")
	if trackID == "" {
		http.Error(w, "missing track id", http.StatusBadRequest)
		return
	}
	if err := h.plex.StreamTrack(r.Context(), w, r, trackID); err != nil {
		log.Printf("stream error track=%s err=%v", trackID, err)
		http.Error(w, "stream error", http.StatusBadGateway)
	}
}

func (h *Handler) handleShareSong(w http.ResponseWriter, r *http.Request, sess store.Session) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	trackID := strings.TrimPrefix(r.URL.Path, "/share/song/")
	if strings.TrimSpace(trackID) == "" {
		http.Error(w, "missing track id", http.StatusBadRequest)
		return
	}
	link, err := h.share.Create(trackID, sess.PubKey)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"token": link.Token,
		"url":   "/s/" + link.Token,
	})
}

func (h *Handler) handleRevokeShare(w http.ResponseWriter, r *http.Request, _ store.Session) {
	if r.Method != http.MethodDelete {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	token := strings.TrimPrefix(r.URL.Path, "/share/")
	if token == "" {
		http.Error(w, "missing token", http.StatusBadRequest)
		return
	}
	if !h.share.Revoke(token) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (h *Handler) handleSharePlay(w http.ResponseWriter, r *http.Request) {
	token := strings.TrimPrefix(r.URL.Path, "/s/")
	if token == "" {
		http.Error(w, "missing token", http.StatusBadRequest)
		return
	}
	link, ok := h.share.Get(token)
	if !ok || link.RevokedAt != nil {
		http.Error(w, "share link unavailable", http.StatusNotFound)
		return
	}
	if err := h.plex.StreamTrack(r.Context(), w, r, link.TrackID); err != nil {
		log.Printf("share stream error token=%s err=%v", token, err)
		http.Error(w, "stream error", http.StatusBadGateway)
	}
}

func (h *Handler) handleRefreshContacts(w http.ResponseWriter, r *http.Request, _ store.Session) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if err := h.contacts.ForceRefresh(r.Context()); err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (h *Handler) withAuth(next func(http.ResponseWriter, *http.Request, store.Session)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		sess, err := h.sessionFromRequest(r)
		if err != nil {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		next(w, r, sess)
	}
}

func (h *Handler) withFriend(next func(http.ResponseWriter, *http.Request, store.Session)) http.HandlerFunc {
	return h.withAuth(func(w http.ResponseWriter, r *http.Request, sess store.Session) {
		ok, err := h.policy.Allowed(r.Context(), sess.PubKey)
		if err != nil {
			http.Error(w, "policy unavailable", http.StatusBadGateway)
			return
		}
		if !ok {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		next(w, r, sess)
	})
}

func (h *Handler) sessionFromRequest(r *http.Request) (store.Session, error) {
	c, err := r.Cookie(h.cfg.SessionCookieName)
	if err != nil {
		return store.Session{}, err
	}
	sess, ok := h.store.GetSession(c.Value)
	if !ok {
		return store.Session{}, errors.New("unknown session")
	}
	if time.Now().After(sess.ExpiresAt) {
		return store.Session{}, errors.New("expired")
	}
	return sess, nil
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func TimeoutCtx(parent context.Context, d time.Duration) (context.Context, context.CancelFunc) {
	if d <= 0 {
		return context.WithCancel(parent)
	}
	return context.WithTimeout(parent, d)
}
