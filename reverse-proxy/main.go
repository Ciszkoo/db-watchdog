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
	"syscall"

	"github.com/Ciszkoo/db-watchdog/reverse-proxy/protocol/postgres"
	"github.com/Ciszkoo/db-watchdog/reverse-proxy/tunnel"
)

func main() {
	ctx, cancel := signal.NotifyContext(
		context.Background(),
		syscall.SIGINT, syscall.SIGTERM,
	)
	defer cancel()

	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelDebug,
	})))

	// TODO: Connect to the main DB

	tlsConfig, err := loadTLSConfig()
	if err != nil {
		slog.Error("failed to load TLS config", "err", err)
		os.Exit(1)
	}

	handler := postgres.NewHandler(tlsConfig)

	listener, err := net.Listen("tcp", ":5432")
	if err != nil {
		slog.Error("failed to listen", "err", err)
		os.Exit(1)
	}
	defer listener.Close()

	slog.Info("proxy listening", "addr", ":5423")

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
		go handleConnection(ctx, conn, handler)
	}
}

func handleConnection(ctx context.Context, clientConn net.Conn, handler *postgres.Handler) {
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

	// TODO: verify token in system DB

	// Connect with backend
	backendConn, buffered, err := handler.ConnectToBackend(
		"localhost:54321",
		"proxy_user_1",
		"proxy_pass",
		result.Database,
	)
	if err != nil {
		slog.Error("backend connect failed", "err", err)
		postgres.SendError(result.Conn, "could not connect to database")
		return
	}
	defer backendConn.Close()

	// Inform client on success
	if err := postgres.SendAuthOk(result.Conn); err != nil {
		return
	}

	// TODO: save session info in system DB

	slog.Info("session started", "user", result.User, "database", result.Database, "ip", clientIP)

	// Forward the messages the backend sent after AuthOK to the client
	// (ParameterStatus, BackendKeyData, ReadyForQuery)
	if _, err := result.Conn.Write(buffered); err != nil {
		return
	}

	// Open pipe
	stats := tunnel.Pipe(result.Conn, backendConn)

	slog.Info("session ended", "user", result.User, "bytes_sent", stats.BytesSent, "bytes_recv", stats.BytesRecv)
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
