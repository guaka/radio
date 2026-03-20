package nostrsvc

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

type ChallengeClaims struct {
	Nonce string `json:"nonce"`
	Exp   int64  `json:"exp"`
}

type VerifyPayload struct {
	Challenge string `json:"challenge"`
	EventJSON string `json:"event"`
}

type AuthService struct {
	secret []byte
}

func NewAuthService(secret string) *AuthService {
	return &AuthService{secret: []byte(secret)}
}

func (a *AuthService) BuildChallenge(ttl time.Duration) (string, error) {
	nonce := make([]byte, 16)
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	claims := ChallengeClaims{
		Nonce: hex.EncodeToString(nonce),
		Exp:   time.Now().Add(ttl).Unix(),
	}
	raw, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	sig := a.sign(raw)
	token := base64.RawURLEncoding.EncodeToString(raw) + "." + base64.RawURLEncoding.EncodeToString(sig)
	return token, nil
}

func (a *AuthService) VerifyEvent(payload VerifyPayload) (string, error) {
	if err := a.verifyChallenge(payload.Challenge); err != nil {
		return "", err
	}

	var ev nostr.Event
	if err := json.Unmarshal([]byte(payload.EventJSON), &ev); err != nil {
		return "", err
	}
	if ok, err := ev.CheckSignature(); err != nil || !ok {
		if err != nil {
			return "", err
		}
		return "", errors.New("invalid signature")
	}
	if ev.PubKey == "" {
		return "", errors.New("missing pubkey")
	}

	want := "auth:" + payload.Challenge
	if ev.Content != want {
		return "", fmt.Errorf("unexpected event content")
	}
	return ev.PubKey, nil
}

func (a *AuthService) verifyChallenge(token string) error {
	parts := split2(token, '.')
	if len(parts) != 2 {
		return errors.New("bad challenge")
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return err
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return err
	}
	if !hmac.Equal(a.sign(raw), sig) {
		return errors.New("invalid challenge signature")
	}
	var claims ChallengeClaims
	if err := json.Unmarshal(raw, &claims); err != nil {
		return err
	}
	if time.Now().Unix() > claims.Exp {
		return errors.New("challenge expired")
	}
	return nil
}

func (a *AuthService) sign(payload []byte) []byte {
	h := hmac.New(sha256.New, a.secret)
	h.Write(payload)
	return h.Sum(nil)
}

func split2(v string, sep byte) []string {
	for i := 0; i < len(v); i++ {
		if v[i] == sep {
			return []string{v[:i], v[i+1:]}
		}
	}
	return []string{v}
}
