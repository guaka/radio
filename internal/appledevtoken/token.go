package appledevtoken

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"os"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Generator signs short-lived MusicKit developer tokens (JWT, ES256).
// See https://developer.apple.com/documentation/applemusicapi/generating_developer_tokens
type Generator struct {
	teamID string
	keyID  string
	key    *ecdsa.PrivateKey
}

// NewFromP8File loads an Apple MusicKit private key (.p8 from developer account).
func NewFromP8File(teamID, keyID, path string) (*Generator, error) {
	pemData, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return NewFromPEM(teamID, keyID, pemData)
}

// NewFromPEM parses PKCS#8 ECDSA private key PEM.
func NewFromPEM(teamID, keyID string, pemData []byte) (*Generator, error) {
	block, _ := pem.Decode(pemData)
	if block == nil {
		return nil, errors.New("apple private key: invalid PEM")
	}
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("apple private key: parse PKCS8: %w", err)
	}
	ec, ok := key.(*ecdsa.PrivateKey)
	if !ok || ec.Curve != elliptic.P256() {
		return nil, errors.New("apple private key: expected ECDSA P-256")
	}
	return &Generator{teamID: teamID, keyID: keyID, key: ec}, nil
}

// Token returns a signed JWT. exp is capped under Apple's maximum (~6 months).
func (g *Generator) Token() (string, error) {
	now := time.Now()
	exp := now.Add(150 * 24 * time.Hour)
	t := jwt.NewWithClaims(jwt.SigningMethodES256, jwt.MapClaims{
		"iss": g.teamID,
		"iat": now.Unix(),
		"exp": exp.Unix(),
	})
	t.Header["kid"] = g.keyID
	return t.SignedString(g.key)
}
