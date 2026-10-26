package main

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

const reserveBytes int64 = 5 << 30
const maxChunk = 4 << 20
const reservationLifetime = 7 * 24 * time.Hour

var hashPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)
var safeName = regexp.MustCompile(`[^\pL\pN._ -]+`)

type Fault struct {
	Code      string
	Message   string
	Status    int
	Retryable bool
}

func (e *Fault) Error() string                             { return e.Message }
func fault(code, msg string, status int, retry bool) error { return &Fault{code, msg, status, retry} }

type Upload struct {
	ID      string `json:"id"`
	SHA256  string `json:"sha256"`
	Size    int64  `json:"size"`
	Offset  int64  `json:"offset"`
	Name    string `json:"name"`
	Mime    string `json:"mime"`
	Taken   int64  `json:"taken"`
	State   string `json:"state"`
	Path    string `json:"path"`
	Error   string `json:"error"`
	Updated int64  `json:"updated,omitempty"`
}
type Source struct {
	Device   string `json:"device"`
	SourceID string `json:"source_id"`
	Version  string `json:"version"`
	Folder   string `json:"folder"`
	Name     string `json:"name"`
	SHA256   string `json:"sha256"`
	Taken    int64  `json:"taken"`
}
type CreateRequest struct {
	Upload
	SourceID string `json:"source_id"`
	Version  string `json:"version"`
	Folder   string `json:"folder"`
}
type Store struct {
	rootFS      *os.Root
	rootInfo    os.FileInfo
	root        string
	db          *sql.DB
	mu          sync.Mutex
	wg          sync.WaitGroup
	library     string
	volume      uint64
	reserve     int64
	free        func(string) (int64, error)
	verifySlots chan struct{}
	active      map[string]bool
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}
func digest(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func diskFree(p string) (int64, error) {
	var st syscall.Statfs_t
	err := syscall.Statfs(p, &st)
	return int64(st.Bavail) * int64(st.Bsize), err
}
func deviceID(p string) (uint64, error) {
	var st syscall.Stat_t
	err := syscall.Stat(p, &st)
	return uint64(st.Dev), err
}
func syncDir(p string) error {
	f, e := os.Open(p)
	if e != nil {
		return e
	}
	defer f.Close()
	return f.Sync()
}
func atomicJSON(path string, v any) error {
	b, e := json.MarshalIndent(v, "", "  ")
	if e != nil {
		return e
	}
	f, e := os.CreateTemp(filepath.Dir(path), ".writing-")
	if e != nil {
		return e
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if e = f.Chmod(0600); e == nil {
		_, e = f.Write(b)
	}
	if e == nil {
		e = f.Sync()
	}
	closeErr := f.Close()
	if e == nil {
		e = closeErr
	}
	if e != nil {
		return e
	}
	if e = os.Rename(tmp, path); e != nil {
		return e
	}
	return syncDir(filepath.Dir(path))
}
func fileHash(path string) (string, int64, error) {
	before, e := os.Lstat(path)
	if e != nil {
		return "", 0, e
	}
	if !before.Mode().IsRegular() {
		return "", 0, errors.New("not a regular file")
	}
	f, e := os.Open(path)
	if e != nil {
		return "", 0, e
	}
	defer f.Close()
	st, e := f.Stat()
	if e != nil {
		return "", 0, e
	}
	if !os.SameFile(before, st) {
		return "", 0, errors.New("file changed while opening")
	}
	h := sha256.New()
	n, e := io.CopyBuffer(h, f, make([]byte, 256<<10))
	if e != nil {
		return "", n, e
	}
	after, e := os.Lstat(path)
	if e != nil {
		return "", n, e
	}
	if !os.SameFile(st, after) || st.Size() != after.Size() || !st.ModTime().Equal(after.ModTime()) || n != st.Size() {
		return "", n, errors.New("file changed while reading")
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}

// Reject symlinks and traversal in catalog/recovery-controlled paths.
func (s *Store) safePath(rel string) (string, error) {
	if rel == "" || filepath.IsAbs(rel) || filepath.Clean(rel) != rel || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return "", errors.New("unsafe library path")
	}
	p := s.root
	for _, part := range strings.Split(rel, string(os.PathSeparator)) {
		p = filepath.Join(p, part)
		st, e := os.Lstat(p)
		if os.IsNotExist(e) {
			continue
		}
		if e != nil {
			return "", e
		}
		if st.Mode()&os.ModeSymlink != 0 {
			return "", errors.New("symlink in library path")
		}
	}
	return p, nil
}

// All runtime writes use a pinned directory handle, so a disappearing mount
// cannot cause MkdirAll to recreate a library on the Mac's internal filesystem.
func (s *Store) syncDir(rel string) error {
	f, e := s.rootFS.Open(rel)
	if e != nil {
		return e
	}
	defer f.Close()
	return f.Sync()
}
func (s *Store) mkdir(rel string) error {
	if e := s.rootFS.MkdirAll(rel, 0700); e != nil {
		return e
	}
	for p := rel; ; p = filepath.Dir(p) {
		if e := s.syncDir(p); e != nil {
			return e
		}
		if p == "." {
			return nil
		}
	}
}
func (s *Store) atomicJSON(rel string, v any) error {
	b, e := json.MarshalIndent(v, "", "  ")
	if e != nil {
		return e
	}
	tmp := filepath.Join(filepath.Dir(rel), ".writing-"+randomHex(12))
	f, e := s.rootFS.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	defer s.rootFS.Remove(tmp)
	_, e = f.Write(b)
	if e == nil {
		e = f.Sync()
	}
	closeErr := f.Close()
	if e == nil {
		e = closeErr
	}
	if e != nil {
		return e
	}
	if e = s.rootFS.Rename(tmp, rel); e != nil {
		return e
	}
	return s.syncDir(filepath.Dir(rel))
}
func OpenStore(root, library string) (*Store, error) {
	canonical, e := filepath.EvalSymlinks(root)
	if e != nil {
		return nil, e
	}
	root = canonical
	volume, e := deviceID(root)
	if e != nil {
		return nil, e
	}
	s := &Store{root: root, library: library, volume: volume, reserve: reserveBytes, free: diskFree, verifySlots: make(chan struct{}, 2), active: make(map[string]bool)}
	s.rootFS, e = os.OpenRoot(root)
	if e != nil {
		return nil, e
	}
	opened := false
	defer func() {
		if !opened {
			s.rootFS.Close()
		}
	}()
	s.rootInfo, e = s.rootFS.Stat(".")
	if e != nil {
		s.rootFS.Close()
		return nil, e
	}
	if e = s.guard(); e != nil {
		s.rootFS.Close()
		return nil, e
	}
	if _, e = s.safePath("catalog.sqlite"); e != nil {
		return nil, e
	}
	catalog, e := s.rootFS.OpenFile("catalog.sqlite", os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		s.rootFS.Close()
		return nil, e
	}
	if e = catalog.Sync(); e != nil {
		catalog.Close()
		s.rootFS.Close()
		return nil, e
	}
	catalog.Close()
	dbURL := url.URL{Scheme: "file", Path: filepath.Join(root, "catalog.sqlite")}
	db, e := sql.Open("sqlite3", dbURL.String()+"?mode=rw&_journal_mode=DELETE&_synchronous=FULL&_busy_timeout=10000&_foreign_keys=on")
	if e != nil {
		return nil, e
	}
	db.SetMaxOpenConns(1)
	s.db = db
	_, e = db.Exec(`PRAGMA fullfsync=ON;
 CREATE TABLE IF NOT EXISTS uploads(id TEXT PRIMARY KEY, data TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS sources(id TEXT PRIMARY KEY, data TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS devices(token_hash TEXT PRIMARY KEY, name TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
 CREATE INDEX IF NOT EXISTS uploads_state ON uploads(json_extract(data,'$.state'));`)
	if e != nil {
		db.Close()
		return nil, e
	}
	var saved string
	e = db.QueryRow("SELECT value FROM meta WHERE key='library'").Scan(&saved)
	if e == sql.ErrNoRows {
		_, e = db.Exec("INSERT INTO meta VALUES('library',?)", library)
	} else if e == nil && saved != library {
		e = errors.New("catalog belongs to a different library")
	}
	if e != nil {
		db.Close()
		return nil, e
	}
	opened = true
	return s, nil
}
func (s *Store) Close() { s.wg.Wait(); s.db.Close(); s.rootFS.Close() }
func (s *Store) guard() error {
	if s.rootInfo != nil {
		current, e := os.Stat(s.root)
		if e != nil || !os.SameFile(current, s.rootInfo) {
			return fault("drive_unavailable", "Library directory was removed or replaced", 503, true)
		}
	}
	v, e := deviceID(s.root)
	if e != nil || v != s.volume {
		return fault("drive_unavailable", "Drive disconnected or replaced", 503, true)
	}
	for _, rel := range []string{".personal-backup-library", "staging", "originals", "manifests", "sources", "quarantine"} {
		if _, e = s.safePath(rel); e != nil {
			return fault("drive_unavailable", "Unsafe or inaccessible library path", 503, true)
		}
	}
	b, e := os.ReadFile(filepath.Join(s.root, ".personal-backup-library"))
	if e != nil || strings.TrimSpace(string(b)) != s.library {
		return fault("drive_unavailable", "Library identity is missing or changed", 503, true)
	}
	return nil
}
func (s *Store) get(id string) (Upload, error) {
	var u Upload
	var b string
	e := s.db.QueryRow("SELECT data FROM uploads WHERE id=?", id).Scan(&b)
	if e == sql.ErrNoRows {
		return u, fault("not_found", "Backup not found", 404, false)
	}
	if e != nil {
		return u, e
	}
	e = json.Unmarshal([]byte(b), &u)
	return u, e
}
func (s *Store) save(u Upload) error {
	u.Updated = time.Now().Unix()
	b, e := json.Marshal(u)
	if e != nil {
		return e
	}
	_, e = s.db.Exec("INSERT INTO uploads(id,data) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data", u.ID, string(b))
	return e
}
func (s *Store) space(extra int64) error {
	free, e := s.free(s.root)
	if e != nil {
		return fault("drive_unavailable", "Cannot read drive capacity", 503, true)
	}
	var reserved int64
	e = s.db.QueryRow("SELECT COALESCE(SUM(MAX(0,CAST(json_extract(data,'$.size') AS INTEGER)-CAST(json_extract(data,'$.offset') AS INTEGER))),0) FROM uploads WHERE json_extract(data,'$.state')='verifying' OR (json_extract(data,'$.state')='uploading' AND COALESCE(json_extract(data,'$.updated'),0)>=?)", time.Now().Add(-reservationLifetime).Unix()).Scan(&reserved)
	if e != nil {
		return e
	}
	if extra < 0 || free < s.reserve || reserved > free-s.reserve || extra > free-s.reserve-reserved {
		return fault("storage_full", "Not enough unreserved storage; 5 GiB is kept free", 507, true)
	}
	return nil
}
func (s *Store) source(req CreateRequest, device string) error {
	src := Source{device, req.SourceID, req.Version, req.Folder, req.Name, req.SHA256, req.Taken}
	b, e := json.Marshal(src)
	if e != nil {
		return e
	}
	id := digest([]byte(device + "\x00" + req.SourceID + "\x00" + req.Version + "\x00" + req.SHA256))
	if e = s.atomicJSON(filepath.Join("sources", id+".json"), src); e != nil {
		return e
	}
	_, e = s.db.Exec("INSERT INTO sources VALUES(?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data", id, string(b))
	return e
}
func (s *Store) Create(req CreateRequest, device string) (Upload, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var zero Upload
	if e := s.guard(); e != nil {
		return zero, e
	}
	if !hashPattern.MatchString(req.SHA256) || req.Size <= 0 || req.Size > 1<<50 || req.SourceID == "" || req.Version == "" || len(req.Name) > 512 || len(req.Mime) > 256 || len(req.SourceID) > 2048 || len(req.Version) > 1024 || len(req.Folder) > 2048 {
		return zero, fault("invalid_media", "Invalid size, hash, or source metadata", 400, false)
	}
	u, e := s.get(req.SHA256)
	if e == nil {
		if u.Size != req.Size {
			return zero, fault("size_conflict", "Hash already exists with a different length", 409, false)
		}
		if u.State == "corrupt" {
			return u, fault("integrity_error", "Stored object needs repair; run server repair", 409, false)
		}
		if u.State == "committed" {
			st, err := os.Stat(filepath.Join(s.root, u.Path))
			if err != nil || st.Size() != u.Size {
				return u, fault("integrity_error", "Stored object missing or changed; run server repair", 409, false)
			}
		}
		if u.State == "uploading" && u.Offset < u.Size {
			part, err := s.stageForWrite(u)
			if err != nil {
				return u, err
			}
			part.Close()
		}
		if u.State == "uploading" && u.Updated < time.Now().Add(-reservationLifetime).Unix() {
			if e = s.space(u.Size - u.Offset); e != nil {
				return u, e
			}
			if e = s.save(u); e != nil {
				return u, e
			}
		}
		return u, s.source(req, device)
	}
	var f *Fault
	if !errors.As(e, &f) || f.Code != "not_found" {
		return zero, e
	}
	if e = s.space(req.Size); e != nil {
		return zero, e
	}
	u = req.Upload
	u.ID = u.SHA256
	u.Offset = 0
	u.State = "uploading"
	u.Error = ""
	u.Path = ""
	fstage, e := s.rootFS.OpenFile(filepath.Join("staging", u.ID+".part"), os.O_CREATE|os.O_TRUNC|os.O_RDWR, 0600)
	if e != nil {
		return zero, e
	}
	e = fstage.Sync()
	fstage.Close()
	if e != nil {
		return zero, e
	}
	if e = s.syncDir("staging"); e != nil {
		return zero, e
	}
	if e = s.save(u); e != nil {
		return zero, e
	}
	return u, s.source(req, device)
}

// Discard only bytes beyond the last durable catalog acknowledgment. Reclaim
// this space before capacity checks, otherwise a crash tail can block resuming.
func (s *Store) stageForWrite(u Upload) (*os.File, error) {
	rel := filepath.Join("staging", u.ID+".part")
	if _, e := s.safePath(rel); e != nil {
		return nil, e
	}
	f, e := s.rootFS.OpenFile(rel, os.O_RDWR, 0600)
	if e != nil {
		return nil, e
	}
	st, e := f.Stat()
	if e != nil {
		f.Close()
		return nil, e
	}
	if !st.Mode().IsRegular() || st.Size() < u.Offset {
		f.Close()
		return nil, fault("integrity_error", "Staging file shorter than acknowledged offset or not a regular file", 409, false)
	}
	if st.Size() > u.Offset {
		if e = f.Truncate(u.Offset); e == nil {
			e = f.Sync()
		}
		if e != nil {
			f.Close()
			return nil, e
		}
	}
	return f, nil
}
func (s *Store) Chunk(id string, offset int64, data []byte, checksum string) (Upload, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var zero Upload
	if e := s.guard(); e != nil {
		return zero, e
	}
	u, e := s.get(id)
	if e != nil {
		return zero, e
	}
	if len(data) == 0 || len(data) > maxChunk || digest(data) != checksum {
		return u, fault("chunk_checksum", "Chunk length or checksum invalid", 422, true)
	}
	if offset < 0 || offset > u.Size || int64(len(data)) > u.Size-offset {
		return u, fault("invalid_offset", "Chunk exceeds file size", 409, false)
	}
	// Old offsets are acknowledged only after comparing the exact durable bytes.
	if offset < u.Offset {
		if offset+int64(len(data)) > u.Offset {
			return u, fault("offset_conflict", "Query the current durable offset", 409, true)
		}
		path := filepath.Join(s.root, "staging", id+".part")
		if u.State == "committed" {
			path = filepath.Join(s.root, u.Path)
		}
		f, e := os.Open(path)
		if e != nil {
			return u, e
		}
		defer f.Close()
		b := make([]byte, len(data))
		_, e = f.ReadAt(b, offset)
		if e != nil || digest(b) != checksum {
			return u, fault("offset_conflict", "Repeated chunk differs from durable content", 409, false)
		}
		return u, nil
	}
	if u.State != "uploading" || offset != u.Offset {
		return u, fault("offset_conflict", "Query the current upload state and offset", 409, true)
	}
	extra := int64(0)
	if u.Updated < time.Now().Add(-reservationLifetime).Unix() {
		extra = u.Size - u.Offset
	}
	f, e := s.stageForWrite(u)
	if e != nil {
		return u, e
	}
	defer f.Close()
	if e = s.space(extra); e != nil {
		return u, e
	}
	if _, e = f.WriteAt(data, u.Offset); e != nil {
		return u, e
	}
	if e = f.Sync(); e != nil {
		return u, e
	}
	u.Offset += int64(len(data))
	if e = s.save(u); e != nil {
		return zero, e
	}
	return u, nil
}
func (s *Store) Finish(id string) (Upload, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if e := s.guard(); e != nil {
		return Upload{}, e
	}
	u, e := s.get(id)
	if e != nil {
		return u, e
	}
	if u.State == "committed" || (u.State == "verifying" && s.active[u.ID]) {
		return u, nil
	}
	if (u.State != "uploading" && u.State != "verifying") || u.Offset != u.Size {
		return u, fault("incomplete", "Upload is not complete", 409, false)
	}
	select {
	case s.verifySlots <- struct{}{}:
	default:
		return u, fault("verification_busy", "Two files are being verified; retry shortly", 503, true)
	}
	u.State = "verifying"
	if e = s.save(u); e != nil {
		<-s.verifySlots
		return u, e
	}
	s.startVerify(u)
	return u, nil
}
func (s *Store) startVerify(u Upload) {
	s.active[u.ID] = true
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		defer func() { s.mu.Lock(); delete(s.active, u.ID); s.mu.Unlock(); <-s.verifySlots }()
		s.verifyResult(u)
	}()
}
func (s *Store) verifyResult(u Upload) {
	if e := s.finalize(u); e != nil {
		log.Printf("Finalization %s failed: %v", u.ID, e)
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.guard() == nil {
			// I/O errors remain retryable. Only a proven content mismatch is permanent.
			var f *Fault
			if errors.As(e, &f) && f.Code == "integrity_error" {
				u.State = "corrupt"
				u.Error = "Full-file checksum mismatch; source must be retried after server repair"
			} else {
				u.State = "uploading"
				u.Error = "Finalization interrupted; retry finish"
			}
			if err := s.save(u); err != nil {
				log.Printf("Cannot persist finalization state for %s: %v", u.ID, err)
			}
		}
	}
}
func (s *Store) finalize(u Upload) error {
	if e := s.guard(); e != nil {
		return e
	}
	part := filepath.Join(s.root, "staging", u.ID+".part")
	if u.Path == "" {
		folder := "Undated"
		if u.Taken > 0 {
			folder = time.UnixMilli(u.Taken).UTC().Format("2006/01")
		}
		name := safeName.ReplaceAllString(filepath.Base(u.Name), "_")
		if len(name) > 120 {
			ext := filepath.Ext(name)
			if len(ext) > 16 {
				ext = ""
			}
			name = "media" + ext
		}
		if name == "" || name == "." {
			name = "media"
		}
		u.Path = filepath.Join("originals", folder, u.ID+"--"+name)
	}
	final, e := s.safePath(u.Path)
	if e != nil {
		return e
	}
	input := part
	if _, e := os.Stat(final); e == nil {
		input = final
	}
	h, n, e := fileHash(input)
	if e != nil {
		return e
	}
	if h != u.SHA256 || n != u.Size {
		return fault("integrity_error", "Full-file checksum mismatch", 409, false)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if e = s.guard(); e != nil {
		return e
	}
	if e = s.mkdir(filepath.Dir(u.Path)); e != nil {
		return e
	}
	u.State = "committed"
	u.Error = ""
	// Persist an intent manifest before rename. Rebuild verifies the file, never trusts the intent alone.
	if e = s.atomicJSON(filepath.Join("manifests", u.ID+".json"), u); e != nil {
		return e
	}
	if input != final {
		if e = s.rootFS.Rename(filepath.Join("staging", u.ID+".part"), u.Path); e != nil {
			return e
		}
		if e = s.syncDir(filepath.Dir(u.Path)); e != nil {
			return e
		}
		if e = s.syncDir("staging"); e != nil {
			return e
		}
	}
	return s.save(u)
}
func (s *Store) Recover() error {
	// Bounded pages and sequential verification avoid a goroutine/buffer per catalog row.
	after := ""
	for {
		if e := s.guard(); e != nil {
			return e
		}
		rows, e := s.db.Query("SELECT id,data FROM uploads WHERE json_extract(data,'$.state')='verifying' AND id>? ORDER BY id LIMIT 64", after)
		if e != nil {
			return e
		}
		var pending []Upload
		for rows.Next() {
			var id, b string
			if e = rows.Scan(&id, &b); e != nil {
				rows.Close()
				return e
			}
			var u Upload
			if e = json.Unmarshal([]byte(b), &u); e != nil {
				rows.Close()
				return e
			}
			pending = append(pending, u)
			after = id
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return e
		}
		if len(pending) == 0 {
			return nil
		}
		for _, u := range pending {
			s.verifyResult(u)
		}
	}
}

// Resume verification after a removable drive returns, without requiring a restart.
func (s *Store) RetryVerifying() {
	if s.guard() != nil {
		return
	}
	rows, e := s.db.Query("SELECT id FROM uploads WHERE json_extract(data,'$.state')='verifying' ORDER BY id LIMIT 64")
	if e != nil {
		return
	}
	var ids []string
	for rows.Next() {
		var id string
		if rows.Scan(&id) == nil {
			ids = append(ids, id)
		}
	}
	rows.Close()
	for _, id := range ids {
		_, _ = s.Finish(id)
	}
}
func (s *Store) Verify(id string) (Upload, error) {
	if e := s.guard(); e != nil {
		return Upload{}, e
	}
	u, e := s.get(id)
	if e != nil {
		return u, e
	}
	if u.State != "committed" {
		return u, fault("not_committed", "Backup is not verified", 409, false)
	}
	path, e := s.safePath(u.Path)
	if e != nil {
		return u, fault("integrity_error", "Stored path is unsafe; cleanup denied", 409, false)
	}
	h, n, e := fileHash(path)
	if e != nil || h != u.SHA256 || n != u.Size {
		return u, fault("integrity_error", "Stored file is missing or corrupt; cleanup denied", 409, false)
	}
	if e = s.guard(); e != nil {
		return u, e
	}
	return u, nil
}
func (s *Store) Rebuild() error {
	// Called offline. Existing devices are kept; source/catalog records are reconstructed from manifests.
	return filepath.WalkDir(filepath.Join(s.root, "manifests"), func(path string, d os.DirEntry, e error) error {
		if e != nil {
			return e
		}
		if d.IsDir() || !strings.HasSuffix(path, ".json") {
			return nil
		}
		if e = s.guard(); e != nil {
			return e
		}
		b, e := os.ReadFile(path)
		if e != nil {
			return e
		}
		var u Upload
		if e = json.Unmarshal(b, &u); e != nil {
			return e
		}
		if !hashPattern.MatchString(u.ID) || u.ID != u.SHA256 {
			return fmt.Errorf("invalid manifest %s", path)
		}
		if !strings.HasPrefix(u.Path, "originals/") {
			return errors.New("unsafe manifest path")
		}
		final, e := s.safePath(u.Path)
		if e != nil {
			return e
		}
		h, n, e := fileHash(final)
		if os.IsNotExist(e) {
			part := filepath.Join(s.root, "staging", u.ID+".part")
			h, n, e = fileHash(part)
			if e == nil && h == u.SHA256 && n == u.Size {
				if e = s.mkdir(filepath.Dir(u.Path)); e == nil {
					e = s.rootFS.Rename(filepath.Join("staging", u.ID+".part"), u.Path)
				}
				if e == nil {
					e = s.syncDir(filepath.Dir(u.Path))
				}
				if e == nil {
					e = s.syncDir("staging")
				}
			}
		}
		if e != nil || h != u.SHA256 || n != u.Size {
			return fmt.Errorf("cannot recover %s: file missing or checksum mismatch", u.Name)
		}
		u.State = "committed"
		u.Offset = u.Size
		u.Error = ""
		return s.save(u)
	})
}
