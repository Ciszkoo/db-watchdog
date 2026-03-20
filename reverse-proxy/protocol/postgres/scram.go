package postgres

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strings"

	"golang.org/x/crypto/pbkdf2"
)

func HandleSCRAM(conn net.Conn, password string, payload []byte) error {
	// payload contains the list of mechanisms: "SCRAM-SHA-256\0..."
	// check whether SCRAM-SHA-256 is available
	mechanisms := parseSCRAMMechanisms(payload[4:])
	supported := false
	for _, m := range mechanisms {
		if m == "SCRAM-SHA-256" {
			supported = true
			break
		}
	}
	if !supported {
		return fmt.Errorf("SCRAM-SHA-256 not supported by backend")
	}

	// Step 1: client-first-message
	clientNonce := generateNonce()
	clientFirstMessageBare := fmt.Sprintf("n=,r=%s", clientNonce)
	clientFirstMessage := "n,," + clientFirstMessageBare

	if err := sendSCRAMClientFirst(conn, clientFirstMessage); err != nil {
		return err
	}

	// Step 2: receive server-first-message
	serverFirst, err := readSCRAMServerMessage(conn)
	if err != nil {
		return err
	}

	// Parse server-first-message: r=<nonce>,s=<salt>,i=<iterations>
	params := parseSCRAMMessage(serverFirst)
	serverNonce := params["r"]
	saltB64 := params["s"]
	iterations := parseInt(params["i"])

	if !strings.HasPrefix(serverNonce, clientNonce) {
		return fmt.Errorf("server nonce does not start with client nonce")
	}

	salt, err := base64.StdEncoding.DecodeString(saltB64)
	if err != nil {
		return fmt.Errorf("decoding salt: %w", err)
	}

	// Step 3: compute client-final-message
	saltedPassword := pbkdf2.Key(
		[]byte(password), salt, iterations, 32, sha256.New,
	)

	clientKey := hmacSHA256(saltedPassword, []byte("Client Key"))
	storedKey := sha256.Sum256(clientKey)

	channelBinding := base64.StdEncoding.EncodeToString([]byte("n,,"))
	clientFinalMessageWithoutProof := fmt.Sprintf(
		"c=%s,r=%s", channelBinding, serverNonce,
	)

	authMessage := strings.Join([]string{
		clientFirstMessageBare,
		serverFirst,
		clientFinalMessageWithoutProof,
	}, ",")

	clientSignature := hmacSHA256(storedKey[:], []byte(authMessage))
	clientProof := xorBytes(clientKey, clientSignature)
	clientProofB64 := base64.StdEncoding.EncodeToString(clientProof)

	clientFinalMessage := fmt.Sprintf(
		"%s,p=%s", clientFinalMessageWithoutProof, clientProofB64,
	)

	if err := sendSCRAMClientFinal(conn, clientFinalMessage); err != nil {
		return err
	}

	// Step 4: receive server-final-message (AuthenticationSASLFinal)
	if err := readSCRAMServerFinal(conn); err != nil {
		return err
	}

	return nil
}

// Helpers

func generateNonce() string {
	buf := make([]byte, 18)
	rand.Read(buf)
	return base64.StdEncoding.EncodeToString(buf)
}

func hmacSHA256(key, msg []byte) []byte {
	h := hmac.New(sha256.New, key)
	h.Write(msg)
	return h.Sum(nil)
}

func xorBytes(a, b []byte) []byte {
	result := make([]byte, len(a))
	for i := range a {
		result[i] = a[i] ^ b[i]
	}
	return result
}

func parseSCRAMMechanisms(data []byte) []string {
	var mechanisms []string
	parts := strings.Split(string(data), "\x00")
	for _, p := range parts {
		if p != "" {
			mechanisms = append(mechanisms, p)
		}
	}
	return mechanisms
}

func parseSCRAMMessage(msg string) map[string]string {
	result := make(map[string]string)
	for _, part := range strings.Split(msg, ",") {
		if idx := strings.IndexByte(part, '='); idx >= 0 {
			result[part[:idx]] = part[idx+1:]
		}
	}
	return result
}

func parseInt(s string) int {
	n := 0
	fmt.Sscanf(s, "%d", &n)
	return n
}

func sendSCRAMClientFirst(conn net.Conn, message string) error {
	// Format: ['p'][int32 length]["SCRAM-SHA-256\0"][int32 msglen][message]
	mechanism := "SCRAM-SHA-256"
	msgLen := len(message)

	// mechanism\0 + int32(msgLen) + message
	bodyLen := len(mechanism) + 1 + 4 + msgLen
	totalLen := 4 + bodyLen

	buf := make([]byte, 1+totalLen)
	buf[0] = 'p'
	binary.BigEndian.PutUint32(buf[1:5], uint32(totalLen))
	copy(buf[5:], mechanism)
	buf[5+len(mechanism)] = 0
	binary.BigEndian.PutUint32(buf[6+len(mechanism):], uint32(msgLen))
	copy(buf[10+len(mechanism):], message)

	_, err := conn.Write(buf)
	return err
}

func sendSCRAMClientFinal(conn net.Conn, message string) error {
	// Format: ['p'][int32 length][message]
	totalLen := 4 + len(message)
	buf := make([]byte, 1+totalLen)
	buf[0] = 'p'
	binary.BigEndian.PutUint32(buf[1:5], uint32(totalLen))
	copy(buf[5:], message)
	_, err := conn.Write(buf)
	return err
}

func readSCRAMServerMessage(conn net.Conn) (string, error) {
	// Expect AuthenticationSASLContinue (R, type=11)
	msgType := make([]byte, 1)
	if _, err := io.ReadFull(conn, msgType); err != nil {
		return "", err
	}
	if msgType[0] != 'R' {
		return "", fmt.Errorf("expected auth message, got %c", msgType[0])
	}

	lenBuf := make([]byte, 4)
	if _, err := io.ReadFull(conn, lenBuf); err != nil {
		return "", err
	}
	msgLen := int(binary.BigEndian.Uint32(lenBuf)) - 4

	payload := make([]byte, msgLen)
	if _, err := io.ReadFull(conn, payload); err != nil {
		return "", err
	}

	authType := binary.BigEndian.Uint32(payload[:4])
	if authType != 11 { // AuthenticationSASLContinue
		return "", fmt.Errorf("expected SASLContinue (11), got %d", authType)
	}

	return string(payload[4:]), nil
}

func readSCRAMServerFinal(conn net.Conn) error {
	// Expect AuthenticationSASLFinal (R, type=12)
	msgType := make([]byte, 1)
	if _, err := io.ReadFull(conn, msgType); err != nil {
		return err
	}
	if msgType[0] != 'R' {
		return fmt.Errorf("expected auth message, got %c", msgType[0])
	}

	lenBuf := make([]byte, 4)
	if _, err := io.ReadFull(conn, lenBuf); err != nil {
		return err
	}
	msgLen := int(binary.BigEndian.Uint32(lenBuf)) - 4

	payload := make([]byte, msgLen)
	if _, err := io.ReadFull(conn, payload); err != nil {
		return err
	}

	authType := binary.BigEndian.Uint32(payload[:4])
	if authType != 12 { // AuthenticationSASLFinal
		return fmt.Errorf("expected SASLFinal (12), got %d", authType)
	}

	// The server signature can be verified, but for now we ignore it
	return nil
}
