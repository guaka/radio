package store

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	_ "modernc.org/sqlite"
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
	db       *sql.DB
}

func New(dbPath string) (*Store, error) {
	if dbPath == "" {
		dbPath = filepath.Join("data", "plextr.sqlite3")
	}
	if err := os.MkdirAll(filepath.Dir(dbPath), 0o755); err != nil {
		return nil, fmt.Errorf("create db dir: %w", err)
	}
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(`
CREATE TABLE IF NOT EXISTS app_state (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
`); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("migrate sqlite: %w", err)
	}
	return &Store{
		sessions: map[string]Session{},
		shares:   map[string]ShareLink{},
		db:       db,
	}, nil
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

func (s *Store) Close() error {
	if s == nil || s.db == nil {
		return nil
	}
	return s.db.Close()
}

func (s *Store) GetOrCreateAppSecret(key string, nBytes int) (string, error) {
	if s == nil || s.db == nil {
		return "", fmt.Errorf("store not initialized")
	}
	if nBytes < 16 {
		nBytes = 16
	}
	var existing string
	err := s.db.QueryRow(`SELECT v FROM app_state WHERE k = ?`, key).Scan(&existing)
	if err == nil && existing != "" {
		return existing, nil
	}
	if err != nil && err != sql.ErrNoRows {
		return "", err
	}
	buf := make([]byte, nBytes)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	secret := hex.EncodeToString(buf)
	_, err = s.db.Exec(`
INSERT INTO app_state (k, v, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)
ON CONFLICT(k) DO NOTHING
`, key, secret)
	if err != nil {
		return "", err
	}
	// Read the winner when multiple processes initialize the same key at once.
	err = s.db.QueryRow(`SELECT v FROM app_state WHERE k = ?`, key).Scan(&secret)
	return secret, err
}
