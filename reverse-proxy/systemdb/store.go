package systemdb

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

var ErrAuthenticationFailed = errors.New("authentication failed")
var ErrMissingTechnicalCredentialsKey = errors.New("missing TECHNICAL_CREDENTIALS_KEY")

const consumeOTPQuery = `
WITH configured_session AS (
  SELECT set_config('app.technical_credentials_key', $4, false),
         set_config('app.previous_technical_credentials_key', $5, false)
)
UPDATE db_watchdog.temporary_access_credentials AS tac
SET used_at = NOW(),
    updated_at = NOW()
FROM db_watchdog.users AS u, db_watchdog.databases AS d, configured_session
WHERE tac.user_id = u.id
  AND tac.database_id = d.id
  AND tac.otp_hash = $1
  AND tac.used_at IS NULL
  AND tac.expires_at > NOW()
  AND u.email = $2
  AND d.database_name = $3
  AND d.engine = 'postgres'
  AND d.deactivated_at IS NULL
RETURNING tac.id,
          tac.user_id,
          tac.database_id,
          d.host,
          d.port,
          d.database_name,
          d.technical_user,
          db_watchdog.decrypt_technical_password(
            d.technical_password_ciphertext,
            $4,
            $5
          )
`

const startSessionQuery = `
INSERT INTO db_watchdog.database_sessions (user_id, database_id, credential_id, client_addr, started_at)
VALUES ($1, $2, $3, $4, $5)
RETURNING id
`

const endSessionQuery = `
UPDATE db_watchdog.database_sessions
SET ended_at = $2,
    bytes_sent = $3,
    bytes_received = $4,
    updated_at = NOW()
WHERE id = $1
`

type db interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
}

type Store struct {
	db                              db
	technicalCredentialsKey         string
	previousTechnicalCredentialsKey *string
}

type ConsumedCredential struct {
	CredentialID      uuid.UUID
	UserID            uuid.UUID
	DatabaseID        uuid.UUID
	Host              string
	Port              int
	DatabaseName      string
	TechnicalUser     string
	TechnicalPassword string
}

type StartSessionInput struct {
	UserID       uuid.UUID
	DatabaseID   uuid.UUID
	CredentialID uuid.UUID
	ClientAddr   string
	StartedAt    time.Time
}

func NewStore(pool *pgxpool.Pool, technicalCredentialsKey string, previousTechnicalCredentialsKey string) (*Store, error) {
	return newStore(pool, technicalCredentialsKey, previousTechnicalCredentialsKey)
}

func NewStoreWithDB(querier db, technicalCredentialsKey string, previousTechnicalCredentialsKey string) (*Store, error) {
	return newStore(querier, technicalCredentialsKey, previousTechnicalCredentialsKey)
}

func newStore(querier db, technicalCredentialsKey string, previousTechnicalCredentialsKey string) (*Store, error) {
	if strings.TrimSpace(technicalCredentialsKey) == "" {
		return nil, ErrMissingTechnicalCredentialsKey
	}

	var normalizedPreviousKey *string
	if strings.TrimSpace(previousTechnicalCredentialsKey) != "" {
		normalizedPreviousKey = &previousTechnicalCredentialsKey
	}

	return &Store{
		db:                              querier,
		technicalCredentialsKey:         technicalCredentialsKey,
		previousTechnicalCredentialsKey: normalizedPreviousKey,
	}, nil
}

func (s *Store) ConsumeOTP(
	ctx context.Context,
	loginIdentifier string,
	databaseName string,
	otp string,
) (ConsumedCredential, error) {
	var credential ConsumedCredential

	err := s.db.QueryRow(
		ctx,
		consumeOTPQuery,
		sha256Hex(otp),
		loginIdentifier,
		databaseName,
		s.technicalCredentialsKey,
		s.previousTechnicalCredentialsKeyValue(),
	).Scan(
		&credential.CredentialID,
		&credential.UserID,
		&credential.DatabaseID,
		&credential.Host,
		&credential.Port,
		&credential.DatabaseName,
		&credential.TechnicalUser,
		&credential.TechnicalPassword,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ConsumedCredential{}, ErrAuthenticationFailed
		}
		return ConsumedCredential{}, err
	}

	return credential, nil
}

func (s *Store) previousTechnicalCredentialsKeyValue() string {
	if s.previousTechnicalCredentialsKey == nil {
		return ""
	}

	return *s.previousTechnicalCredentialsKey
}

func (s *Store) StartSession(
	ctx context.Context,
	input StartSessionInput,
) (uuid.UUID, error) {
	var sessionID uuid.UUID

	err := s.db.QueryRow(
		ctx,
		startSessionQuery,
		input.UserID,
		input.DatabaseID,
		input.CredentialID,
		input.ClientAddr,
		input.StartedAt,
	).Scan(&sessionID)
	if err != nil {
		return uuid.Nil, err
	}

	return sessionID, nil
}

func (s *Store) EndSession(
	ctx context.Context,
	sessionID uuid.UUID,
	endedAt time.Time,
	bytesSent int64,
	bytesReceived int64,
) error {
	_, err := s.db.Exec(
		ctx,
		endSessionQuery,
		sessionID,
		endedAt,
		bytesSent,
		bytesReceived,
	)
	return err
}

func sha256Hex(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}
