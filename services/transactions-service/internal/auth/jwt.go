package auth

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/lestrrat-go/jwx/v2/jwk"
	"github.com/lestrrat-go/jwx/v2/jwt"
)

type ctxKey int

const (
	ctxKeyClaims ctxKey = iota
)

type Claims struct {
	Subject string
	Token   jwt.Token
}

func FromContext(ctx context.Context) (Claims, bool) {
	v, ok := ctx.Value(ctxKeyClaims).(Claims)
	return v, ok
}

// Verifier verifies JWTs against a JWKS endpoint. If issuer is empty, the
// middleware is disabled and a fixed development subject is used (suitable for
// local demos and tests).
type Verifier struct {
	issuer   string
	audience string

	mu       sync.RWMutex
	keyset   jwk.Set
	cacheExp time.Time
	jwksURL  string
}

func NewVerifier(issuer, audience, jwksURIOverride string) *Verifier {
	v := &Verifier{issuer: issuer, audience: audience}
	if issuer != "" {
		if jwksURIOverride != "" {
			v.jwksURL = jwksURIOverride
		} else {
			v.jwksURL = strings.TrimRight(issuer, "/") + "/.well-known/jwks.json"
		}
	}
	return v
}

func (v *Verifier) keys(ctx context.Context) (jwk.Set, error) {
	v.mu.RLock()
	if v.keyset != nil && time.Now().Before(v.cacheExp) {
		ks := v.keyset
		v.mu.RUnlock()
		return ks, nil
	}
	v.mu.RUnlock()
	set, err := jwk.Fetch(ctx, v.jwksURL)
	if err != nil {
		return nil, err
	}
	v.mu.Lock()
	v.keyset = set
	v.cacheExp = time.Now().Add(5 * time.Minute)
	v.mu.Unlock()
	return set, nil
}

func (v *Verifier) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if v.issuer == "" {
			ctx := context.WithValue(r.Context(), ctxKeyClaims, Claims{Subject: "dev-user"})
			next.ServeHTTP(w, r.WithContext(ctx))
			return
		}
		ah := r.Header.Get("Authorization")
		if !strings.HasPrefix(ah, "Bearer ") {
			http.Error(w, "missing bearer token", http.StatusUnauthorized)
			return
		}
		raw := strings.TrimPrefix(ah, "Bearer ")
		set, err := v.keys(r.Context())
		if err != nil {
			http.Error(w, "jwks unavailable", http.StatusServiceUnavailable)
			return
		}
		opts := []jwt.ParseOption{jwt.WithKeySet(set), jwt.WithIssuer(v.issuer), jwt.WithValidate(true)}
		if v.audience != "" {
			opts = append(opts, jwt.WithAudience(v.audience))
		}
		tok, err := jwt.Parse([]byte(raw), opts...)
		if err != nil {
			http.Error(w, "invalid token", http.StatusUnauthorized)
			return
		}
		sub, _ := tok.Get("sub")
		subStr, _ := sub.(string)
		if subStr == "" {
			http.Error(w, "missing sub", http.StatusUnauthorized)
			return
		}
		ctx := context.WithValue(r.Context(), ctxKeyClaims, Claims{Subject: subStr, Token: tok})
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

var ErrNoClaims = errors.New("no claims in context")
