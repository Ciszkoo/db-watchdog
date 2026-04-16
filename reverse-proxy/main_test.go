package main

import (
	"errors"
	"os"
	"strings"
	"testing"

	"github.com/Ciszkoo/db-watchdog/reverse-proxy/systemdb"
)

func TestLoadTechnicalCredentialsKeyReturnsEnvironmentValue(t *testing.T) {
	t.Setenv("TECHNICAL_CREDENTIALS_KEY", "proxy-test-key")

	value, err := loadTechnicalCredentialsKey()
	if err != nil {
		t.Fatalf("loadTechnicalCredentialsKey: %v", err)
	}

	if value != "proxy-test-key" {
		t.Fatalf("unexpected key value: %q", value)
	}
}

func TestLoadTechnicalCredentialsKeyFailsWhenMissing(t *testing.T) {
	if previousValue, existed := os.LookupEnv("TECHNICAL_CREDENTIALS_KEY"); existed {
		t.Cleanup(func() {
			_ = os.Setenv("TECHNICAL_CREDENTIALS_KEY", previousValue)
		})
	}
	_ = os.Unsetenv("TECHNICAL_CREDENTIALS_KEY")

	_, err := loadTechnicalCredentialsKey()
	if !errors.Is(err, systemdb.ErrMissingTechnicalCredentialsKey) {
		t.Fatalf("expected ErrMissingTechnicalCredentialsKey, got %v", err)
	}
}

func TestLoadPreviousTechnicalCredentialsKeyReturnsEnvironmentValue(t *testing.T) {
	t.Setenv("TECHNICAL_CREDENTIALS_PREVIOUS_KEY", "proxy-previous-key")

	value := loadPreviousTechnicalCredentialsKey()
	if value != "proxy-previous-key" {
		t.Fatalf("unexpected previous key value: %q", value)
	}
}

func TestLoadPreviousTechnicalCredentialsKeyNormalizesBlankValues(t *testing.T) {
	t.Setenv("TECHNICAL_CREDENTIALS_PREVIOUS_KEY", "   ")

	value := loadPreviousTechnicalCredentialsKey()
	if value != "" {
		t.Fatalf("expected blank previous key to normalize to empty string, got %q", value)
	}
}

func TestStartupWarningsIncludePlaintextTargetDatabaseWarning(t *testing.T) {
	warnings := startupWarnings("postgres://custom.example.test/db_watchdog?sslmode=require")

	if !containsWarning(warnings, "reverse-proxy -> target database currently uses plaintext TCP") {
		t.Fatalf("expected plaintext target database warning, got %v", warnings)
	}
}

func TestStartupWarningsIncludeDefaultSystemDatabaseSSLModeWarning(t *testing.T) {
	warnings := startupWarnings(defaultSystemDBDSN)

	if !containsWarning(warnings, "default SYSTEM_DB_DSN uses sslmode=disable") {
		t.Fatalf("expected default SYSTEM_DB_DSN warning, got %v", warnings)
	}
}

func TestStartupWarningsDoNotDependOnLoggingSideEffects(t *testing.T) {
	first := startupWarnings(defaultSystemDBDSN)
	second := startupWarnings(defaultSystemDBDSN)

	if len(first) != len(second) {
		t.Fatalf("expected stable warning count, got %d and %d", len(first), len(second))
	}

	for index := range first {
		if first[index] != second[index] {
			t.Fatalf("expected stable warnings, got %q and %q", first[index], second[index])
		}
	}
}

func containsWarning(warnings []string, fragment string) bool {
	for _, warning := range warnings {
		if strings.Contains(warning, fragment) {
			return true
		}
	}

	return false
}
