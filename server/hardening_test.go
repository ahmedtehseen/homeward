package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func requireFault(t *testing.T, err error, code string) {
	t.Helper()
	var f *Fault
	if !errors.As(err, &f) || f.Code != code {
		t.Fatalf("want %s, got %v", code, err)
	}
}

func TestExpiredReservationRetainsPartialAndReacquiresCapacity(t *testing.T) {
	s := testStore(t)
	free := int64(15)
	s.free = func(string) (int64, error) { return free, nil }
	b := []byte("1234567890")
	u, e := s.Create(reqFor(b), "phone")
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Chunk(u.ID, 0, b[:5], digest(b[:5])); e != nil {
		t.Fatal(e)
	}
	free = 10
	if _, e = s.db.Exec("UPDATE uploads SET data=json_set(data,'$.updated',?) WHERE id=?", time.Now().Add(-8*24*time.Hour).Unix(), u.ID); e != nil {
		t.Fatal(e)
	}
	if _, e = s.Create(reqFor([]byte("abcdefghij")), "phone"); e != nil {
		t.Fatal(e)
	}
	_, e = s.Chunk(u.ID, 5, b[5:], digest(b[5:]))
	requireFault(t, e, "storage_full")
	actual, e := os.ReadFile(filepath.Join(s.root, "staging", u.ID+".part"))
	if e != nil || !bytes.Equal(actual, b[:5]) {
		t.Fatal("partial bytes were lost")
	}
	free = 20
	if _, e = s.Chunk(u.ID, 5, b[5:], digest(b[5:])); e != nil {
		t.Fatal(e)
	}
}

func TestFinalizationConcurrencyAndTransientFailure(t *testing.T) {
	s := testStore(t)
	b := []byte("finalization retry")
	u, e := s.Create(reqFor(b), "phone")
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Chunk(u.ID, 0, b, digest(b)); e != nil {
		t.Fatal(e)
	}
	s.verifySlots <- struct{}{}
	s.verifySlots <- struct{}{}
	_, e = s.Finish(u.ID)
	requireFault(t, e, "verification_busy")
	<-s.verifySlots
	<-s.verifySlots
	// A transient filesystem error must not permanently label valid media corrupt.
	manifests := filepath.Join(s.root, "manifests")
	if e = os.Rename(manifests, manifests+".saved"); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(manifests, []byte("temporary obstruction"), 0600); e != nil {
		t.Fatal(e)
	}
	if _, e = s.Finish(u.ID); e != nil {
		t.Fatal(e)
	}
	s.wg.Wait()
	u, e = s.get(u.ID)
	if e != nil || u.State != "uploading" {
		t.Fatalf("transient failure: %+v %v", u, e)
	}
	os.Remove(manifests)
	if e = os.Rename(manifests+".saved", manifests); e != nil {
		t.Fatal(e)
	}
	if _, e = s.Finish(u.ID); e != nil {
		t.Fatal(e)
	}
	s.wg.Wait()
	if _, e = s.Verify(u.ID); e != nil {
		t.Fatal(e)
	}
}

func TestPinnedRootPreventsFallbackToReplacementDirectory(t *testing.T) {
	s := testStore(t)
	u := uploadAll(t, s, []byte("keep on original drive"))
	root := s.root
	moved := root + "-disconnected"
	if e := os.Rename(root, moved); e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { os.RemoveAll(root); os.Rename(moved, root) })
	// Simulate an internal mount-point replacement bearing a copied identity marker.
	if e := os.Mkdir(root, 0700); e != nil {
		t.Fatal(e)
	}
	os.WriteFile(filepath.Join(root, ".personal-backup-library"), []byte(s.library), 0600)
	_, e := s.Verify(u.ID)
	requireFault(t, e, "drive_unavailable")
	_, e = s.Create(reqFor([]byte("must not write here")), "phone")
	requireFault(t, e, "drive_unavailable")
	// Even if the mount disappears after a successful guard, writes stay pinned.
	if e = s.mkdir("originals/pinned-test"); e != nil {
		t.Fatal(e)
	}
	if _, e = os.Stat(filepath.Join(root, "originals")); !os.IsNotExist(e) {
		t.Fatal("fallback directory was created")
	}
	if _, e = os.Stat(filepath.Join(moved, "originals/pinned-test")); e != nil {
		t.Fatal(e)
	}
}

func TestRejectSymlinkAndUnsafeRecoveryManifest(t *testing.T) {
	s := testStore(t)
	u := uploadAll(t, s, []byte("original"))
	path := filepath.Join(s.root, u.Path)
	other := filepath.Join(t.TempDir(), "outside.jpg")
	if e := os.Rename(path, other); e != nil {
		t.Fatal(e)
	}
	if e := os.Symlink(other, path); e != nil {
		t.Fatal(e)
	}
	_, e := s.Verify(u.ID)
	requireFault(t, e, "integrity_error")
	if e = s.Rebuild(); e == nil {
		t.Fatal("rebuild followed an external symlink")
	}
	os.Remove(path)
	os.Rename(other, path)
	u.Path = "originals/../../outside.jpg"
	if e = atomicJSON(filepath.Join(s.root, "manifests", u.ID+".json"), u); e != nil {
		t.Fatal(e)
	}
	if e = s.Rebuild(); e == nil {
		t.Fatal("rebuild accepted path traversal")
	}
}

func TestDecodeRejectsOversizeTrailingWhitespace(t *testing.T) {
	r := httptest.NewRequest("POST", "/v1/pair", strings.NewReader(`{"secret":"x","name":"test"}`+strings.Repeat(" ", 64<<10)))
	var req struct {
		Secret string `json:"secret"`
		Name   string `json:"name"`
	}
	requireFault(t, decode(r, &req), "invalid_request")
}

func TestSameSourceVersionStillRetainsDifferentContent(t *testing.T) {
	s := testStore(t)
	if _, e := s.Create(reqFor([]byte("first")), "phone"); e != nil {
		t.Fatal(e)
	}
	if _, e := s.Create(reqFor([]byte("other")), "phone"); e != nil {
		t.Fatal(e)
	}
	var count int
	if e := s.db.QueryRow("SELECT COUNT(*) FROM sources").Scan(&count); e != nil || count != 2 {
		t.Fatalf("sources %d: %v", count, e)
	}
}

func TestRecoveryReplaysRenameBeforeCatalogCommit(t *testing.T) {
	s := testStore(t)
	b := []byte("renamed before sqlite commit")
	u := uploadAll(t, s, b)
	u.State = "verifying"
	u.Path = ""
	if e := s.save(u); e != nil {
		t.Fatal(e)
	}
	if e := s.Recover(); e != nil {
		t.Fatal(e)
	}
	if _, e := s.Verify(u.ID); e != nil {
		t.Fatal(e)
	}
}

func TestCatalogPathWithQueryCharacters(t *testing.T) {
	root := filepath.Join(t.TempDir(), "Library ?#% contents")
	if e := os.Mkdir(root, 0700); e != nil {
		t.Fatal(e)
	}
	for _, d := range []string{"staging", "originals", "manifests", "sources", "quarantine"} {
		os.Mkdir(filepath.Join(root, d), 0700)
	}
	os.WriteFile(filepath.Join(root, ".personal-backup-library"), []byte("special-library"), 0600)
	s, e := OpenStore(root, "special-library")
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	var id string
	if e = s.db.QueryRow("SELECT value FROM meta WHERE key='library'").Scan(&id); e != nil || id != "special-library" {
		t.Fatalf("catalog mismatch: %s %v", id, e)
	}
	if _, e = os.Stat(filepath.Join(root, "catalog.sqlite")); e != nil {
		t.Fatal(e)
	}
}

func TestLongExtensionAndDotNamesRemainRecoverable(t *testing.T) {
	s := testStore(t)
	for _, name := range []string{"photo..jpg", "name." + strings.Repeat("x", 300)} {
		b := []byte(name)
		req := reqFor(b)
		req.Name = name
		u, e := s.Create(req, "phone")
		if e != nil {
			t.Fatal(e)
		}
		if _, e = s.Chunk(u.ID, 0, b, digest(b)); e != nil {
			t.Fatal(e)
		}
		if _, e = s.Finish(u.ID); e != nil {
			t.Fatal(e)
		}
		s.wg.Wait()
		u, e = s.get(u.ID)
		if e != nil || u.State != "committed" {
			out, _ := json.Marshal(u)
			t.Fatalf("%s %v", out, e)
		}
	}
	if e := s.Rebuild(); e != nil {
		t.Fatal(e)
	}
}

func TestUnacknowledgedTailReclaimedBeforeCapacityCheck(t *testing.T) {
	s := testStore(t)
	b := []byte("1234567890")
	req := reqFor(b)
	s.free = func(string) (int64, error) {
		st, e := os.Stat(filepath.Join(s.root, "staging", req.SHA256+".part"))
		if os.IsNotExist(e) {
			return 10, nil
		}
		if e != nil {
			return 0, e
		}
		return 10 - st.Size(), nil
	}
	u, e := s.Create(req, "phone")
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Chunk(u.ID, 0, b[:5], digest(b[:5])); e != nil {
		t.Fatal(e)
	}
	f, e := os.OpenFile(filepath.Join(s.root, "staging", u.ID+".part"), os.O_APPEND|os.O_WRONLY, 0600)
	if e != nil {
		t.Fatal(e)
	}
	f.Write([]byte("bad"))
	f.Close()
	// Without truncating the unacknowledged tail first, reservations exceed free space.
	if _, e = s.Chunk(u.ID, 5, b[5:], digest(b[5:])); e != nil {
		t.Fatal(e)
	}
	if _, e = s.Finish(u.ID); e != nil {
		t.Fatal(e)
	}
	s.wg.Wait()
	if _, e = s.Verify(u.ID); e != nil {
		t.Fatal(e)
	}
}

func TestInitializeAndLoadConfigOnMac(t *testing.T) {
	root := filepath.Join(t.TempDir(), "PersonalBackup")
	if e := initialize(root, 8787, true); e != nil {
		t.Fatal(e)
	}
	c, e := loadConfig(root)
	if e != nil {
		t.Fatal(e)
	}
	if len(c.Library) != 32 || c.VolumeUUID == "" || c.Port != 8787 {
		t.Fatalf("invalid config: %+v", c)
	}
	if e = initialize(root, 8787, true); e == nil {
		t.Fatal("reinitialization accepted")
	}
	c2, e := loadConfig(root)
	if e != nil || c2.Library != c.Library {
		t.Fatal("existing identity was changed")
	}
}
