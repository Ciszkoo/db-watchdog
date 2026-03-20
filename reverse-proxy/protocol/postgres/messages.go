package postgres

import (
	"encoding/binary"
	"fmt"
	"io"
	"log/slog"
	"net"
)

const (
	SSLRequestCode    = 80877103
	CancelRequestCode = 80877102
)

type StartupMessage struct {
	ProtocolVersion uint32
	Parameters      map[string]string
}

func readInt32(r io.Reader) (uint32, error) {
	buf := make([]byte, 4)
	if _, err := io.ReadFull(r, buf); err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint32(buf), nil
}

func ReadStartupMessage(conn net.Conn) (*StartupMessage, bool, error) {
	// Format: [int32 length][int32 code/version][payload]
	length, err := readInt32(conn)
	if err != nil {
		return nil, false, fmt.Errorf("reading length: %w", err)
	}

	code, err := readInt32(conn)
	if err != nil {
		return nil, false, fmt.Errorf("reading code: %w", err)
	}

	if code == SSLRequestCode {
		return nil, true, nil
	}

	remaining := int(length) - 8
	if remaining < 0 {
		return nil, false, fmt.Errorf("invalid startup message length")
	}

	payload := make([]byte, remaining)
	if _, err := io.ReadFull(conn, payload); err != nil {
		return nil, false, fmt.Errorf("reading payload: %w", err)
	}

	params, err := parseParameters(payload)
	if err != nil {
		return nil, false, err
	}

	return &StartupMessage{
		ProtocolVersion: code,
		Parameters:      params,
	}, false, nil

}

func parseParameters(data []byte) (map[string]string, error) {
	params := make(map[string]string)
	i := 0
	for i < len(data) {
		// Key
		j := i
		for j < len(data) && data[j] != 0 {
			j++
		}
		if j >= len(data) {
			break
		}
		key := string(data[i:j])
		i = j + 1

		// Value
		j = i
		for j < len(data) && data[j] != 0 {
			j++
		}
		value := string(data[i:j])
		i = j + 1

		if key != "" {
			params[key] = value
		}
	}
	return params, nil
}

func ReadPasswordMessage(conn net.Conn) (string, error) {
	// Format: ['p'][int32 length][password\0]
	slog.Debug("[ReadPassword] reading password")
	msgType := make([]byte, 1)
	if _, err := io.ReadFull(conn, msgType); err != nil {
		return "", err
	}
	if msgType[0] != 'p' {
		return "", fmt.Errorf("expected password message, got %c", msgType[0])
	}
	slog.Debug("[ReadPassword] read password successfully")

	length, err := readInt32(conn)
	if err != nil {
		return "", err
	}

	payload := make([]byte, int(length)-4)
	if _, err := io.ReadFull(conn, payload); err != nil {
		return "", err
	}

	// Strip null term
	password := string(payload)
	if len(password) > 0 && password[len(password)-1] == 0 {
		password = password[:len(password)-1]
	}

	return password, nil
}

func SendSSLResponse(conn net.Conn, accept bool) error {
	if accept {
		_, err := conn.Write([]byte("S"))
		return err
	}
	_, err := conn.Write([]byte("N"))
	return err
}

func SendAuthCleartextPassword(conn net.Conn) error {
	// Format: ['R'][int32 length=8][int32 type=3]
	msg := make([]byte, 9)
	msg[0] = 'R'
	binary.BigEndian.PutUint32(msg[1:5], 8)
	binary.BigEndian.PutUint32(msg[5:9], 3)
	_, err := conn.Write(msg)
	return err
}

func SendAuthOk(conn net.Conn) error {
	// Format: ['R'][int32 length=8][int32 type=0]
	msg := make([]byte, 9)
	msg[0] = 'R'
	binary.BigEndian.PutUint32(msg[1:5], 8)
	binary.BigEndian.PutUint32(msg[5:9], 0)
	_, err := conn.Write(msg)
	return err
}

func SendError(conn net.Conn, message string) error {
	// Format: ['E'][int32 length][fields...]
	// Field: ['M'][message\0]['\0']
	fields := fmt.Sprintf("MFATAL\000C28P01\000M%s\000\000", message)
	msg := make([]byte, 5+len(fields))
	msg[0] = 'E'
	binary.BigEndian.PutUint32(msg[1:5], uint32(4+len(fields)))
	copy(msg[5:], fields)
	_, err := conn.Write(msg)
	return err
}
