package nostrsvc

import (
	"context"
	"sync"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

type ContactsService struct {
	ownerPubKey string
	relayURLs   []string
	ttl         time.Duration

	mu        sync.RWMutex
	lastFetch time.Time
	allowed   map[string]struct{}
}

func NewContactsService(ownerPubKey string, relays []string, ttl time.Duration) *ContactsService {
	return &ContactsService{
		ownerPubKey: ownerPubKey,
		relayURLs:   relays,
		ttl:         ttl,
		allowed:     map[string]struct{}{},
	}
}

func (c *ContactsService) Allowed(ctx context.Context, pubKey string) (bool, error) {
	if err := c.refreshIfNeeded(ctx); err != nil {
		return false, err
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	_, ok := c.allowed[pubKey]
	return ok, nil
}

func (c *ContactsService) ForceRefresh(ctx context.Context) error {
	return c.refresh(ctx)
}

func (c *ContactsService) refreshIfNeeded(ctx context.Context) error {
	c.mu.RLock()
	needs := time.Since(c.lastFetch) > c.ttl || c.lastFetch.IsZero()
	c.mu.RUnlock()
	if !needs {
		return nil
	}
	return c.refresh(ctx)
}

func (c *ContactsService) refresh(ctx context.Context) error {
	if c.ownerPubKey == "" {
		return nil
	}
	allowed := map[string]struct{}{}
	pool := nostr.NewSimplePool(ctx)
	ev := pool.QuerySingle(ctx, c.relayURLs, nostr.Filter{
		Authors: []string{c.ownerPubKey},
		Kinds:   []int{3},
		Limit:   1,
	})
	if ev != nil && ev.Event != nil {
		for _, tag := range ev.Event.Tags {
			if len(tag) >= 2 && tag[0] == "p" {
				allowed[tag[1]] = struct{}{}
			}
		}
	}

	c.mu.Lock()
	c.allowed = allowed
	c.lastFetch = time.Now()
	c.mu.Unlock()
	return nil
}
