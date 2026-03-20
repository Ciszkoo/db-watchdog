package tunnel

import (
	"io"
	"net"
	"sync"
)

type Stats struct {
	BytesSent int64
	BytesRecv int64
}

// Pipe moves data between client and backend.
// Blocking until one of the connections get closed.
func Pipe(client net.Conn, backend net.Conn) Stats {
	var stats Stats
	var wg sync.WaitGroup
	wg.Add(2)

	// client -> backend
	go func() {
		defer wg.Done()
		n, _ := io.Copy(backend, client)
		stats.BytesSent = n
		backend.(*net.TCPConn).CloseWrite()
	}()

	// backend -> client
	go func() {
		defer wg.Done()
		n, _ := io.Copy(client, backend)
		stats.BytesRecv = n
		backend.(*net.TCPConn).CloseWrite()
	}()

	wg.Wait()
	return stats
}
