package share

import (
	"crypto/rand"
	"encoding/base64"
	"time"

	"github.com/guaka/radio-guaka/internal/store"
)

type LinksService struct {
	store      *store.Store
	tokenBytes int
}

func New(store *store.Store, tokenBytes int) *LinksService {
	return &LinksService{store: store, tokenBytes: tokenBytes}
}

func (s *LinksService) Create(trackID, createdBy string) (store.ShareLink, error) {
	raw := make([]byte, s.tokenBytes)
	if _, err := rand.Read(raw); err != nil {
		return store.ShareLink{}, err
	}
	token := base64.RawURLEncoding.EncodeToString(raw)
	link := store.ShareLink{
		Token:     token,
		TrackID:   trackID,
		CreatedBy: createdBy,
		CreatedAt: time.Now(),
	}
	s.store.PutShare(link)
	return link, nil
}

func (s *LinksService) Get(token string) (store.ShareLink, bool) {
	return s.store.GetShare(token)
}

func (s *LinksService) Revoke(token string) bool {
	return s.store.RevokeShare(token, time.Now())
}
