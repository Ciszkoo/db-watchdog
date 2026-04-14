package main

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/Ciszkoo/db-watchdog/reverse-proxy/protocol/postgres"
	"github.com/Ciszkoo/db-watchdog/reverse-proxy/systemdb"
	"github.com/Ciszkoo/db-watchdog/reverse-proxy/tunnel"
	"github.com/jackc/pgx/v5/pgxpool"
)

const defaultSystemDBDSN = "postgres://postgres:password@localhost:54320/db_watchdog?sslmode=disable"

func main() {
	ctx, cancel := signal.NotifyContext(
		context.Background(),
		syscall.SIGINT, syscall.SIGTERM,
	)
	defer cancel()

	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelDebug,
	})))

	systemDBPool, err := pgxpool.New(ctx, systemDBDSN())
	if err != nil {
		slog.Error("failed to connect to system db", "err", err)
		os.Exit(1)
	}
	defer systemDBPool.Close()

	if err := systemDBPool.Ping(ctx); err != nil {
		slog.Error("failed to ping system db", "err", err)
		os.Exit(1)
	}

	tlsConfig, err := loadTLSConfig()
	if err != nil {
		slog.Error("failed to load TLS config", "err", err)
		os.Exit(1)
	}

	handler := postgres.NewHandler(tlsConfig)
	store := systemdb.NewStore(systemDBPool)

	listener, err := net.Listen("tcp", ":5432")
	if err != nil {
		slog.Error("failed to listen", "err", err)
		os.Exit(1)
	}
	defer listener.Close()

	slog.Info("proxy listening", "addr", ":5432")

	// Close listener when signal received. Temp to handle single connection.
	go func() {
		<-ctx.Done()
		listener.Close()
	}()

	for {
		conn, err := listener.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				return
			default:
				slog.Warn("accept error", "err", err)
				continue
			}
		}
		go handleConnection(ctx, conn, handler, store)
	}
}

func handleConnection(
	ctx context.Context,
	clientConn net.Conn,
	handler *postgres.Handler,
	store *systemdb.Store,
) {
	defer clientConn.Close()
	clientIP := clientConn.RemoteAddr().String()

	// PG handshake
	result, err := handler.Handshake(ctx, clientConn)
	if errors.Is(err, io.EOF) {
		slog.Debug("client disconnected during handshake", "ip", clientIP)
		return
	}
	if err != nil {
		slog.Warn("handshake failed", "ip", clientIP, "err", err)
		return
	}

	credential, err := store.ConsumeOTP(
		ctx,
		result.User,
		result.Database,
		result.Password,
	)
	if err != nil {
		if errors.Is(err, systemdb.ErrAuthenticationFailed) {
			slog.Warn("authentication failed", "user", result.User, "database", result.Database, "ip", clientIP)
		} else {
			slog.Error("system db auth lookup failed", "user", result.User, "database", result.Database, "ip", clientIP, "err", err)
		}
		postgres.SendError(result.Conn, "password authentication failed")
		return
	}

	// Connect with backend
	backendAddr := net.JoinHostPort(credential.Host, strconv.Itoa(credential.Port))
	backendConn, buffered, err := handler.ConnectToBackend(
		backendAddr,
		credential.TechnicalUser,
		credential.TechnicalPassword,
		credential.DatabaseName,
	)
	if err != nil {
		slog.Error("backend connect failed", "user", result.User, "database", credential.DatabaseName, "ip", clientIP, "err", err)
		postgres.SendError(result.Conn, "could not connect to database")
		return
	}
	defer backendConn.Close()

	sessionID, err := store.StartSession(
		ctx,
		systemdb.StartSessionInput{
			UserID:       credential.UserID,
			DatabaseID:   credential.DatabaseID,
			CredentialID: credential.CredentialID,
			ClientAddr:   clientIP,
			StartedAt:    time.Now().UTC(),
		},
	)
	if err != nil {
		backendConn.Close()
		slog.Error("failed to create session", "user", result.User, "database", credential.DatabaseName, "ip", clientIP, "err", err)
		postgres.SendError(result.Conn, "could not connect to database")
		return
	}

	var stats tunnel.Stats
	defer func() {
		if err := store.EndSession(
			ctx,
			sessionID,
			time.Now().UTC(),
			stats.BytesSent,
			stats.BytesRecv,
		); err != nil {
			slog.Warn("failed to mark session ended", "session_id", sessionID, "err", err)
		}
	}()

	// Inform client on success
	if err := postgres.SendAuthOk(result.Conn); err != nil {
		return
	}

	slog.Info("session started", "session_id", sessionID, "user", result.User, "database", credential.DatabaseName, "ip", clientIP)

	// Forward the messages the backend sent after AuthOK to the client
	// (ParameterStatus, BackendKeyData, ReadyForQuery)
	if _, err := result.Conn.Write(buffered); err != nil {
		return
	}

	// Open pipe
	stats = tunnel.Pipe(result.Conn, backendConn)

	slog.Info("session ended", "session_id", sessionID, "user", result.User, "bytes_sent", stats.BytesSent, "bytes_received", stats.BytesRecv)
}

func loadTLSConfig() (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(
		os.Getenv("TLS_CERT_FILE"),
		os.Getenv("TLS_KEY_FILE"),
	)
	if err != nil {
		return nil, err
	}
	return &tls.Config{Certificates: []tls.Certificate{cert}}, nil
}

func systemDBDSN() string {
	if value := os.Getenv("SYSTEM_DB_DSN"); value != "" {
		return value
	}

	return defaultSystemDBDSN
}
