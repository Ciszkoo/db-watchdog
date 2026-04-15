package main

import (
	"errors"
	"os"
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
