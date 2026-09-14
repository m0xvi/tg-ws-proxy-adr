package main

import (
	"context"
	"encoding/json"
	"errors"
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

func newTestServer(t *testing.T) (*httptest.Server, *server) {
	t.Helper()
	srv := &server{
		cfg: config{
			listenAddr: ":0",
			token:      testToken,
			maxConns:   4,
			readLimit:  defaultWSReadLimit,
		},
		sem: make(chan struct{}, 4),
	}
	ts := httptest.NewServer(srv.handler())
	t.Cleanup(ts.Close)
	return ts, srv
}

// wsURL переводит http:// адрес httptest-сервера в ws://.
func wsURL(httpURL, path string) string {
	return "ws" + strings.TrimPrefix(httpURL, "http") + path
}

func dialWS(t *testing.T, url string, header http.Header) *websocket.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, resp, err := websocket.Dial(ctx, url, &websocket.DialOptions{HTTPHeader: header})
	if err != nil {
		status := 0
		if resp != nil {
			status = resp.StatusCode
		}
		t.Fatalf("websocket dial %s failed (http %d): %v", url, status, err)
	}
	return conn
}

func TestHealthz(t *testing.T) {
	ts, _ := newTestServer(t)

	resp, err := http.Get(ts.URL + "/healthz")
	if err != nil {
		t.Fatalf("GET /healthz: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	var payload healthzResponse
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if payload.Status != "ok" || payload.Version != version || payload.ActiveSessions != 0 {
		t.Fatalf("unexpected payload: %+v", payload)
	}
}

func TestReadyzReportsWhitelistedDCs(t *testing.T) {
	ts, srv := newTestServer(t)

	// Подменяем дозвон до DC локальным listener'ом, чтобы тест не зависел от сети.
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer listener.Close()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			conn.Close()
		}
	}()

	restore := swapDialUpstream(func(ctx context.Context, network, address string) (net.Conn, error) {
		return net.Dial("tcp", listener.Addr().String())
	})
	defer restore()

	resp, err := http.Get(ts.URL + "/readyz")
	if err != nil {
		t.Fatalf("GET /readyz: %v", err)
	}
	defer resp.Body.Close()
	var payload readyzResponse
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if payload.Status != "ok" {
		t.Fatalf("status = %q, want ok (%+v)", payload.Status, payload.Checks)
	}
	if len(payload.Checks) != len(dcOrder) {
		t.Fatalf("checks = %d, want %d", len(payload.Checks), len(dcOrder))
	}
	if payload.Checks[len(payload.Checks)-1].Name != "dc203" {
		t.Fatalf("last check = %q, want dc203", payload.Checks[len(payload.Checks)-1].Name)
	}
	for _, check := range payload.Checks {
		if !check.OK {
			t.Fatalf("check %s not ok: %+v", check.Name, check)
		}
	}
	_ = srv
}

func TestReadyzDegradedWhenDCUnreachable(t *testing.T) {
	ts, _ := newTestServer(t)
	restore := swapDialUpstream(func(ctx context.Context, network, address string) (net.Conn, error) {
		return nil, errors.New("no route to host")
	})
	defer restore()

	resp, err := http.Get(ts.URL + "/readyz")
	if err != nil {
		t.Fatalf("GET /readyz: %v", err)
	}
	defer resp.Body.Close()
	var payload readyzResponse
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200 so the app can still read details", resp.StatusCode)
	}
	if payload.Status != "degraded" {
		t.Fatalf("status = %q, want degraded", payload.Status)
	}
}

func TestDownloadReturnsExactByteCount(t *testing.T) {
	ts, _ := newTestServer(t)

	resp, err := http.Get(ts.URL + "/download?bytes=4096")
	if err != nil {
		t.Fatalf("GET /download: %v", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	if len(body) != 4096 {
		t.Fatalf("downloaded %d bytes, want 4096", len(body))
	}

	bad, err := http.Get(ts.URL + "/download?bytes=abc")
	if err != nil {
		t.Fatalf("GET /download?bytes=abc: %v", err)
	}
	defer bad.Body.Close()
	if bad.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", bad.StatusCode)
	}
}

func TestProbeHandshake(t *testing.T) {
	ts, _ := newTestServer(t)
	conn := dialWS(t, wsURL(ts.URL, "/probe"), nil)
	defer conn.CloseNow()

	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	if _, _, err := conn.Read(ctx); err == nil {
		t.Fatalf("expected the probe socket to be closed after %s", probeHoldDuration)
	}
}

func TestProbeStreamSendsRequestedBytes(t *testing.T) {
	ts, _ := newTestServer(t)
	conn := dialWS(t, wsURL(ts.URL, "/probe-stream?bytes=8192"), nil)
	defer conn.CloseNow()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	total := 0
	for total < 8192 {
		_, data, err := conn.Read(ctx)
		if err != nil {
			t.Fatalf("read after %d bytes: %v", total, err)
		}
		total += len(data)
	}
	if total != 8192 {
		t.Fatalf("received %d bytes, want 8192", total)
	}
}

func TestProbeUploadAcceptsRequestedBytes(t *testing.T) {
	ts, _ := newTestServer(t)
	conn := dialWS(t, wsURL(ts.URL, "/probe-upload?bytes=8192"), nil)
	defer conn.CloseNow()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	payload := make([]byte, 4096)
	for i := 0; i < 2; i++ {
		if err := conn.Write(ctx, websocket.MessageBinary, payload); err != nil {
			t.Fatalf("write %d: %v", i, err)
		}
	}
	if _, _, err := conn.Read(ctx); err == nil {
		t.Fatal("expected the server to close the upload socket")
	}
}

func TestAPIWSRequiresValidToken(t *testing.T) {
	ts, _ := newTestServer(t)

	for name, url := range map[string]string{
		"no token":    wsURL(ts.URL, "/apiws?dc=2"),
		"wrong token": wsURL(ts.URL, "/apiws?dc=2&token=deadbeefdeadbeefdeadbeefdeadbeef"),
	} {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		_, resp, err := websocket.Dial(ctx, url, nil)
		cancel()
		if err == nil {
			t.Fatalf("%s: dial unexpectedly succeeded", name)
		}
		if resp == nil || resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("%s: status = %v, want 401", name, resp)
		}
	}
}

func TestAPIWSRejectsDCOutsideWhitelist(t *testing.T) {
	ts, _ := newTestServer(t)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, resp, err := websocket.Dial(ctx, wsURL(ts.URL, "/apiws?dc=99&token="+testToken), nil)
	if err == nil {
		t.Fatal("dial unexpectedly succeeded")
	}
	if resp == nil || resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %v, want 400", resp)
	}
}

func TestAPIWSReportsUnreachableDCBeforeUpgrade(t *testing.T) {
	ts, _ := newTestServer(t)
	restore := swapDialUpstream(func(ctx context.Context, network, address string) (net.Conn, error) {
		return nil, errors.New("connection refused")
	})
	defer restore()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, resp, err := websocket.Dial(ctx, wsURL(ts.URL, "/apiws?dc=2&token="+testToken), nil)
	if err == nil {
		t.Fatal("dial unexpectedly succeeded")
	}
	if resp == nil || resp.StatusCode != http.StatusBadGateway {
		t.Fatalf("status = %v, want 502", resp)
	}
}

// TestAPIWSTunnelsTraffic проверяет главный сценарий: байты из WebSocket
// доезжают до «DC» (локальный echo-сервер) и возвращаются обратно.
func TestAPIWSTunnelsTraffic(t *testing.T) {
	ts, _ := newTestServer(t)

	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer echo.Close()
	go func() {
		for {
			conn, err := echo.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = io.Copy(c, c)
			}(conn)
		}
	}()
	restore := swapDialUpstream(func(ctx context.Context, network, address string) (net.Conn, error) {
		return net.Dial("tcp", echo.Addr().String())
	})
	defer restore()

	conn := dialWS(t, wsURL(ts.URL, "/apiws?dc=2&token="+testToken), nil)
	defer conn.CloseNow()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	want := []byte("mtproto-ish payload")
	if err := conn.Write(ctx, websocket.MessageBinary, want); err != nil {
		t.Fatalf("write: %v", err)
	}
	_, got, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(got) != string(want) {
		t.Fatalf("echoed %q, want %q", got, want)
	}
}

// TestAPIWSAcceptsBearerToken — так токен передаёт встроенная диагностика.
func TestAPIWSAcceptsBearerToken(t *testing.T) {
	ts, _ := newTestServer(t)

	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer echo.Close()
	go func() {
		for {
			conn, err := echo.Accept()
			if err != nil {
				return
			}
			conn.Close()
		}
	}()
	restore := swapDialUpstream(func(ctx context.Context, network, address string) (net.Conn, error) {
		return net.Dial("tcp", echo.Addr().String())
	})
	defer restore()

	conn := dialWS(t, wsURL(ts.URL, "/apiws?dc=203"), http.Header{
		"Authorization": []string{"Bearer " + testToken},
	})
	conn.CloseNow()
}

func TestLoadConfigValidatesToken(t *testing.T) {
	t.Setenv("RELAY_TOKEN", "too-short")
	if _, err := loadConfig(); err == nil {
		t.Fatal("expected an error for a short token")
	}

	t.Setenv("RELAY_TOKEN", testToken)
	t.Setenv("MAX_CONNECTIONS", "not-a-number")
	t.Setenv("LISTEN_ADDR", "")
	cfg, err := loadConfig()
	if err != nil {
		t.Fatalf("loadConfig: %v", err)
	}
	if cfg.listenAddr != defaultListenAddr || cfg.maxConns != defaultMaxConnections {
		t.Fatalf("unexpected config: %+v", cfg)
	}
}

func swapDialUpstream(fn func(context.Context, string, string) (net.Conn, error)) func() {
	previous := dialUpstream
	dialUpstream = fn
	return func() { dialUpstream = previous }
}
