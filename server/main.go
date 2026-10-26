package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/grandcat/zeroconf"
	"github.com/skip2/go-qrcode"
)

type Config struct {
	Library    string `json:"library_id"`
	VolumeUUID string `json:"volume_uuid"`
	Port       int    `json:"port"`
}

func diskInfo(path string) (map[string]any, error) {
	cmd := exec.Command("diskutil", "info", "-plist", path)
	b, e := cmd.Output()
	if e != nil {
		// Current macOS releases accept mount points and device nodes here, but
		// may reject an ordinary directory. Resolve its backing device first.
		df, dfErr := exec.Command("df", "-P", path).Output()
		if dfErr != nil {
			return nil, fmt.Errorf("resolve destination volume: %w", e)
		}
		lines := strings.Split(strings.TrimSpace(string(df)), "\n")
		fields := strings.Fields(lines[len(lines)-1])
		if len(fields) == 0 {
			return nil, fmt.Errorf("resolve destination volume: unexpected df output")
		}
		b, e = exec.Command("diskutil", "info", "-plist", fields[0]).Output()
		if e != nil {
			return nil, fmt.Errorf("inspect destination volume: %w", e)
		}
	}
	conv := exec.Command("plutil", "-convert", "json", "-o", "-", "--", "-")
	conv.Stdin = strings.NewReader(string(b))
	b, e = conv.Output()
	if e != nil {
		return nil, e
	}
	var v map[string]any
	e = json.Unmarshal(b, &v)
	return v, e
}
func loadConfig(root string) (Config, error) {
	var c Config
	b, e := os.ReadFile(filepath.Join(root, "config.json"))
	if e != nil {
		return c, e
	}
	e = json.Unmarshal(b, &c)
	if e != nil {
		return c, e
	}
	if len(c.Library) != 32 || c.Port < 1 || c.Port > 65535 || c.VolumeUUID == "" {
		return c, fmt.Errorf("invalid library configuration")
	}
	v, e := diskInfo(root)
	if e != nil {
		return c, e
	}
	if v["VolumeUUID"] != c.VolumeUUID {
		return c, fmt.Errorf("destination volume identity changed; refusing to write")
	}
	return c, nil
}
func writeDurable(path string, b []byte) error {
	f, e := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	_, e = f.Write(b)
	if e == nil {
		e = f.Sync()
	}
	closeErr := f.Close()
	if e == nil {
		e = closeErr
	}
	return e
}

func replaceDurable(path string, b []byte) error {
	f, e := os.CreateTemp(filepath.Dir(path), ".certificate-")
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

func createCertificate(root string, c Config, replace bool) error {
	priv, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		return e
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	tmpl := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "Personal Media Backup " + c.Library}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().AddDate(10, 0, 0), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, DNSNames: []string{"localhost"}}
	der, e := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &priv.PublicKey, priv)
	if e != nil {
		return e
	}
	key, e := x509.MarshalPKCS8PrivateKey(priv)
	if e != nil {
		return e
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key})
	if replace {
		if e = replaceDurable(filepath.Join(root, "server.key"), keyPEM); e != nil {
			return e
		}
		return replaceDurable(filepath.Join(root, "server.crt"), certPEM)
	}
	if e = writeDurable(filepath.Join(root, "server.crt"), certPEM); e != nil {
		return e
	}
	return writeDurable(filepath.Join(root, "server.key"), keyPEM)
}
func initialize(root string, port int, internal bool) error {
	if port < 1 || port > 65535 {
		return fmt.Errorf("port must be between 1 and 65535")
	}
	entries, e := os.ReadDir(root)
	if e != nil && !os.IsNotExist(e) {
		return e
	}
	if len(entries) > 0 {
		return fmt.Errorf("choose a new or empty library folder; initialization never overwrites existing files")
	}

	parent := root
	for {
		if _, e := os.Stat(parent); e == nil {
			break
		}
		next := filepath.Dir(parent)
		if next == parent {
			return fmt.Errorf("no existing parent")
		}
		parent = next
	}
	info, e := diskInfo(parent)
	if e != nil {
		return e
	}
	fs := strings.ToLower(fmt.Sprint(info["FilesystemType"]))
	if fs != "apfs" && fs != "exfat" && fs != "hfs" {
		return fmt.Errorf("use APFS, HFS+, or exFAT; filesystem %q is not supported", fs)
	}
	if info["Internal"] == true && !internal {
		return fmt.Errorf("choose an external drive; --allow-internal is available for development")
	}
	uuid, ok := info["VolumeUUID"].(string)
	if !ok || uuid == "" {
		return fmt.Errorf("cannot determine volume UUID")
	}
	if e = os.MkdirAll(root, 0700); e != nil {
		return e
	}
	if e = os.Chmod(root, 0700); e != nil {
		return e
	}
	for _, d := range []string{"staging", "originals", "manifests", "sources", "quarantine"} {
		if e = os.MkdirAll(filepath.Join(root, d), 0700); e != nil {
			return e
		}
	}
	c := Config{randomHex(16), uuid, port}
	if e = writeDurable(filepath.Join(root, ".personal-backup-library"), []byte(c.Library+"\n")); e != nil {
		return e
	}
	if e = createCertificate(root, c, false); e != nil {
		return e
	}
	if e = atomicJSON(filepath.Join(root, "config.json"), c); e != nil {
		return e
	}
	if e = syncDir(filepath.Dir(root)); e != nil {
		return e
	}
	s, e := OpenStore(root, c.Library)
	if e == nil {
		s.Close()
	}
	return e
}
func pairing(root, host string, c Config) error {
	if host == "" {
		out, e := exec.Command("scutil", "--get", "LocalHostName").Output()
		if e != nil {
			return fmt.Errorf("provide --host with your Mac Wi-Fi IP address")
		}
		host = strings.TrimSpace(string(out)) + ".local"
	}
	if net.ParseIP(host) == nil && strings.ContainsAny(host, "/: \n") {
		return fmt.Errorf("host must be a hostname or IP address")
	}
	b, e := os.ReadFile(filepath.Join(root, "server.crt"))
	if e != nil {
		return e
	}
	block, _ := pem.Decode(b)
	if block == nil {
		return fmt.Errorf("invalid certificate")
	}
	secret := randomHex(32)
	if e = atomicJSON(filepath.Join(root, "pairing.json"), PairSecret{digest([]byte(secret)), time.Now().Add(10 * time.Minute).Unix()}); e != nil {
		return e
	}
	payload := map[string]any{"protocol": 1, "url": "https://" + net.JoinHostPort(host, strconv.Itoa(c.Port)), "fingerprint": digest(block.Bytes), "library_id": c.Library, "secret": secret}
	encoded, _ := json.Marshal(payload)
	qr, e := qrcode.New(string(encoded), qrcode.Medium)
	if e != nil {
		return e
	}
	fmt.Println("Scan in the Android app. This code expires in 10 minutes and can be used once.")
	fmt.Println(qr.ToSmallString(false))
	fmt.Println("Or paste this pairing text into the app:")
	fmt.Println(string(encoded))
	return nil
}
func lockLibrary(root string) (*os.File, error) {
	f, e := os.OpenFile(filepath.Join(root, ".server.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		return nil, e
	}
	if e = syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); e != nil {
		f.Close()
		return nil, fmt.Errorf("server is running; stop it before maintenance")
	}
	return f, nil
}
func start(root string, c Config) error {
	lock, e := lockLibrary(root)
	if e != nil {
		return e
	}
	defer lock.Close()
	s, e := OpenStore(root, c.Library)
	if e != nil {
		return e
	}
	defer s.Close()
	if e = s.Recover(); e != nil {
		return e
	}
	api := &API{store: s, verifySlots: make(chan struct{}, 2)}
	// TLS sessions are pinned by the phone. No certificate-authority or internet dependency.
	srv := &http.Server{Addr: fmt.Sprintf(":%d", c.Port), Handler: api.Handler(), ReadHeaderTimeout: 15 * time.Second, ReadTimeout: 5 * time.Minute, IdleTimeout: 90 * time.Second, MaxHeaderBytes: 16 << 10, TLSConfig: &tls.Config{MinVersion: tls.VersionTLS13}}
	mdns, e := zeroconf.Register("Personal Backup "+c.Library[:8], "_pbackup._tcp", "local.", c.Port, []string{"library=" + c.Library, "protocol=1"}, nil)
	if e != nil {
		log.Printf("Discovery unavailable; use the Mac IP address: %v", e)
	} else {
		defer mdns.Shutdown()
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	backgroundDone := make(chan struct{})
	go func() {
		defer close(backgroundDone)
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.RetryVerifying()
			}
		}
	}()
	defer func() { stop(); <-backgroundDone; _ = srv.Close(); api.requests.Wait() }()
	go func() {
		<-ctx.Done()
		shut, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		if srv.Shutdown(shut) != nil {
			_ = srv.Close()
		}
	}()
	log.Printf("Listening on HTTPS port %d; library %s", c.Port, c.Library)
	e = srv.ListenAndServeTLS(filepath.Join(root, "server.crt"), filepath.Join(root, "server.key"))
	if e == http.ErrServerClosed {
		return nil
	}
	return e
}
func xmlEscape(s string) string {
	r := strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", "\"", "&quot;")
	return r.Replace(s)
}
func loginService(root string, install bool) error {
	home, e := os.UserHomeDir()
	if e != nil {
		return e
	}
	dir := filepath.Join(home, "Library", "LaunchAgents")
	path := filepath.Join(dir, "dev.personal.backup.plist")
	domain := fmt.Sprintf("gui/%d", os.Getuid())
	if !install {
		_ = exec.Command("launchctl", "bootout", domain, path).Run()
		return os.Remove(path)
	}
	exe, e := os.Executable()
	if e != nil {
		return e
	}
	if e = os.MkdirAll(dir, 0700); e != nil {
		return e
	}
	contents := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>Label</key><string>dev.personal.backup</string><key>ProgramArguments</key><array><string>%s</string><string>start</string><string>--root</string><string>%s</string></array><key>RunAtLoad</key><true/><key>KeepAlive</key><true/><key>ThrottleInterval</key><integer>60</integer><key>ProcessType</key><string>Background</string></dict></plist>`, xmlEscape(exe), xmlEscape(root))
	if e = os.WriteFile(path, []byte(contents), 0600); e != nil {
		return e
	}
	return exec.Command("launchctl", "bootstrap", domain, path).Run()
}
func run() error {
	if len(os.Args) < 2 {
		return fmt.Errorf("usage: personal-backup {init|pair|start|status|check|rebuild|repair|refresh-certificate|install-service|uninstall-service} --root /Volumes/Drive/PersonalBackup")
	}
	cmd := os.Args[1]
	flags := flag.NewFlagSet(cmd, flag.ContinueOnError)
	root := flags.String("root", "", "Library folder on the external drive")
	host := flags.String("host", "", "Mac Wi-Fi IP or .local hostname for pairing")
	port := flags.Int("port", 8787, "HTTPS port")
	internal := flags.Bool("allow-internal", false, "Allow internal storage for development")
	sha := flags.String("sha", "", "Object SHA-256 for repair")
	if e := flags.Parse(os.Args[2:]); e != nil {
		return e
	}
	if *root == "" {
		return fmt.Errorf("--root is required")
	}
	abs, e := filepath.Abs(*root)
	if e != nil {
		return e
	}
	*root = abs
	if cmd == "init" {
		if e = initialize(*root, *port, *internal); e != nil {
			return e
		}
		fmt.Println("Library initialized. Run pair, then start with the same --root.")
		return nil
	}
	c, e := loadConfig(*root)
	if e != nil {
		return e
	}
	switch cmd {
	case "pair":
		return pairing(*root, *host, c)
	case "start":
		return start(*root, c)
	case "install-service":
		return loginService(*root, true)
	case "uninstall-service":
		return loginService(*root, false)
	case "status":
		free, e := diskFree(*root)
		if e != nil {
			return e
		}
		fmt.Printf("Library: %s\nStorage: %s\nFree: %.2f GiB (5 GiB reserved)\nPort: %d\n", c.Library, *root, float64(free)/(1<<30), c.Port)
		dialer := &net.Dialer{Timeout: time.Second}
		// This is only a local liveness probe. Library identity and certificate
		// pinning are enforced by paired clients on their authenticated requests.
		conn, e := tls.DialWithDialer(dialer, "tcp", fmt.Sprintf("127.0.0.1:%d", c.Port), &tls.Config{
			MinVersion:         tls.VersionTLS13,
			InsecureSkipVerify: true, // localhost certificate is self-signed
		})
		if e != nil {
			fmt.Println("Server: not listening")
		} else {
			conn.Close()
			fmt.Println("Server: port listening")
		}
		return nil
	case "refresh-certificate":
		lock, e := lockLibrary(*root)
		if e != nil {
			return e
		}
		defer lock.Close()
		if e = createCertificate(*root, c, true); e != nil {
			return e
		}
		_ = os.Remove(filepath.Join(*root, "pairing.json"))
		fmt.Println("Certificate refreshed. Generate a new pairing code and pair the phone again.")
		return nil
	case "check", "rebuild", "repair":
		lock, e := lockLibrary(*root)
		if e != nil {
			return e
		}
		defer lock.Close()
		s, e := OpenStore(*root, c.Library)
		if e != nil {
			return e
		}
		defer s.Close()
		if cmd == "rebuild" {
			if e = s.Rebuild(); e != nil {
				return e
			}
			entries, e := os.ReadDir(filepath.Join(*root, "sources"))
			if e != nil {
				return e
			}
			for _, f := range entries {
				if !strings.HasSuffix(f.Name(), ".json") {
					continue
				}
				b, e := os.ReadFile(filepath.Join(*root, "sources", f.Name()))
				if e != nil {
					return e
				}
				var src Source
				if e = json.Unmarshal(b, &src); e != nil {
					return e
				}
				_, e = s.db.Exec("INSERT OR REPLACE INTO sources VALUES(?,?)", strings.TrimSuffix(f.Name(), ".json"), string(b))
				if e != nil {
					return e
				}
			}
			fmt.Println("Catalog rebuilt from verified originals and source manifests.")
			return nil
		}
		if cmd == "repair" {
			if !hashPattern.MatchString(*sha) {
				return fmt.Errorf("--sha must be a SHA-256 hash")
			}
			u, e := s.get(*sha)
			if e != nil {
				return e
			}
			if _, e = s.Verify(*sha); e == nil {
				return fmt.Errorf("object is valid; no repair needed")
			} else {
				var f *Fault
				if !errors.As(e, &f) || (f.Code != "integrity_error" && f.Code != "not_committed") {
					return e
				}
			}
			if e = s.guard(); e != nil {
				return e
			}
			for _, p := range []string{u.Path, filepath.Join("staging", u.ID+".part"), filepath.Join("manifests", u.ID+".json")} {
				if p == "" {
					continue
				}
				from, e := s.safePath(p)
				if e != nil {
					return e
				}
				if _, e = os.Stat(from); e == nil {
					to := filepath.Join(*root, "quarantine", randomHex(8)+"--"+filepath.Base(p))
					if e = s.rootFS.Rename(p, filepath.Join("quarantine", filepath.Base(to))); e != nil {
						return e
					}
					if e = syncDir(filepath.Dir(to)); e != nil {
						return e
					}
					if e = syncDir(filepath.Dir(from)); e != nil {
						return e
					}
				}
			}
			_, e = s.db.Exec("DELETE FROM uploads WHERE id=?", u.ID)
			fmt.Println("Damaged content preserved in quarantine. Retry the source on the phone.")
			return e
		}
		total := 0
		after := ""
		for {
			rows, e := s.db.Query("SELECT id FROM uploads WHERE json_extract(data,'$.state')='committed' AND id>? ORDER BY id LIMIT 128", after)
			if e != nil {
				return e
			}
			var ids []string
			for rows.Next() {
				var id string
				if e = rows.Scan(&id); e != nil {
					rows.Close()
					return e
				}
				ids = append(ids, id)
				after = id
			}
			e = rows.Err()
			rows.Close()
			if e != nil {
				return e
			}
			if len(ids) == 0 {
				break
			}
			for _, id := range ids {
				if _, e = s.Verify(id); e != nil {
					return fmt.Errorf("%s: %w", id, e)
				}
				total++
			}
		}
		fmt.Printf("Verified %d originals.\n", total)
		return nil
	default:
		return fmt.Errorf("unknown command %s", cmd)
	}
}
func main() {
	if e := run(); e != nil {
		log.Fatal(e)
	}
}
