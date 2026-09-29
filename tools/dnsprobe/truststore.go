package main

import (
	"crypto/x509"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// Default Android CA certificate directories, checked in order.
var defaultAndroidCADirs = []string{
	"/system/etc/security/cacerts",
	"/apex/com.android.conscrypt/cacerts",
}

// TrustStore holds the loaded certificate pool and metadata.
type TrustStore struct {
	Pool      *x509.CertPool
	CertCount int
	Sources   []string // directories that contributed certs
	Empty     bool
	// PlatformVerifier is set when the host verifies certificates itself
	// (Windows, macOS). There, SystemCertPool() hands back a pool with no
	// Subjects, which is NOT the same as having no trust — a nil RootCAs
	// still verifies correctly. Reporting that as EMPTY would be a lie.
	PlatformVerifier bool
}

// LoadTrustStore builds an x509.CertPool.
//
// If caDirs is non-empty, only those directories are scanned (no system pool).
// In auto mode (caDirs empty), it starts from SystemCertPool and then
// appends certs from the default Android CA directories if they exist.
func LoadTrustStore(caDirs []string) TrustStore {
	ts := TrustStore{}

	if len(caDirs) > 0 {
		// Explicit directories only — no system pool.
		ts.Pool = x509.NewCertPool()
		for _, dir := range caDirs {
			n := appendCertsFromDir(ts.Pool, dir)
			if n > 0 {
				ts.CertCount += n
				ts.Sources = append(ts.Sources, dir)
			}
		}
	} else {
		// Auto: start from system pool, then try Android dirs.
		pool, err := x509.SystemCertPool()
		if err != nil || pool == nil {
			pool = x509.NewCertPool()
		}
		ts.Pool = pool

		// Count system certs (pool.Subjects is deprecated but the only
		// stdlib way to get the count without importing certs twice).
		//nolint:staticcheck // Subjects() is deprecated but has no replacement for counting.
		systemCount := len(pool.Subjects())
		ts.CertCount = systemCount
		if systemCount > 0 {
			ts.Sources = append(ts.Sources, "system")
		}

		for _, dir := range defaultAndroidCADirs {
			n := appendCertsFromDir(ts.Pool, dir)
			if n > 0 {
				ts.CertCount += n
				ts.Sources = append(ts.Sources, dir)
			}
		}
	}

	if ts.CertCount == 0 && len(caDirs) == 0 &&
		(runtime.GOOS == "windows" || runtime.GOOS == "darwin" || runtime.GOOS == "ios") {
		ts.PlatformVerifier = true
	}
	ts.Empty = ts.CertCount == 0 && !ts.PlatformVerifier
	return ts
}

// appendCertsFromDir reads all regular files in dir, treating each as a PEM
// certificate. Returns the number of certificates successfully added.
func appendCertsFromDir(pool *x509.CertPool, dir string) int {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0 // directory doesn't exist or unreadable — skip silently
	}
	count := 0
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		pem, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			continue
		}
		if pool.AppendCertsFromPEM(pem) {
			count++
		}
	}
	return count
}

// PrintTrustStoreStatus prints a one-line summary to stderr.
func PrintTrustStoreStatus(ts TrustStore) {
	if ts.PlatformVerifier {
		fmt.Fprintf(os.Stderr, "trust store: host platform verifier (%s)\n", runtime.GOOS)
		return
	}
	if ts.Empty {
		fmt.Fprintf(os.Stderr, "trust store: EMPTY — every TLS result below is unverifiable\n")
	} else {
		fmt.Fprintf(os.Stderr, "trust store: %d certs from %s\n",
			ts.CertCount, strings.Join(ts.Sources, ", "))
	}
}
