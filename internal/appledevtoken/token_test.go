package appledevtoken

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/pem"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

func TestDeveloperToken(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	g, err := NewFromPEM("team", "key-id", pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}))
	if err != nil {
		t.Fatal(err)
	}
	raw, err := g.Token()
	if err != nil {
		t.Fatal(err)
	}
	token, err := jwt.Parse(raw, func(_ *jwt.Token) (any, error) { return &key.PublicKey, nil }, jwt.WithValidMethods([]string{"ES256"}), jwt.WithIssuer("team"), jwt.WithExpirationRequired())
	if err != nil || !token.Valid {
		t.Fatalf("invalid token: %v", err)
	}
	if token.Header["kid"] != "key-id" {
		t.Fatal("missing key identifier")
	}
	exp, err := token.Claims.GetExpirationTime()
	if err != nil || exp == nil || exp.After(time.Now().Add(180*24*time.Hour)) {
		t.Fatal("invalid expiration")
	}
}

func TestRejectInvalidKeys(t *testing.T) {
	if _, err := NewFromPEM("team", "key-id", []byte("invalid")); err == nil {
		t.Fatal("accepted invalid PEM")
	}
	key, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := NewFromPEM("team", "key-id", pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})); err == nil {
		t.Fatal("accepted a non-P256 key")
	}
}
