package httpapi

import (
	"github.com/guaka/radio-guaka/internal/store"
	"net/http"
)

// withFriendHTTP protects helpers that can use server-owned credentials.
func (h *Handler) withFriendHTTP(next http.Handler) http.Handler {
	return h.withFriend(func(w http.ResponseWriter, r *http.Request, _ store.Session) {
		next.ServeHTTP(w, r)
	})
}
