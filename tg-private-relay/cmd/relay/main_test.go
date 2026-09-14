package main

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

const testToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func newTestRelay(dc2Address string) *relayServer {
	return &relayServer{
		cfg: config{
			token:          testToken,
			maxConnections: 8,
			readLimit:      2 * 1024 * 1024,
			dcAddresses:    map[int]string{2: dc2Address},
		},
		slots: make(chan struct{}, 8),
	}
}

func TestLoadConfigRejectsShortToken(t *testing.T) {
	t.Setenv("RELAY_TOKEN", "short")
	if _, err := loadConfig(); err == nil {
		t.Fatal("expected short token to be rejected")
	}
}

func TestAuthorization(t *testing.T) {
	relay := newTestRelay("127.0.0.1:1")

	queryRequest := httptest.NewRequest(http.MethodGet, "/apiws?token="+testToken, nil)
	if !relay.authorized(queryRequest) {
		t.Fatal("query token should authorize")
	}

	headerRequest := httptest.NewRequest(http.MethodGet, "/apiws", nil)
	headerRequest.Header.Set("Authorization", "Bearer "+testToken)
	if !relay.authorized(headerRequest) {
		t.Fatal("bearer token should authorize")
	}

	badRequest := httptest.NewRequest(http.MethodGet, "/apiws?token=wrong", nil)
	if relay.authorized(badRequest) {
		t.Fatal("wrong token must not authorize")
	}
}

func TestHealth(t *testing.T) {
	relay := newTestRelay("127.0.0.1:1")
	recorder := httptest.NewRecorder()
	relay.handleHealth(recorder, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if recorder.Code != http.StatusOK {
		t.Fatalf("health status = %d", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"status":"ok"`) {
		t.Fatalf("unexpected health body: %s", recorder.Body.String())
	}
}

func TestProbeWebSocket(t *testing.T) {
	relay := newTestRelay("127.0.0.1:1")
	server := httptest.NewServer(http.HandlerFunc(relay.handleProbe))
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	wsURL := "ws" + strings.TrimPrefix(server.URL, "http")
	conn, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		t.Fatalf("probe dial: %v", err)
	}
	defer conn.CloseNow()

	messageType, payload, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("probe read: %v", err)
	}
	if messageType != websocket.MessageText || string(payload) != "probe-ok" {
		t.Fatalf("unexpected probe response: type=%v payload=%q", messageType, payload)
	}
}

func TestRelayEchoIntegration(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()

	go func() {
		connection, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		defer connection.Close()
		_, _ = io.Copy(connection, connection)
	}()

	relay := newTestRelay(listener.Addr().String())
	server := httptest.NewServer(http.HandlerFunc(relay.handleRelay))
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	wsURL := "ws" + strings.TrimPrefix(server.URL, "http") + "?dc=2&token=" + testToken
	conn, response, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{Subprotocols: []string{"binary"}})
	if err != nil {
		if response != nil {
			t.Fatalf("relay dial: %v (HTTP %d)", err, response.StatusCode)
		}
		t.Fatalf("relay dial: %v", err)
	}
	defer conn.CloseNow()

	want := []byte("mtproto-test-payload")
	if err := conn.Write(ctx, websocket.MessageBinary, want); err != nil {
		t.Fatalf("relay write: %v", err)
	}
	messageType, got, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("relay read: %v", err)
	}
	if messageType != websocket.MessageBinary || string(got) != string(want) {
		t.Fatalf("echo mismatch: type=%v got=%q", messageType, got)
	}
}

func TestRelayRejectsUnauthorized(t *testing.T) {
	relay := newTestRelay("127.0.0.1:1")
	recorder := httptest.NewRecorder()
	relay.handleRelay(recorder, httptest.NewRequest(http.MethodGet, "/apiws?dc=2", nil))
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", recorder.Code)
	}
}
