package systemdb

import (
	"context"
	"errors"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/pashagolub/pgxmock/v4"
)

func TestConsumeOTPConsumesMatchingCredential(t *testing.T) {
	t.Parallel()

	mock, err := pgxmock.NewPool()
	if err != nil {
		t.Fatalf("new pool: %v", err)
	}
	defer mock.Close()

	store, err := NewStoreWithDB(mock, "test-technical-credentials-key")
	if err != nil {
		t.Fatalf("NewStoreWithDB: %v", err)
	}
	expected := ConsumedCredential{
		CredentialID:      uuid.MustParse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
		UserID:            uuid.MustParse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
		DatabaseID:        uuid.MustParse("cccccccc-cccc-cccc-cccc-cccccccccccc"),
		Host:              "db.internal",
		Port:              5432,
		DatabaseName:      "external_db",
		TechnicalUser:     "proxy_user_1",
		TechnicalPassword: "proxy_pass",
	}

	mock.ExpectQuery(regexp.QuoteMeta(consumeOTPQuery)).
		WithArgs(
			sha256Hex("PlaintextOtp"),
			"jane@example.com",
			"external_db",
			"test-technical-credentials-key",
		).
		WillReturnRows(
			pgxmock.NewRows(
				[]string{
					"id",
					"user_id",
					"database_id",
					"host",
					"port",
					"database_name",
					"technical_user",
					"technical_password",
				},
			).AddRow(
				expected.CredentialID,
				expected.UserID,
				expected.DatabaseID,
				expected.Host,
				expected.Port,
				expected.DatabaseName,
				expected.TechnicalUser,
				expected.TechnicalPassword,
			),
		)

	actual, err := store.ConsumeOTP(
		context.Background(),
		"jane@example.com",
		"external_db",
		"PlaintextOtp",
	)
	if err != nil {
		t.Fatalf("ConsumeOTP: %v", err)
	}

	if actual != expected {
		t.Fatalf("unexpected credential: %#v", actual)
	}

	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatalf("unmet expectations: %v", err)
	}
}

func TestConsumeOTPRejectsInvalidCredentials(t *testing.T) {
	t.Parallel()

	testCases := []struct {
		name            string
		loginIdentifier string
		databaseName    string
		otp             string
	}{
		{
			name:            "wrong otp",
			loginIdentifier: "jane@example.com",
			databaseName:    "external_db",
			otp:             "wrong-otp",
		},
		{
			name:            "expired otp",
			loginIdentifier: "jane@example.com",
			databaseName:    "external_db",
			otp:             "expired-otp",
		},
		{
			name:            "used otp",
			loginIdentifier: "jane@example.com",
			databaseName:    "external_db",
			otp:             "used-otp",
		},
		{
			name:            "wrong startup user",
			loginIdentifier: "john@example.com",
			databaseName:    "external_db",
			otp:             "valid-otp",
		},
		{
			name:            "wrong startup database",
			loginIdentifier: "jane@example.com",
			databaseName:    "wrong_db",
			otp:             "valid-otp",
		},
		{
			name:            "unsupported engine",
			loginIdentifier: "jane@example.com",
			databaseName:    "mysql_db",
			otp:             "valid-otp",
		},
		{
			name:            "inactive database",
			loginIdentifier: "jane@example.com",
			databaseName:    "inactive_db",
			otp:             "valid-otp",
		},
	}

	for _, testCase := range testCases {
		testCase := testCase
		t.Run(testCase.name, func(t *testing.T) {
			t.Parallel()

			mock, err := pgxmock.NewPool()
			if err != nil {
				t.Fatalf("new pool: %v", err)
			}
			defer mock.Close()

			store, err := NewStoreWithDB(mock, "test-technical-credentials-key")
			if err != nil {
				t.Fatalf("NewStoreWithDB: %v", err)
			}

			mock.ExpectQuery(regexp.QuoteMeta(consumeOTPQuery)).
				WithArgs(
					sha256Hex(testCase.otp),
					testCase.loginIdentifier,
					testCase.databaseName,
					"test-technical-credentials-key",
				).
				WillReturnError(pgx.ErrNoRows)

			_, err = store.ConsumeOTP(
				context.Background(),
				testCase.loginIdentifier,
				testCase.databaseName,
				testCase.otp,
			)
			if !errors.Is(err, ErrAuthenticationFailed) {
				t.Fatalf("expected ErrAuthenticationFailed, got %v", err)
			}

			if err := mock.ExpectationsWereMet(); err != nil {
				t.Fatalf("unmet expectations: %v", err)
			}
		})
	}
}

func TestConsumeOTPQueryGuardsRequiredConstraints(t *testing.T) {
	t.Parallel()

	requiredFragments := []string{
		"set_config('app.technical_credentials_key', $4, false)",
		"tac.otp_hash = $1",
		"tac.used_at IS NULL",
		"tac.expires_at > NOW()",
		"u.email = $2",
		"d.database_name = $3",
		"d.engine = 'postgres'",
		"d.deactivated_at IS NULL",
		"technical_password_ciphertext",
		"pgp_sym_decrypt",
		"SET used_at = NOW()",
		"updated_at = NOW()",
	}

	for _, fragment := range requiredFragments {
		if !strings.Contains(consumeOTPQuery, fragment) {
			t.Fatalf("consumeOTPQuery missing %q", fragment)
		}
	}
}

func TestEndSessionStoresFinalCounters(t *testing.T) {
	t.Parallel()

	mock, err := pgxmock.NewPool()
	if err != nil {
		t.Fatalf("new pool: %v", err)
	}
	defer mock.Close()

	store, err := NewStoreWithDB(mock, "test-technical-credentials-key")
	if err != nil {
		t.Fatalf("NewStoreWithDB: %v", err)
	}
	sessionID := uuid.MustParse("dddddddd-dddd-dddd-dddd-dddddddddddd")
	endedAt := time.Date(2026, time.April, 13, 10, 15, 0, 0, time.UTC)

	mock.ExpectExec(regexp.QuoteMeta(endSessionQuery)).
		WithArgs(sessionID, endedAt, int64(120), int64(240)).
		WillReturnResult(pgxmock.NewResult("UPDATE", 1))

	if err := store.EndSession(
		context.Background(),
		sessionID,
		endedAt,
		120,
		240,
	); err != nil {
		t.Fatalf("EndSession: %v", err)
	}

	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatalf("unmet expectations: %v", err)
	}
}

func TestNewStoreRejectsMissingTechnicalCredentialsKey(t *testing.T) {
	t.Parallel()

	mock, err := pgxmock.NewPool()
	if err != nil {
		t.Fatalf("new pool: %v", err)
	}
	defer mock.Close()

	_, err = NewStoreWithDB(mock, "   ")
	if !errors.Is(err, ErrMissingTechnicalCredentialsKey) {
		t.Fatalf("expected ErrMissingTechnicalCredentialsKey, got %v", err)
	}
}
