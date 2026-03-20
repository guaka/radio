package policy

import (
	"context"
)

type ContactsChecker interface {
	Allowed(ctx context.Context, pubKey string) (bool, error)
}

type AccessPolicy struct {
	contacts ContactsChecker
}

func New(contacts ContactsChecker) *AccessPolicy {
	return &AccessPolicy{contacts: contacts}
}

func (p *AccessPolicy) Allowed(ctx context.Context, pubKey string) (bool, error) {
	return p.contacts.Allowed(ctx, pubKey)
}
