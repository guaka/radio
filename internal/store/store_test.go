package store

import (
	"path/filepath"
	"sync"
	"testing"
)

func TestAppSecretSurvivesReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.sqlite3")
	s, err := New(path)
	if err != nil {
		t.Fatal(err)
	}
	secret, err := s.GetOrCreateAppSecret("session_secret", 32)
	if err != nil {
		t.Fatal(err)
	}
	if len(secret) != 64 {
		t.Fatalf("unexpected secret length: %d", len(secret))
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	s, err = New(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	again, err := s.GetOrCreateAppSecret("session_secret", 32)
	if err != nil {
		t.Fatal(err)
	}
	if again != secret {
		t.Fatal("secret changed after reopening database")
	}
	other, err := s.GetOrCreateAppSecret("another_key", 32)
	if err != nil {
		t.Fatal(err)
	}
	if other == secret {
		t.Fatal("different keys reused the same secret")
	}
}

func TestAppSecretConcurrentInitialization(t *testing.T) {
	s, err := New(filepath.Join(t.TempDir(), "state.sqlite3"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	var wg sync.WaitGroup
	secrets := make(chan string, 8)
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			secret, err := s.GetOrCreateAppSecret("shared", 32)
			if err != nil {
				t.Error(err)
				return
			}
			secrets <- secret
		}()
	}
	wg.Wait()
	close(secrets)
	var first string
	for secret := range secrets {
		if first == "" {
			first = secret
		}
		if secret != first {
			t.Fatal("concurrent callers received different secrets")
		}
	}
}
