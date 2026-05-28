package api

import (
	"context"
	"time"
)

// detachedCtx returns a context that inherits values (e.g. correlation/trace
// IDs) from the parent but is not canceled when the parent is. Used to launch
// saga work that should outlive the HTTP request.
func detachedCtx(parent context.Context) context.Context {
	return detachCtx{parent: parent}
}

type detachCtx struct{ parent context.Context }

func (d detachCtx) Deadline() (time.Time, bool) { return time.Time{}, false }
func (d detachCtx) Done() <-chan struct{}       { return nil }
func (d detachCtx) Err() error                  { return nil }
func (d detachCtx) Value(k any) any             { return d.parent.Value(k) }

// asCtx adapts a ContextLike interface back to context.Context. Since
// ContextLike has the same methods as context.Context, any concrete value
// already satisfies the interface.
func asCtx(c ContextLike) context.Context {
	if cc, ok := c.(context.Context); ok {
		return cc
	}
	return context.Background()
}
