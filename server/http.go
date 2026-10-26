package main

import (
	"bytes"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

type API struct {
	store       *Store
	pairMu      sync.Mutex
	verifySlots chan struct{}
	bodySlots   chan struct{}
	requests    sync.WaitGroup
}
type PairSecret struct {
	Hash    string `json:"hash"`
	Expires int64  `json:"expires"`
}

func jsonResponse(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
func apiError(w http.ResponseWriter, e error) {
	var f *Fault
	if !errors.As(e, &f) {
		log.Printf("Storage operation failed (%T): %v", e, e)
		f = &Fault{"storage_error", "Storage operation failed; check the drive and server logs", 503, true}
	}
	jsonResponse(w, f.Status, map[string]any{"error": f.Code, "message": f.Message, "retryable": f.Retryable})
}
func decode(r *http.Request, v any) error {
	defer r.Body.Close()
	b, e := io.ReadAll(io.LimitReader(r.Body, (64<<10)+1))
	if e != nil || len(b) > 64<<10 {
		return fault("invalid_request", "Request exceeds 64 KiB or was interrupted", 400, false)
	}
	d := json.NewDecoder(bytes.NewReader(b))
	d.DisallowUnknownFields()
	if e = d.Decode(v); e != nil {
		return fault("invalid_request", "Invalid JSON request", 400, false)
	}
	if e = d.Decode(new(any)); e != io.EOF {
		return fault("invalid_request", "Unexpected trailing request content", 400, false)
	}
	return nil
}
func (a *API) Handler() http.Handler {
	if a.verifySlots == nil {
		a.verifySlots = make(chan struct{}, 2)
	}
	if a.bodySlots == nil {
		a.bodySlots = make(chan struct{}, 4)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/pair", a.pair)
	mux.HandleFunc("GET /v1/status", a.auth(func(w http.ResponseWriter, r *http.Request, device string) {
		if e := a.store.guard(); e != nil {
			apiError(w, e)
			return
		}
		free, e := a.store.free(a.store.root)
		if e != nil {
			apiError(w, e)
			return
		}
		jsonResponse(w, 200, map[string]any{"library_id": a.store.library, "free_bytes": free, "reserve_bytes": a.store.reserve, "protocol": 1})
	}))
	mux.HandleFunc("POST /v1/uploads", a.auth(func(w http.ResponseWriter, r *http.Request, device string) {
		var req CreateRequest
		if e := decode(r, &req); e != nil {
			apiError(w, e)
			return
		}
		u, e := a.store.Create(req, device)
		if e != nil {
			apiError(w, e)
			return
		}
		jsonResponse(w, 200, u)
	}))
	mux.HandleFunc("GET /v1/uploads/{id}", a.auth(func(w http.ResponseWriter, r *http.Request, device string) {
		u, e := a.store.get(r.PathValue("id"))
		if e != nil {
			apiError(w, e)
			return
		}
		jsonResponse(w, 200, u)
	}))
	mux.HandleFunc("GET /v1/backups/{id}", a.auth(func(w http.ResponseWriter, r *http.Request, device string) {
		u, e := a.store.get(r.PathValue("id"))
		if e != nil {
			apiError(w, e)
			return
		}
		jsonResponse(w, 200, u)
	}))
	mux.HandleFunc("PUT /v1/uploads/{id}/chunk", a.auth(func(w http.ResponseWriter, r *http.Request, device string) {
		offset, e := strconv.ParseInt(r.Header.Get("Upload-Offset"), 10, 64)
		if e != nil {
			apiError(w, fault("invalid_offset", "Upload-Offset must be an integer", 400, false))
			return
		}
		select {
		case a.bodySlots <- struct{}{}:
			defer func() { <-a.bodySlots }()
		default:
			apiError(w, fault("server_busy", "Four upload chunks are already in progress", 503, true))
			return
		}
		b, e := io.ReadAll(http.MaxBytesReader(w, r.Body, maxChunk))
		r.Body.Close()
		if e != nil {
			apiError(w, fault("invalid_chunk", "Chunk exceeds 4 MiB or was interrupted", 400, true))
			return
		}
		u, e := a.store.Chunk(r.PathValue("id"), offset, b, r.Header.Get("Chunk-SHA256"))
		if e != nil {
			apiError(w, e)
			return
		}
		jsonResponse(w, 200, u)
	}))
	mux.HandleFunc("POST /v1/uploads/{id}/finish", a.auth(func(w http.ResponseWriter, r *http.Request, device string) {
		u, e := a.store.Finish(r.PathValue("id"))
		if e != nil {
			apiError(w, e)
			return
		}
		jsonResponse(w, 202, u)
	}))
	mux.HandleFunc("POST /v1/backups/{id}/verify", a.auth(func(w http.ResponseWriter, r *http.Request, device string) {
		select {
		case a.verifySlots <- struct{}{}:
			defer func() { <-a.verifySlots }()
		case <-r.Context().Done():
			return
		default:
			apiError(w, fault("verification_busy", "Two backup checks are in progress", 503, true))
			return
		}
		u, e := a.store.Verify(r.PathValue("id"))
		if e != nil {
			apiError(w, e)
			return
		}
		jsonResponse(w, 200, map[string]any{"library_id": a.store.library, "sha256": u.SHA256, "size": u.Size, "verified": true, "verified_at": time.Now().UTC()})
	}))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		a.requests.Add(1)
		defer a.requests.Done()
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Library-ID", a.store.library)
		mux.ServeHTTP(w, r)
	})
}
func (a *API) auth(next func(http.ResponseWriter, *http.Request, string)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if e := a.store.guard(); e != nil {
			apiError(w, e)
			return
		}
		authorization := r.Header.Get("Authorization")
		token := strings.TrimPrefix(authorization, "Bearer ")
		if !strings.HasPrefix(authorization, "Bearer ") || len(token) != 64 {
			apiError(w, fault("unauthorized", "Pair this device with the Mac", 401, false))
			return
		}
		var name string
		hash := digest([]byte(token))
		if e := a.store.db.QueryRow("SELECT name FROM devices WHERE token_hash=?", hash).Scan(&name); e != nil {
			apiError(w, fault("unauthorized", "Pair this device with the Mac", 401, false))
			return
		}
		next(w, r, hash)
	}
}
func (a *API) pair(w http.ResponseWriter, r *http.Request) {
	a.pairMu.Lock()
	defer a.pairMu.Unlock()
	if e := a.store.guard(); e != nil {
		apiError(w, e)
		return
	}
	var req struct {
		Secret string `json:"secret"`
		Name   string `json:"name"`
	}
	if e := decode(r, &req); e != nil {
		apiError(w, e)
		return
	}
	b, e := os.ReadFile(filepath.Join(a.store.root, "pairing.json"))
	var p PairSecret
	if e != nil || json.Unmarshal(b, &p) != nil || time.Now().Unix() > p.Expires || subtle.ConstantTimeCompare([]byte(p.Hash), []byte(digest([]byte(req.Secret)))) != 1 {
		apiError(w, fault("pairing_expired", "Generate a fresh pairing code on the Mac", 403, false))
		return
	}
	if len(req.Name) > 128 || len(req.Secret) != 64 {
		apiError(w, fault("invalid_request", "Invalid pairing request", 400, false))
		return
	}
	token := randomHex(32)
	_, e = a.store.db.Exec("INSERT INTO devices VALUES(?,?)", digest([]byte(token)), req.Name)
	if e != nil {
		apiError(w, e)
		return
	}
	if e = a.store.rootFS.Remove("pairing.json"); e != nil {
		apiError(w, e)
		return
	}
	if e = a.store.syncDir("."); e != nil {
		apiError(w, e)
		return
	}
	jsonResponse(w, 200, map[string]any{"token": token, "library_id": a.store.library})
}
