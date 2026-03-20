package store

import (
	"sync"
	"time"
)

type Session struct {
	ID        string
	PubKey    string
	CreatedAt time.Time
	ExpiresAt time.Time
}

type ShareLink struct {
	Token     string
	TrackID   string
	CreatedBy string
	CreatedAt time.Time
	RevokedAt *time.Time
}

type Store struct {
	mu       sync.RWMutex
	sessions map[string]Session
	shares   map[string]ShareLink
}

func New() *Store {
	return &Store{
		sessions: map[string]Session{},
		shares:   map[string]ShareLink{},
	}
}

func (s *Store) PutSession(v Session) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sessions[v.ID] = v
}

func (s *Store) GetSession(id string) (Session, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	v, ok := s.sessions[id]
	return v, ok
}

func (s *Store) PutShare(v ShareLink) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.shares[v.Token] = v
}

func (s *Store) GetShare(token string) (ShareLink, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	v, ok := s.shares[token]
	return v, ok
}

func (s *Store) RevokeShare(token string, when time.Time) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	v, ok := s.shares[token]
	if !ok {
		return false
	}
	v.RevokedAt = &when
	s.shares[token] = v
	return true
}
