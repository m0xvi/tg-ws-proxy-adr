package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/coder/websocket"
)

const (
	defaultListenAddr     = ":8080"
	defaultMaxConnections = 256
	defaultReadLimit      = 2 * 1024 * 1024
	upstreamDialTimeout   = 8 * time.Second
	shutdownTimeout       = 10 * time.Second
)

type config struct {
	listenAddr     string
	token          string
	maxConnections int
	readLimit      int64
	dcAddresses    map[int]string
}

type relayServer struct {
	cfg      config
	slots    chan struct{}
	active   atomic.Int64
	sessions atomic.Uint64
}

type healthResponse struct {
	Status         string `json:"status"`
	ActiveSessions int64  `json:"active_sessions"`
	Version        string `json:"version"`
}

type readyResponse struct {
	Status string `json:"status"`
	Version string `json:"version"`
	Checks []readyCheck `json:"checks"`
}

type readyCheck struct {
	Name string `json:"name"`
	OK bool `json:"ok"`
	DurationMS int64 `json:"duration_ms"`
	Error string `json:"error,omitempty"`
}

func main() {
	cfg, err := loadConfig()
	if err != nil {
		log.Fatalf("configuration error: %v", err)
	}

	relay := &relayServer{
		cfg:   cfg,
		slots: make(chan struct{}, cfg.maxConnections),
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", relay.handleHealth)
	mux.HandleFunc("GET /readyz", relay.handleReady)
	mux.HandleFunc("GET /download", relay.handleDownload)
	mux.HandleFunc("GET /probe", relay.handleProbe)
	mux.HandleFunc("GET /probe-stream", relay.handleProbeStream)
	mux.HandleFunc("GET /probe-upload", relay.handleProbeUpload)
	mux.HandleFunc("GET /media-test", relay.handleMediaTest)
	mux.HandleFunc("GET /apiws", relay.handleRelay)
	mux.HandleFunc("/", notFound)

	server := &http.Server{
		Addr:              cfg.listenAddr,
		Handler:           securityHeaders(mux),
		ReadHeaderTimeout: 8 * time.Second,
		IdleTimeout:       75 * time.Second,
		MaxHeaderBytes:    32 * 1024,
	}

	stopContext, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go func() {
		<-stopContext.Done()
		ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
		defer cancel()
		if err := server.Shutdown(ctx); err != nil {
			log.Printf("shutdown: %v", err)
		}
	}()

	log.Printf("tg-relay listening on %s; max_connections=%d", cfg.listenAddr, cfg.maxConnections)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("http server: %v", err)
	}
}

func loadConfig() (config, error) {
	token := strings.TrimSpace(os.Getenv("RELAY_TOKEN"))
	if len(token) < 32 {
		return config{}, errors.New("RELAY_TOKEN must contain at least 32 characters")
	}

	cfg := config{
		listenAddr:     envOr("LISTEN_ADDR", defaultListenAddr),
		token:          token,
		maxConnections: envInt("MAX_CONNECTIONS", defaultMaxConnections),
		readLimit:      int64(envInt("WS_READ_LIMIT_BYTES", defaultReadLimit)),
		dcAddresses: map[int]string{
			1:   envOr("DC1_ADDR", "149.154.175.50:443"),
			2:   envOr("DC2_ADDR", "149.154.167.51:443"),
			3:   envOr("DC3_ADDR", "149.154.175.100:443"),
			4:   envOr("DC4_ADDR", "149.154.167.91:443"),
			5:   envOr("DC5_ADDR", "149.154.171.5:443"),
			203: envOr("DC203_ADDR", "91.105.192.100:443"),
		},
	}
	if cfg.maxConnections < 1 || cfg.maxConnections > 10000 {
		return config{}, errors.New("MAX_CONNECTIONS must be between 1 and 10000")
	}
	if cfg.readLimit < 64*1024 || cfg.readLimit > 64*1024*1024 {
		return config{}, errors.New("WS_READ_LIMIT_BYTES is outside the safe range")
	}
	for dc, address := range cfg.dcAddresses {
		if _, _, err := net.SplitHostPort(address); err != nil {
			return config{}, fmt.Errorf("invalid DC%d address %q: %w", dc, address, err)
		}
	}
	return cfg, nil
}

func (s *relayServer) handleHealth(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(healthResponse{
		Status:         "ok",
		ActiveSessions: s.active.Load(),
		Version:        "0.1.2",
	})
}

func (s *relayServer) handleReady(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()

	checks := make([]readyCheck, 0, len(s.cfg.dcAddresses))
	allOK := true
	for _, dc := range []int{1, 2, 3, 4, 5, 203} {
		addr, ok := s.cfg.dcAddresses[dc]
		if !ok {
			continue
		}
		started := time.Now()
		dialer := net.Dialer{Timeout: 4 * time.Second, KeepAlive: 30 * time.Second}
		conn, err := dialer.DialContext(ctx, "tcp", addr)
		check := readyCheck{
			Name:       fmt.Sprintf("dc%d", dc),
			OK:         err == nil,
			DurationMS: time.Since(started).Milliseconds(),
		}
		if err != nil {
			allOK = false
			check.Error = compactReadyError(err)
		} else {
			_ = conn.Close()
		}
		checks = append(checks, check)
	}

	status := "ok"
	if !allOK {
		status = "degraded"
		w.WriteHeader(http.StatusServiceUnavailable)
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(readyResponse{Status: status, Version: "0.1.2", Checks: checks})
}

func (s *relayServer) handleDownload(w http.ResponseWriter, r *http.Request) {
	bytesToSend := boundedBytesParam(r, 1024*1024, 64*1024*1024)
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Length", strconv.Itoa(bytesToSend))
	writePattern(w, bytesToSend)
}

func (s *relayServer) handleProbeStream(w http.ResponseWriter, r *http.Request) {
	bytesToSend := boundedBytesParam(r, 1024*1024, 64*1024*1024)
	conn, err := acceptWebSocket(w, r, 64*1024)
	if err != nil {
		return
	}
	defer conn.CloseNow()

	ctx, cancel := context.WithTimeout(r.Context(), 45*time.Second)
	defer cancel()
	buf := make([]byte, 64*1024)
	fillPattern(buf)
	remaining := bytesToSend
	for remaining > 0 {
		n := len(buf)
		if remaining < n {
			n = remaining
		}
		if err := conn.Write(ctx, websocket.MessageBinary, buf[:n]); err != nil {
			return
		}
		remaining -= n
	}
	_ = conn.Close(websocket.StatusNormalClosure, "stream complete")
}

func (s *relayServer) handleProbeUpload(w http.ResponseWriter, r *http.Request) {
	bytesExpected := boundedBytesParam(r, 1024*1024, 64*1024*1024)
	conn, err := acceptWebSocket(w, r, 256*1024)
	if err != nil {
		return
	}
	defer conn.CloseNow()

	ctx, cancel := context.WithTimeout(r.Context(), 75*time.Second)
	defer cancel()
	started := time.Now()
	var got int
	for got < bytesExpected {
		messageType, payload, err := conn.Read(ctx)
		if err != nil {
			return
		}
		if messageType != websocket.MessageBinary {
			_ = conn.Close(websocket.StatusUnsupportedData, "binary required")
			return
		}
		got += len(payload)
	}
	msg := fmt.Sprintf("upload-ok bytes=%d duration_ms=%d", got, time.Since(started).Milliseconds())
	_ = conn.Write(ctx, websocket.MessageText, []byte(msg))
	_ = conn.Close(websocket.StatusNormalClosure, "upload complete")
}

func (s *relayServer) handleMediaTest(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = io.WriteString(w, mediaTestHTML)
}

func boundedBytesParam(r *http.Request, fallback int, maxValue int) int {
	value := strings.TrimSpace(r.URL.Query().Get("bytes"))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed < 1 {
		return fallback
	}
	if parsed > maxValue {
		return maxValue
	}
	return parsed
}

func writePattern(w io.Writer, total int) {
	buf := make([]byte, 64*1024)
	fillPattern(buf)
	remaining := total
	for remaining > 0 {
		n := len(buf)
		if remaining < n {
			n = remaining
		}
		if _, err := w.Write(buf[:n]); err != nil {
			return
		}
		remaining -= n
	}
}

func fillPattern(buf []byte) {
	for i := range buf {
		buf[i] = byte((i*31 + 17) & 0xff)
	}
}

func compactReadyError(err error) string {
	if err == nil {
		return ""
	}
	msg := err.Error()
	if len(msg) > 160 {
		msg = msg[:160]
	}
	return msg
}

const mediaTestHTML = `<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TG Relay Media Test</title>
<style>
body{font-family:system-ui,-apple-system,Segoe UI,sans-serif;margin:20px;line-height:1.4;max-width:820px}
button{font-size:16px;padding:10px 14px;margin:6px 6px 6px 0}
pre{background:#111;color:#eee;padding:12px;border-radius:8px;white-space:pre-wrap;word-break:break-word}
.ok{color:#0a7f22}.bad{color:#b00020}
</style>
</head>
<body>
<h1>TG Relay Media Test</h1>
<p>Тестирует большой HTTPS download, WSS download и WSS upload через этот же домен. Токен relay не используется.</p>
<p>
<button onclick="runAll(1048576)">Тест 1 MiB</button>
<button onclick="runAll(10485760)">Тест 10 MiB</button>
<button onclick="runAll(52428800)">Тест 50 MiB</button>
<button onclick="clearLog()">Очистить</button>
</p>
<pre id="log"></pre>
<script>
const logEl = document.getElementById('log');
function log(s){ logEl.textContent += s + '\n'; }
function clearLog(){ logEl.textContent=''; }
function fmt(n){ return (n/1048576).toFixed(2)+' MiB'; }
async function testHttp(bytes){
  const url = '/download?bytes=' + bytes + '&_=' + Date.now();
  const started = performance.now();
  const r = await fetch(url, {cache:'no-store'});
  if(!r.ok) throw new Error('HTTP '+r.status);
  const reader = r.body.getReader();
  let got = 0;
  while(true){
    const {done, value} = await reader.read();
    if(done) break;
    got += value.byteLength;
  }
  const sec = (performance.now()-started)/1000;
  log('HTTPS download: '+fmt(got)+' за '+sec.toFixed(2)+'s, '+(got/sec/1048576).toFixed(2)+' MiB/s');
  if(got < bytes) throw new Error('short HTTPS download '+got+'/'+bytes);
}
function testWs(bytes){
  return new Promise((resolve,reject)=>{
    const scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
    const ws = new WebSocket(scheme + location.host + '/probe-stream?bytes=' + bytes + '&_=' + Date.now(), 'binary');
    ws.binaryType = 'arraybuffer';
    let got = 0;
    const started = performance.now();
    const timeout = setTimeout(()=>{ try{ws.close()}catch(e){}; reject(new Error('WebSocket timeout, got '+got)); }, 60000);
    ws.onmessage = ev => { got += ev.data.byteLength || ev.data.size || 0; };
    ws.onerror = () => { clearTimeout(timeout); reject(new Error('WebSocket error, got '+got)); };
    ws.onclose = () => {
      clearTimeout(timeout);
      const sec = (performance.now()-started)/1000;
      log('WSS stream: '+fmt(got)+' за '+sec.toFixed(2)+'s, '+(got/sec/1048576).toFixed(2)+' MiB/s');
      if(got >= bytes) resolve(); else reject(new Error('short WSS stream '+got+'/'+bytes));
    };
  });
}

function testWsUpload(bytes){
  return new Promise((resolve,reject)=>{
    const scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
    const ws = new WebSocket(scheme + location.host + '/probe-upload?bytes=' + bytes + '&_=' + Date.now(), 'binary');
    const chunkSize = 64 * 1024;
    const chunk = new Uint8Array(chunkSize);
    for(let i=0;i<chunk.length;i++) chunk[i]=(i*31+17)&255;
    let sent = 0;
    let ack = '';
    const started = performance.now();
    const timeout = setTimeout(()=>{ try{ws.close()}catch(e){}; reject(new Error('WebSocket upload timeout, sent '+sent)); }, 75000);
    ws.onopen = () => {
      function pump(){
        while(sent < bytes && ws.bufferedAmount < 1024*1024){
          const n = Math.min(chunkSize, bytes-sent);
          ws.send(n === chunkSize ? chunk : chunk.slice(0,n));
          sent += n;
        }
        if(sent < bytes) setTimeout(pump, 10);
      }
      pump();
    };
    ws.onmessage = ev => { ack += String(ev.data || ''); };
    ws.onerror = () => { clearTimeout(timeout); reject(new Error('WebSocket upload error, sent '+sent)); };
    ws.onclose = () => {
      clearTimeout(timeout);
      const sec = (performance.now()-started)/1000;
      log('WSS upload: '+fmt(sent)+' за '+sec.toFixed(2)+'s, '+(sent/sec/1048576).toFixed(2)+' MiB/s ack='+ack);
      if(sent >= bytes && ack.indexOf('upload-ok') >= 0) resolve(); else reject(new Error('upload no ack, sent '+sent+' ack='+ack));
    };
  });
}

async function readyz(){
  const r = await fetch('/readyz?_=' + Date.now(), {cache:'no-store'});
  const text = await r.text();
  log('/readyz HTTP '+r.status+': '+text);
}
async function runAll(bytes){
  log('--- '+new Date().toISOString()+' test '+fmt(bytes)+' ---');
  try { await readyz(); } catch(e) { log('readyz FAIL: '+e.message); }
  try { await testHttp(bytes); log('HTTPS OK'); } catch(e) { log('HTTPS FAIL: '+e.message); }
  try { await testWs(bytes); log('WSS download OK'); } catch(e) { log('WSS download FAIL: '+e.message); }
  try { await testWsUpload(bytes); log('WSS upload OK'); } catch(e) { log('WSS upload FAIL: '+e.message); }
}
</script>
</body>
</html>`

// /probe performs only a short WebSocket handshake. It never connects to
// Telegram and intentionally needs no secret, so the Android diagnostic app can
// verify DNS/TCP/TLS/WSS reachability without placing RELAY_TOKEN in a report.
func (s *relayServer) handleProbe(w http.ResponseWriter, r *http.Request) {
	conn, err := acceptWebSocket(w, r, 64*1024)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	_ = conn.Write(ctx, websocket.MessageText, []byte("probe-ok"))
	_ = conn.Close(websocket.StatusNormalClosure, "probe complete")
}

func (s *relayServer) handleRelay(w http.ResponseWriter, r *http.Request) {
	if !s.authorized(r) {
		w.Header().Set("WWW-Authenticate", "Bearer")
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	dc, err := strconv.Atoi(r.URL.Query().Get("dc"))
	if err != nil {
		http.Error(w, "invalid dc", http.StatusBadRequest)
		return
	}
	upstreamAddress, ok := s.cfg.dcAddresses[dc]
	if !ok {
		http.Error(w, "unsupported dc", http.StatusBadRequest)
		return
	}

	select {
	case s.slots <- struct{}{}:
		defer func() { <-s.slots }()
	default:
		http.Error(w, "relay busy", http.StatusServiceUnavailable)
		return
	}

	dialer := net.Dialer{Timeout: upstreamDialTimeout, KeepAlive: 30 * time.Second}
	upstream, err := dialer.DialContext(r.Context(), "tcp", upstreamAddress)
	if err != nil {
		http.Error(w, "upstream unavailable", http.StatusBadGateway)
		return
	}
	defer upstream.Close()

	conn, err := acceptWebSocket(w, r, s.cfg.readLimit)
	if err != nil {
		return
	}
	defer conn.CloseNow()

	sessionID := s.sessions.Add(1)
	s.active.Add(1)
	defer s.active.Add(-1)
	started := time.Now()

	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	errCh := make(chan error, 2)
	var uploaded atomic.Int64
	var downloaded atomic.Int64

	go func() {
		errCh <- websocketToTCP(ctx, conn, upstream, &uploaded)
	}()
	go func() {
		errCh <- tcpToWebsocket(ctx, conn, upstream, &downloaded)
	}()

	firstErr := <-errCh
	cancel()
	_ = upstream.SetDeadline(time.Now())
	_ = upstream.Close()
	_ = conn.Close(websocket.StatusNormalClosure, "session complete")

	// Never log the token, query string, client IP, or Telegram payload.
	log.Printf(
		"session=%d dc=%d duration=%s up=%d down=%d result=%s",
		sessionID,
		dc,
		time.Since(started).Round(time.Millisecond),
		uploaded.Load(),
		downloaded.Load(),
		classifyBridgeError(firstErr),
	)
}

func acceptWebSocket(w http.ResponseWriter, r *http.Request, readLimit int64) (*websocket.Conn, error) {
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		Subprotocols:       []string{"binary"},
		InsecureSkipVerify: true, // Native app may omit Origin; relay auth protects /apiws.
		CompressionMode:    websocket.CompressionDisabled,
	})
	if err != nil {
		return nil, err
	}
	conn.SetReadLimit(readLimit)
	return conn, nil
}

func websocketToTCP(
	ctx context.Context,
	conn *websocket.Conn,
	upstream net.Conn,
	counter *atomic.Int64,
) error {
	for {
		messageType, payload, err := conn.Read(ctx)
		if err != nil {
			return err
		}
		if messageType != websocket.MessageBinary {
			return errors.New("non-binary WebSocket message")
		}
		if err := writeAll(upstream, payload); err != nil {
			return err
		}
		counter.Add(int64(len(payload)))
	}
}

func tcpToWebsocket(
	ctx context.Context,
	conn *websocket.Conn,
	upstream net.Conn,
	counter *atomic.Int64,
) error {
	buffer := make([]byte, 64*1024)
	for {
		n, err := upstream.Read(buffer)
		if n > 0 {
			if writeErr := conn.Write(ctx, websocket.MessageBinary, buffer[:n]); writeErr != nil {
				return writeErr
			}
			counter.Add(int64(n))
		}
		if err != nil {
			return err
		}
	}
}

func writeAll(writer io.Writer, payload []byte) error {
	for len(payload) > 0 {
		n, err := writer.Write(payload)
		if err != nil {
			return err
		}
		payload = payload[n:]
	}
	return nil
}

func (s *relayServer) authorized(r *http.Request) bool {
	provided := strings.TrimSpace(r.URL.Query().Get("token"))
	if header := strings.TrimSpace(r.Header.Get("Authorization")); strings.HasPrefix(header, "Bearer ") {
		provided = strings.TrimSpace(strings.TrimPrefix(header, "Bearer "))
	}
	if len(provided) != len(s.cfg.token) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(provided), []byte(s.cfg.token)) == 1
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

func notFound(w http.ResponseWriter, r *http.Request) {
	http.NotFound(w, r)
}

func classifyBridgeError(err error) string {
	if err == nil || errors.Is(err, io.EOF) || errors.Is(err, context.Canceled) {
		return "closed"
	}
	status := websocket.CloseStatus(err)
	if status == websocket.StatusNormalClosure || status == websocket.StatusGoingAway {
		return "closed"
	}
	return "error"
}

func envOr(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func envInt(name string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}
