// Command relay — персональный WSS-релей между Android-приложением
// TG WS Proxy Lab и дата-центрами Telegram.
//
// Как это работает:
//
//	Telegram -> локальный MTProto в приложении -> WSS :443 -> Caddy -> этот релей -> TCP :443 -> DC Telegram
//
// Маршруты:
//
//	GET /healthz                     — «жив ли процесс» (JSON, без авторизации).
//	GET /readyz                      — TCP-проверка доступности DC (JSON, без авторизации).
//	GET /probe                       — WebSocket-handshake без подключения к Telegram.
//	GET /download?bytes=N            — отдаёт N случайных байт (проверка больших HTTPS-загрузок).
//	GET /probe-stream?bytes=N        — WebSocket, отдаёт N случайных байт и закрывается.
//	GET /probe-upload?bytes=N        — WebSocket, принимает N байт и закрывается.
//	GET /apiws?dc=N&token=...        — рабочий релей: WSS <-> TCP до DC N.
//
// /apiws принимает токен либо как query-параметр `token`, либо как
// заголовок `Authorization: Bearer <token>` (так его передаёт встроенная
// диагностика приложения). Релей подключается только к DC из белого списка,
// поэтому он не является универсальным открытым прокси.
//
// Содержимое MTProto не журналируется: в лог попадают только номер сессии,
// номер DC, длительность и счётчики байт.
package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
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
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/coder/websocket"
)

// version отдаётся в /healthz и /readyz; приложение показывает её в отчёте.
const version = "0.1.1"

const (
	defaultListenAddr     = ":8080"
	defaultMaxConnections = 256
	defaultWSReadLimit    = 2 << 20 // 2 MiB
	maxProbeBytes         = 100 << 20
	defaultProbeBytes     = 1 << 20
	probeHoldDuration     = 2 * time.Second
	dcDialTimeout         = 5 * time.Second
	readyzCacheTTL        = 5 * time.Second
	tunnelReadBuffer      = 32 << 10
	probeChunkSize        = 16 << 10
	minTokenLength        = 32
	maxTokenLength        = 256
)

// dcTargets — единственные адреса, куда релей имеет право подключаться.
// DC203 — медиа-маршрут (публичные каналы, видео, реакции, custom emoji).
var dcTargets = map[int]string{
	1:   "149.154.175.50:443",
	2:   "149.154.167.51:443",
	3:   "149.154.175.100:443",
	4:   "149.154.167.91:443",
	5:   "149.154.171.5:443",
	203: "91.105.192.100:443",
}

// dcOrder задаёт порядок проверки в /readyz.
var dcOrder = []int{1, 2, 3, 4, 5, 203}

// dialUpstream вынесен в переменную, чтобы тесты могли подменить DC на
// локальный слушатель.
var dialUpstream = func(ctx context.Context, network, address string) (net.Conn, error) {
	d := net.Dialer{Timeout: dcDialTimeout}
	return d.DialContext(ctx, network, address)
}

type config struct {
	listenAddr string
	token      string
	maxConns   int
	readLimit  int64
}

type server struct {
	cfg     config
	active  atomic.Int64
	sem     chan struct{}
	readyzM sync.Mutex
	readyzC *readyzResponse
	readyzT time.Time
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

func main() {
	cfg, err := loadConfig()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	srv := &server{cfg: cfg, sem: make(chan struct{}, cfg.maxConns)}

	httpServer := &http.Server{
		Addr:              cfg.listenAddr,
		Handler:           srv.handler(),
		ReadHeaderTimeout: 10 * time.Second,
		// Таймаутов на чтение/запись тела нет: это долгоживущие WSS-сессии.
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go func() {
		log.Printf("tg-relay %s listening on %s (max_connections=%d, ws_read_limit=%d)",
			version, cfg.listenAddr, cfg.maxConns, cfg.readLimit)
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("listen: %v", err)
		}
	}()

	<-ctx.Done()
	log.Printf("shutting down, active_sessions=%d", srv.active.Load())
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = httpServer.Shutdown(shutdownCtx)
}

func loadConfig() (config, error) {
	cfg := config{
		listenAddr: envString("LISTEN_ADDR", defaultListenAddr),
		token:      strings.TrimSpace(os.Getenv("RELAY_TOKEN")),
		maxConns:   envInt("MAX_CONNECTIONS", defaultMaxConnections),
		readLimit:  int64(envInt("WS_READ_LIMIT_BYTES", defaultWSReadLimit)),
	}
	if cfg.token == "" {
		return cfg, errors.New("RELAY_TOKEN is not set")
	}
	if len(cfg.token) < minTokenLength || len(cfg.token) > maxTokenLength {
		return cfg, fmt.Errorf("RELAY_TOKEN must be %d..%d characters (got %d); "+
			"the Android app only enables the relay for tokens of that length",
			minTokenLength, maxTokenLength, len(cfg.token))
	}
	if cfg.maxConns <= 0 {
		cfg.maxConns = defaultMaxConnections
	}
	if cfg.readLimit <= 0 {
		cfg.readLimit = defaultWSReadLimit
	}
	return cfg, nil
}

func envString(name, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(name)); v != "" {
		return v
	}
	return fallback
}

func envInt(name string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback
	}
	n, err := strconv.Atoi(raw)
	if err != nil {
		log.Printf("ignoring %s=%q: %v", name, raw, err)
		return fallback
	}
	return n
}

// ---------------------------------------------------------------------------
// Диагностика
// ---------------------------------------------------------------------------

// handler собирает все маршруты релея.
func (s *server) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", s.handleHealthz)
	mux.HandleFunc("/readyz", s.handleReadyz)
	mux.HandleFunc("/probe", s.handleProbe)
	mux.HandleFunc("/download", s.handleDownload)
	mux.HandleFunc("/probe-stream", s.handleProbeStream)
	mux.HandleFunc("/probe-upload", s.handleProbeUpload)
	mux.HandleFunc("/apiws", s.handleAPIWS)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "not found"})
	})
	return mux
}

type healthzResponse struct {
	Status         string `json:"status"`
	ActiveSessions int64  `json:"active_sessions"`
	Version        string `json:"version"`
}

func (s *server) handleHealthz(w http.ResponseWriter, r *http.Request) {
	if !requireGet(w, r) {
		return
	}
	writeJSON(w, http.StatusOK, healthzResponse{
		Status:         "ok",
		ActiveSessions: s.active.Load(),
		Version:        version,
	})
}

type readyzCheck struct {
	Name      string `json:"name"`
	Target    string `json:"target"`
	OK        bool   `json:"ok"`
	LatencyMS int64  `json:"latency_ms"`
	Error     string `json:"error,omitempty"`
}

type readyzResponse struct {
	Status  string        `json:"status"`
	Version string        `json:"version"`
	Checks  []readyzCheck `json:"checks"`
}

func (s *server) handleReadyz(w http.ResponseWriter, r *http.Request) {
	if !requireGet(w, r) {
		return
	}
	writeJSON(w, http.StatusOK, s.readyz(r.Context()))
}

func (s *server) readyz(ctx context.Context) readyzResponse {
	s.readyzM.Lock()
	defer s.readyzM.Unlock()
	if s.readyzC != nil && time.Since(s.readyzT) < readyzCacheTTL {
		return *s.readyzC
	}

	checks := make([]readyzCheck, 0, len(dcOrder))
	allOK := true
	for _, dc := range dcOrder {
		target := dcTargets[dc]
		check := readyzCheck{Name: fmt.Sprintf("dc%d", dc), Target: target}
		start := time.Now()
		conn, err := dialUpstream(ctx, "tcp", target)
		check.LatencyMS = time.Since(start).Milliseconds()
		if err != nil {
			check.Error = err.Error()
			allOK = false
		} else {
			check.OK = true
			_ = conn.Close()
		}
		checks = append(checks, check)
	}

	resp := readyzResponse{Status: "ok", Version: version, Checks: checks}
	if !allOK {
		resp.Status = "degraded"
	}
	s.readyzC, s.readyzT = &resp, time.Now()
	return resp
}

// handleProbe — публичный WebSocket-handshake. Приложение использует его,
// чтобы понять, доходит ли WSS до VPS в мобильной сети, ничего не отправляя
// в Telegram.
func (s *server) handleProbe(w http.ResponseWriter, r *http.Request) {
	if !requireGet(w, r) {
		return
	}
	ws, err := acceptWS(w, r, s.cfg.readLimit)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), probeHoldDuration)
	defer cancel()
	<-ctx.Done()
	closeGracefully(ws)
}

// handleDownload — предсказуемая HTTPS-загрузка N байт.
func (s *server) handleDownload(w http.ResponseWriter, r *http.Request) {
	if !requireGet(w, r) {
		return
	}
	n, err := parseBytesParam(r)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Length", strconv.FormatInt(n, 10))
	block := randomBlock(probeChunkSize)
	written := int64(0)
	for written < n {
		size := int64(len(block))
		if remaining := n - written; remaining < size {
			size = remaining
		}
		if _, err := w.Write(block[:size]); err != nil {
			return
		}
		written += size
	}
}

func (s *server) handleProbeStream(w http.ResponseWriter, r *http.Request) {
	if !requireGet(w, r) {
		return
	}
	n, err := parseBytesParam(r)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	ws, err := acceptWS(w, r, s.cfg.readLimit)
	if err != nil {
		return
	}
	ctx := context.Background()

	block := randomBlock(probeChunkSize)
	sent := int64(0)
	for sent < n {
		size := int64(len(block))
		if remaining := n - sent; remaining < size {
			size = remaining
		}
		writer, err := ws.Writer(ctx, websocket.MessageBinary)
		if err != nil {
			ws.CloseNow()
			return
		}
		if _, err := writer.Write(block[:size]); err != nil {
			_ = writer.Close()
			ws.CloseNow()
			return
		}
		if err := writer.Close(); err != nil {
			ws.CloseNow()
			return
		}
		sent += size
	}
	closeGracefully(ws)
}

func (s *server) handleProbeUpload(w http.ResponseWriter, r *http.Request) {
	if !requireGet(w, r) {
		return
	}
	n, err := parseBytesParam(r)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	// Большая upload-проверка приходит одним или несколькими сообщениями,
	// поэтому лимит на чтение здесь поднимаем только до размера проверки.
	limit := s.cfg.readLimit
	if n+int64(probeChunkSize) > limit {
		limit = n + int64(probeChunkSize)
	}
	ws, err := acceptWS(w, r, limit)
	if err != nil {
		return
	}
	ctx := context.Background()

	received := int64(0)
	for received < n {
		_, data, err := ws.Read(ctx)
		if err != nil {
			ws.CloseNow()
			return
		}
		received += int64(len(data))
	}
	closeGracefully(ws)
}

// ---------------------------------------------------------------------------
// Рабочий релей
// ---------------------------------------------------------------------------

func (s *server) handleAPIWS(w http.ResponseWriter, r *http.Request) {
	if !requireGet(w, r) {
		return
	}
	if !s.authorized(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}

	dc, err := strconv.Atoi(strings.TrimSpace(r.URL.Query().Get("dc")))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "dc must be an integer"})
		return
	}
	target, ok := dcTargets[dc]
	if !ok {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "dc is not in the relay whitelist"})
		return
	}

	select {
	case s.sem <- struct{}{}:
	default:
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "relay is at capacity"})
		return
	}
	defer func() { <-s.sem }()

	// Сначала дозваниваемся до DC: если Telegram недоступен с этого VPS,
	// клиент должен получить честный HTTP-отказ, а не оборванный WebSocket.
	upstream, err := dialUpstream(r.Context(), "tcp", target)
	if err != nil {
		log.Printf("dial dc%d %s failed: %v", dc, target, err)
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "telegram dc is unreachable"})
		return
	}
	defer upstream.Close()

	ws, err := acceptWS(w, r, s.cfg.readLimit)
	if err != nil {
		return
	}

	session := sessionID()
	s.active.Add(1)
	started := time.Now()
	log.Printf("session=%s dc=%d target=%s start", session, dc, target)

	up, down := tunnel(ws, upstream)

	s.active.Add(-1)
	log.Printf("session=%s dc=%d bytes_client_to_dc=%d bytes_dc_to_client=%d duration=%s",
		session, dc, up, down, time.Since(started).Round(time.Millisecond))
}

// tunnel перекладывает байты между WebSocket и TCP, пока одна из сторон не
// закроется. Возвращает объём трафика в обе стороны.
func tunnel(ws *websocket.Conn, upstream net.Conn) (clientToDC, dcToClient int64) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var up, down atomic.Int64
	var wg sync.WaitGroup

	// WebSocket -> TCP
	wg.Add(1)
	go func() {
		defer wg.Done()
		defer cancel()
		for {
			_, data, err := ws.Read(ctx)
			if err != nil {
				return
			}
			if len(data) == 0 {
				continue
			}
			if _, err := upstream.Write(data); err != nil {
				return
			}
			up.Add(int64(len(data)))
		}
	}()

	// TCP -> WebSocket
	wg.Add(1)
	go func() {
		defer wg.Done()
		defer cancel()
		buf := make([]byte, tunnelReadBuffer)
		for {
			n, err := upstream.Read(buf)
			if n > 0 {
				writer, werr := ws.Writer(ctx, websocket.MessageBinary)
				if werr != nil {
					return
				}
				if _, werr := writer.Write(buf[:n]); werr != nil {
					_ = writer.Close()
					return
				}
				if werr := writer.Close(); werr != nil {
					return
				}
				down.Add(int64(n))
			}
			if err != nil {
				return
			}
		}
	}()

	<-ctx.Done()
	_ = upstream.Close()
	closeGracefully(ws)
	wg.Wait()
	return up.Load(), down.Load()
}

// ---------------------------------------------------------------------------
// Хелперы
// ---------------------------------------------------------------------------

func (s *server) authorized(r *http.Request) bool {
	token := ""
	if header := r.Header.Get("Authorization"); header != "" {
		if len(header) > len("bearer ") && strings.EqualFold(header[:len("bearer ")], "bearer ") {
			token = strings.TrimSpace(header[len("bearer "):])
		}
	}
	if token == "" {
		token = r.URL.Query().Get("token")
	}
	if token == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(token), []byte(s.cfg.token)) == 1
}

func acceptWS(w http.ResponseWriter, r *http.Request, readLimit int64) (*websocket.Conn, error) {
	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		// Клиент — мобильное приложение, а не браузер: проверка Origin здесь
		// бессмысленна, доступ защищён токеном.
		InsecureSkipVerify: true,
		CompressionMode:    websocket.CompressionDisabled,
	})
	if err != nil {
		// Accept уже сам ответил клиенту кодом ошибки.
		log.Printf("ws accept failed: %v", err)
		return nil, err
	}
	ws.SetReadLimit(readLimit)
	return ws, nil
}

// closeGracefully отправляет close-frame, но не ждёт ответа дольше двух секунд.
func closeGracefully(ws *websocket.Conn) {
	done := make(chan struct{})
	go func() {
		_ = ws.Close(websocket.StatusNormalClosure, "")
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		ws.CloseNow()
	}
}

func requireGet(w http.ResponseWriter, r *http.Request) bool {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET")
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
		return false
	}
	return true
}

func parseBytesParam(r *http.Request) (int64, error) {
	raw := strings.TrimSpace(r.URL.Query().Get("bytes"))
	if raw == "" {
		return defaultProbeBytes, nil
	}
	n, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || n < 0 {
		return 0, errors.New("bytes must be a non-negative integer")
	}
	if n > maxProbeBytes {
		n = maxProbeBytes
	}
	return n, nil
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func randomBlock(size int) []byte {
	block := make([]byte, size)
	if _, err := io.ReadFull(rand.Reader, block); err != nil {
		// crypto/rand практически не может отказать; на всякий случай
		// отдаём детерминированный блок, чтобы проверка не падала.
		for i := range block {
			block[i] = byte(i)
		}
	}
	return block
}

func sessionID() string {
	raw := make([]byte, 8)
	if _, err := io.ReadFull(rand.Reader, raw); err != nil {
		return "unknown"
	}
	return hex.EncodeToString(raw)
}
