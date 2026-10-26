package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func testStore(t *testing.T) *Store {
	t.Helper()
	root := t.TempDir()
	for _, d := range []string{"staging", "originals", "manifests", "sources", "quarantine"} {
		if e := os.Mkdir(filepath.Join(root, d), 0700); e != nil {
			t.Fatal(e)
		}
	}
	os.WriteFile(filepath.Join(root, ".personal-backup-library"), []byte("test-library"), 0600)
	s, e := OpenStore(root, "test-library")
	if e != nil {
		t.Fatal(e)
	}
	s.reserve = 0
	t.Cleanup(s.Close)
	return s
}
func reqFor(b []byte) CreateRequest {
	return CreateRequest{Upload: Upload{SHA256: digest(b), Size: int64(len(b)), Name: "photo.jpg", Mime: "image/jpeg", Taken: 1700000000000}, SourceID: "external:12", Version: "1", Folder: "DCIM/Camera"}
}
func uploadAll(t *testing.T, s *Store, b []byte) Upload {
	t.Helper()
	req := reqFor(b)
	u, e := s.Create(req, "phone")
	if e != nil {
		t.Fatal(e)
	}
	for off := 0; off < len(b); off += maxChunk {
		end := off + maxChunk
		if end > len(b) {
			end = len(b)
		}
		u, e = s.Chunk(u.ID, int64(off), b[off:end], digest(b[off:end]))
		if e != nil {
			t.Fatal(e)
		}
	}
	u, e = s.Finish(u.ID)
	if e != nil {
		t.Fatal(e)
	}
	s.wg.Wait()
	u, e = s.get(u.ID)
	if e != nil || u.State != "committed" {
		t.Fatalf("final: %+v %v", u, e)
	}
	return u
}
func TestChunkResumeAndLostAcknowledgment(t *testing.T) {
	s := testStore(t)
	b := bytes.Repeat([]byte{42}, 2*maxChunk+71)
	u, e := s.Create(reqFor(b), "phone")
	if e != nil {
		t.Fatal(e)
	}
	first := b[:maxChunk]
	u, e = s.Chunk(u.ID, 0, first, digest(first))
	if e != nil {
		t.Fatal(e)
	}
	again, e := s.Chunk(u.ID, 0, first, digest(first))
	if e != nil || again.Offset != maxChunk {
		t.Fatalf("duplicate: %+v %v", again, e)
	}
	bad := bytes.Repeat([]byte{41}, maxChunk)
	if _, e = s.Chunk(u.ID, 0, bad, digest(bad)); e == nil {
		t.Fatal("different bytes accepted at same offset")
	}
	// Simulate a process crash after a file write but before the SQLite offset commit.
	f, e := os.OpenFile(filepath.Join(s.root, "staging", u.ID+".part"), os.O_APPEND|os.O_WRONLY, 0600)
	if e != nil {
		t.Fatal(e)
	}
	f.Write([]byte("unacknowledged garbage"))
	f.Close()
	next, e := OpenStore(s.root, s.library)
	if e != nil {
		t.Fatal(e)
	}
	defer next.Close()
	next.reserve = 0
	for off := maxChunk; off < len(b); off += maxChunk {
		end := off + maxChunk
		if end > len(b) {
			end = len(b)
		}
		if _, e = next.Chunk(u.ID, int64(off), b[off:end], digest(b[off:end])); e != nil {
			t.Fatal(e)
		}
	}
	if _, e = next.Finish(u.ID); e != nil {
		t.Fatal(e)
	}
	next.wg.Wait()
	if _, e = next.Verify(u.ID); e != nil {
		t.Fatal(e)
	}
}
func TestCorruptionNeverCommits(t *testing.T) {
	s := testStore(t)
	original := []byte("original")
	u, e := s.Create(reqFor(original), "phone")
	if e != nil {
		t.Fatal(e)
	}
	wrong := []byte("modified")
	if _, e = s.Chunk(u.ID, 0, wrong, digest(wrong)); e != nil {
		t.Fatal(e)
	}
	s.Finish(u.ID)
	s.wg.Wait()
	u, _ = s.get(u.ID)
	if u.State != "corrupt" {
		t.Fatalf("%+v", u)
	}
	if _, e = s.Verify(u.ID); e == nil {
		t.Fatal("cleanup allowed for corrupt file")
	}
}
func TestCleanupRechecksStorage(t *testing.T) {
	s := testStore(t)
	u := uploadAll(t, s, []byte("original photo"))
	if _, e := s.Verify(u.ID); e != nil {
		t.Fatal(e)
	}
	os.WriteFile(filepath.Join(s.root, u.Path), []byte("changed photo!"), 0600)
	if _, e := s.Verify(u.ID); e == nil {
		t.Fatal("cleanup allowed after disk corruption")
	}
	os.Remove(filepath.Join(s.root, u.Path))
	if _, e := s.Verify(u.ID); e == nil {
		t.Fatal("cleanup allowed after removal")
	}
}
func TestReservationsAndDriveIdentity(t *testing.T) {
	s := testStore(t)
	s.free = func(string) (int64, error) { return 15, nil }
	if _, e := s.Create(reqFor([]byte("1234567890")), "phone"); e != nil {
		t.Fatal(e)
	}
	if _, e := s.Create(reqFor([]byte("abcdefghij")), "phone"); e == nil {
		t.Fatal("overcommitted drive")
	}
	os.Remove(filepath.Join(s.root, ".personal-backup-library"))
	if _, e := s.Create(reqFor([]byte("x")), "phone"); e == nil {
		t.Fatal("missing drive marker accepted")
	}
}
func TestDedupAndSourceVersions(t *testing.T) {
	s := testStore(t)
	b := []byte("identical bytes")
	u := uploadAll(t, s, b)
	r := reqFor(b)
	r.SourceID = "other"
	r.Name = "another.jpg"
	second, e := s.Create(r, "phone")
	if e != nil || second.ID != u.ID || second.State != "committed" {
		t.Fatalf("%+v %v", second, e)
	}
	r.Version = "2"
	if _, e = s.Create(r, "phone"); e != nil {
		t.Fatal(e)
	}
	var count int
	s.db.QueryRow("SELECT COUNT(*) FROM sources").Scan(&count)
	if count != 3 {
		t.Fatalf("source count %d", count)
	}
}
func TestRecoverFinalizationIntentAndRebuild(t *testing.T) {
	s := testStore(t)
	b := []byte("crash after manifest")
	u, e := s.Create(reqFor(b), "phone")
	if e != nil {
		t.Fatal(e)
	}
	u, e = s.Chunk(u.ID, 0, b, digest(b))
	if e != nil {
		t.Fatal(e)
	}
	u.State = "verifying"
	s.save(u)
	if e = s.Recover(); e != nil {
		t.Fatal(e)
	}
	s.wg.Wait()
	u, _ = s.get(u.ID)
	if u.State != "committed" {
		t.Fatalf("%+v", u)
	}
	// Simulate the catalog being lost but recovery metadata remaining.
	s.db.Exec("DELETE FROM uploads")
	if e = s.Rebuild(); e != nil {
		t.Fatal(e)
	}
	if _, e = s.Verify(u.ID); e != nil {
		t.Fatal(e)
	}
	// Intent persisted but rename interrupted: rebuild finishes only after hashing staging bytes.
	os.Rename(filepath.Join(s.root, u.Path), filepath.Join(s.root, "staging", u.ID+".part"))
	s.db.Exec("DELETE FROM uploads")
	if e = s.Rebuild(); e != nil {
		t.Fatal(e)
	}
	if _, e = s.Verify(u.ID); e != nil {
		t.Fatal(e)
	}
}
func TestLargeOffsetsAndUnsafeNames(t *testing.T) {
	s := testStore(t)
	r := reqFor([]byte("large-file-identity"))
	r.Size = (5 << 30) + 3
	r.Name = "../../bad.jpg"
	u, e := s.Create(r, "phone")
	if e != nil {
		t.Fatal(e)
	}
	f, e := os.OpenFile(filepath.Join(s.root, "staging", u.ID+".part"), os.O_RDWR, 0600)
	if e != nil {
		t.Fatal(e)
	}
	u.Offset = 5 << 30
	if e = f.Truncate(u.Offset); e != nil {
		t.Fatal(e)
	}
	f.Close()
	s.save(u)
	u, e = s.Chunk(u.ID, u.Offset, []byte("end"), digest([]byte("end")))
	if e != nil || u.Offset != r.Size {
		t.Fatalf("64-bit offset failed: %+v %v", u, e)
	}
	small := reqFor([]byte("safe"))
	small.Name = "../../bad.jpg"
	v, e := s.Create(small, "phone")
	if e != nil {
		t.Fatal(e)
	}
	s.Chunk(v.ID, 0, []byte("safe"), digest([]byte("safe")))
	s.Finish(v.ID)
	s.wg.Wait()
	v, _ = s.get(v.ID)
	if strings.Contains(v.Path, "..") || !strings.HasPrefix(v.Path, "originals/") {
		t.Fatalf("unsafe path %s", v.Path)
	}
}
func TestPairingAndHTTPProtocol(t *testing.T) {
	s := testStore(t)
	secret := strings.Repeat("a", 64)
	atomicJSON(filepath.Join(s.root, "pairing.json"), PairSecret{digest([]byte(secret)), time.Now().Add(time.Minute).Unix()})
	api := &API{store: s, verifySlots: make(chan struct{}, 2)}
	srv := httptest.NewTLSServer(api.Handler())
	defer srv.Close()
	client := srv.Client()
	do := func(method, path, token string, body []byte, headers map[string]string) (int, map[string]any) {
		t.Helper()
		r, _ := http.NewRequest(method, srv.URL+path, bytes.NewReader(body))
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		for k, v := range headers {
			r.Header.Set(k, v)
		}
		res, e := client.Do(r)
		if e != nil {
			t.Fatal(e)
		}
		defer res.Body.Close()
		var m map[string]any
		if e = json.NewDecoder(res.Body).Decode(&m); e != nil {
			t.Fatal(e)
		}
		return res.StatusCode, m
	}
	if code, _ := do("GET", "/v1/status", "", nil, nil); code != 401 {
		t.Fatal(code)
	}
	pair, _ := json.Marshal(map[string]string{"secret": secret, "name": "test phone"})
	code, m := do("POST", "/v1/pair", "", pair, nil)
	if code != 200 {
		t.Fatal(m)
	}
	token := m["token"].(string)
	if code, _ = do("POST", "/v1/pair", "", pair, nil); code != 403 {
		t.Fatal("pairing secret reused")
	}
	b := []byte("http original")
	body, _ := json.Marshal(reqFor(b))
	code, m = do("POST", "/v1/uploads", token, body, nil)
	if code != 200 {
		t.Fatal(m)
	}
	id := m["id"].(string)
	if code, m = do("PUT", "/v1/uploads/"+id+"/chunk", token, b, map[string]string{"Upload-Offset": "0", "Chunk-SHA256": digest(b)}); code != 200 {
		t.Fatal(m)
	}
	if code, m = do("POST", "/v1/uploads/"+id+"/finish", token, nil, nil); code != 202 {
		t.Fatal(m)
	}
	s.wg.Wait()
	if code, m = do("POST", "/v1/backups/"+id+"/verify", token, nil, nil); code != 200 || m["verified"] != true {
		t.Fatal(m)
	}
	r, _ := http.NewRequest("DELETE", srv.URL+"/v1/backups/"+id, nil)
	res, e := client.Do(r)
	if e != nil {
		t.Fatal(e)
	}
	io.Copy(io.Discard, res.Body)
	res.Body.Close()
	if res.StatusCode != 405 {
		t.Fatal("unexpected delete endpoint")
	}
}
func TestCatalog100000Items(t *testing.T) {
	s := testStore(t)
	tx, e := s.db.Begin()
	if e != nil {
		t.Fatal(e)
	}
	stmt, e := tx.Prepare("INSERT INTO sources VALUES(?,?)")
	if e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 100000; i++ {
		key := digest([]byte(time.Unix(int64(i), 0).String()))
		if _, e = stmt.Exec(key, `{"name":"photo.jpg"}`); e != nil {
			t.Fatal(e)
		}
	}
	stmt.Close()
	if e = tx.Commit(); e != nil {
		t.Fatal(e)
	}
	var n int
	s.db.QueryRow("SELECT COUNT(*) FROM sources").Scan(&n)
	if n != 100000 {
		t.Fatal(n)
	}
}
