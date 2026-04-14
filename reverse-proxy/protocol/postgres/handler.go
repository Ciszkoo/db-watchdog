package postgres

import (
	"bytes"
	"context"
	"crypto/md5"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
)

type Handler struct {
	tlsConfig *tls.Config // nil => no TLS
}

func NewHandler(tlsConfig *tls.Config) *Handler {
	return &Handler{tlsConfig: tlsConfig}
}

type HandshakeResult struct {
	User     string
	Database string
	Password string
	Conn     net.Conn // May become TLS
}

func (h *Handler) Handshake(ctx context.Context, conn net.Conn) (*HandshakeResult, error) {
	// SSLRequest or Startup
	slog.Debug("reading startup message")
	startup, isSSL, err := ReadStartupMessage(conn)
	if err != nil {
		return nil, fmt.Errorf("reading startup: %w", err)
	}
	slog.Debug("startup message read", "isSSL", isSSL)

	// Handle SSLRequest
	if isSSL {
		if h.tlsConfig != nil {
			slog.Debug("accepting TLS")
			if err := SendSSLResponse(conn, true); err != nil {
				return nil, err
			}
			// Lift to TLS
			tlsConn := tls.Server(conn, h.tlsConfig)
			if err := tlsConn.Handshake(); err != nil {
				return nil, fmt.Errorf("tls handshake: %w", err)
			}
			conn = tlsConn
			slog.Debug("TLS upgrade successful")
		} else {
			slog.Debug("rejecting TLS")
			if err := SendSSLResponse(conn, false); err != nil {
				return nil, err
			}
		}

		startup, _, err = ReadStartupMessage(conn)
		if err != nil {
			return nil, fmt.Errorf("reading startup after ssl: %w", err)
		}
		slog.Debug("startup message after ssl", "params", startup.Parameters)
	}

	if startup == nil {
		return nil, fmt.Errorf("no startup message received")
	}

	// Ask for password
	slog.Debug("sending auth cleartext password request")
	if err := SendAuthCleartextPassword(conn); err != nil {
		return nil, fmt.Errorf("sending auth request: %w", err)
	}

	// Receive password
	slog.Debug("waiting for password")
	password, err := ReadPasswordMessage(conn)
	if err != nil {
		return nil, fmt.Errorf("reading password: %w", err)
	}
	slog.Debug("password received")

	return &HandshakeResult{
		User:     startup.Parameters["user"],
		Database: startup.Parameters["database"],
		Password: password,
		Conn:     conn,
	}, nil
}

// Connect to the resolved backend database using the stored technical credentials.
func (h *Handler) ConnectToBackend(
	addr string,
	user string,
	password string,
	database string,
) (net.Conn, []byte, error) {
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		return nil, nil, fmt.Errorf("dialing backend: %w", err)
	}

	if err := sendBackendStartup(conn, user, database); err != nil {
		conn.Close()
		return nil, nil, err
	}

	// handleBackendAuth returns bytes that must be forwarded to the client
	buffered, err := handleBackendAuth(conn, user, password)
	if err != nil {
		conn.Close()
		return nil, nil, fmt.Errorf("backend auth: %w", err)
	}

	return conn, buffered, nil
}

func sendBackendStartup(conn net.Conn, user, database string) error {
	params := fmt.Sprintf("user\x00%s\x00database\x00%s\x00\x00", user, database)

	// 4 (length) + 4 (protocol version) + params
	length := uint32(8 + len(params))

	buf := make([]byte, length)
	binary.BigEndian.PutUint32(buf[0:4], length)
	// Protocol version 3.0 = 196608 = 0x00030000
	binary.BigEndian.PutUint32(buf[4:8], 196608)
	copy(buf[8:], params)

	slog.Debug("sending backend startup",
		"user", user,
		"database", database,
		"length", length,
		"params", fmt.Sprintf("%q", params),
	)

	_, err := conn.Write(buf)
	return err
}

// Read backend msg and response on auth chall
func handleBackendAuth(conn net.Conn, user, password string) ([]byte, error) {
	var buf bytes.Buffer

	for {
		msgType := make([]byte, 1)
		if _, err := io.ReadFull(conn, msgType); err != nil {
			// return nil, fmt.Errorf("reading message type: %w", err)
			if errors.Is(err, io.EOF) {
				return nil, io.EOF
			}
			return nil, fmt.Errorf("reading message type: %w", err)
		}

		lenBuf := make([]byte, 4)
		if _, err := io.ReadFull(conn, lenBuf); err != nil {
			return nil, fmt.Errorf("reading length: %w", err)
		}
		msgLen := int(binary.BigEndian.Uint32(lenBuf)) - 4

		var payload []byte
		if msgLen > 0 {
			payload = make([]byte, msgLen)
			if _, err := io.ReadFull(conn, payload); err != nil {
				return nil, fmt.Errorf("reading payload: %w", err)
			}
		}

		slog.Debug("backend message",
			"type", string(msgType),
			"len", msgLen,
		)

		switch msgType[0] {
		case 'R':
			if len(payload) < 4 {
				return nil, fmt.Errorf("auth message too short")
			}
			authType := binary.BigEndian.Uint32(payload[:4])
			slog.Debug("backend auth type", "type", authType)

			switch authType {
			case 0:
				// AuthOK: buffer and return subsequent messages
				continue
			case 5:
				var salt [4]byte
				copy(salt[:], payload[4:8])
				if err := sendBackendMD5Password(conn, user, password, salt); err != nil {
					return nil, err
				}
			case 10:
				if err := HandleSCRAM(conn, password, payload); err != nil {
					return nil, fmt.Errorf("SCRAM auth: %w", err)
				}
			default:
				return nil, fmt.Errorf("unsupported backend auth type: %d", authType)
			}

		case 'S', 'K':
			// ParameterStatus and BackendKeyData: buffer them to forward to the client
			buf.Write(msgType)
			buf.Write(lenBuf)
			buf.Write(payload)

		case 'Z':
			// ReadyForQuery: buffer it and finish
			buf.Write(msgType)
			buf.Write(lenBuf)
			buf.Write(payload)
			return buf.Bytes(), nil

		case 'E':
			return nil, fmt.Errorf("backend error: %s", parseErrorMessage(payload))

		default:
			return nil, fmt.Errorf(
				"unexpected message from backend: %c (%d)",
				msgType[0], msgType[0],
			)
		}
	}
}

func sendBackendMD5Password(
	conn net.Conn,
	user string,
	password string,
	salt [4]byte,
) error {
	// Step 1: md5(password + user)
	inner := md5.Sum([]byte(password + user))
	innerHex := fmt.Sprintf("%x", inner)

	// Step 2: md5(innerHex + salt)
	h := md5.New()
	h.Write([]byte(innerHex))
	h.Write(salt[:])
	outerHex := fmt.Sprintf("md5%x", h.Sum(nil))

	// Format: ['p'][int32 length][password\0]
	// length = 4 (int32 itself) + len(outerHex) + 1 (null terminator)
	msgLen := uint32(4 + len(outerHex) + 1)

	buf := make([]byte, 1+msgLen)
	buf[0] = 'p'
	binary.BigEndian.PutUint32(buf[1:5], msgLen)
	copy(buf[5:], []byte(outerHex))
	buf[5+len(outerHex)] = 0

	slog.Debug("sending MD5 password", "hash", outerHex)

	_, err := conn.Write(buf)
	return err
}

func parseErrorMessage(payload []byte) string {
	i := 0
	for i < len(payload) {
		fieldType := payload[i]
		i++

		j := i
		for j < len(payload) && payload[j] != 0 {
			j++
		}

		if fieldType == 'M' {
			return string(payload[i:j])
		}

		i = j + 1
	}
	return "unknown error"
}
