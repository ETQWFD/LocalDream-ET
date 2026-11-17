package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"embed"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"log"
	mathrand "math/rand"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

//go:embed web/admin.html
var adminFS embed.FS

var (
	version   = "2.3.0"
	buildInfo = "dev"
)

const (
	kindBadJSON  = "bad_json"
	kindBadReq   = "bad_request"
	kindCanceled = "canceled"
	kindUpstream = "upstream"
	kindTimeout  = "timeout"
	kindHTTP     = "upstream_http"
	kindDecode   = "decode"
)

type Config struct {
	Port         int     `json:"port"`
	InnerPort    int     `json:"inner_port"`
	APIKey       string  `json:"api_key"`
	EnableAPIKey bool    `json:"enable_api_key"`
	ModelPath    string  `json:"model_path"`
	EnginePath   string  `json:"engine_path"`
	Threads      int     `json:"threads"`
	Steps        int     `json:"steps"`
	CFG          float64 `json:"cfg_scale"`
	Width        int     `json:"width"`
	Height       int     `json:"height"`
	AutoStart    bool    `json:"autostart"`
	PrevKey      string  `json:"prev_key,omitempty"`
	PrevKeyUntil string  `json:"prev_key_until,omitempty"`
}

var (
	cfg     Config
	cfgPath string
	progDir string

	engineMu  sync.Mutex
	engineCmd *exec.Cmd
	engineRun bool

	genSlot = make(chan struct{}, 1)
	queueMu sync.Mutex
	queued  int

	busyMu sync.Mutex
	busy   bool

	cancelMu sync.Mutex
	cancelFn context.CancelFunc

	keyMu        sync.RWMutex
	prevKey      string
	prevKeyUntil time.Time

	adminHTML []byte

	readyMu   sync.Mutex
	lastReady bool
	lastCheck time.Time

	engineHTTP = &http.Client{
		Transport: &http.Transport{
			DialContext: (&net.Dialer{
				Timeout:   3 * time.Second,
				KeepAlive: 30 * time.Second,
			}).DialContext,
			MaxIdleConns:        8,
			MaxIdleConnsPerHost: 4,
			IdleConnTimeout:     90 * time.Second,
			DisableCompression:  true,
		},
	}
)

func main() {
	var configFlag string
	var showVersion bool
	flag.StringVar(&configFlag, "config", "", "path to server-go.json")
	flag.BoolVar(&showVersion, "version", false, "print version and exit")
	flag.Parse()
	if showVersion {
		fmt.Printf("Local Dream ET Server (Go) %s %s %s/%s\n", version, buildInfo, runtime.GOOS, runtime.GOARCH)
		return
	}

	exe, _ := os.Executable()
	exeDir := filepath.Dir(exe)
	base := filepath.Base(exeDir)
	if base == "bin" || base == "dist" {
		progDir = filepath.Dir(exeDir)
	} else {
		progDir = exeDir
	}
	if configFlag == "" {
		configFlag = filepath.Join(progDir, "config", "server-go.json")
	}
	cfgPath = configFlag
	if _, err := os.Stat(cfgPath); os.IsNotExist(err) {
		old := filepath.Join(exeDir, "config", "server-go.json")
		if b, err2 := os.ReadFile(old); err2 == nil {
			_ = os.MkdirAll(filepath.Dir(cfgPath), 0o755)
			_ = os.WriteFile(cfgPath, b, 0o644)
			log.Printf("已把配置从 %s 迁移到 %s", old, cfgPath)
		}
	}
	loadConfig()
	var err error
	adminHTML, err = adminFS.ReadFile("web/admin.html")
	if err != nil {
		log.Fatalf("缺少内嵌页面 web/admin.html: %v", err)
	}

	log.SetFlags(log.LstdFlags)
	log.Printf("Local Dream ET Server (Go) %s — http://0.0.0.0:%d", version, cfg.Port)
	log.Printf("API Key: %s (校验=%v)", maskKey(currentKey()), cfg.EnableAPIKey)
	if cfg.ModelPath != "" {
		log.Printf("使用模型: %s（如需更换请改 %s 的 model_path）", cfg.ModelPath, cfgPath)
	} else {
		log.Printf("未找到模型：请把 .safetensors/.gguf/.ckpt 放进 %s（支持子文件夹）", filepath.Join(progDir, "models"))
	}
	if cfg.EnginePath == "" {
		log.Printf("未找到 sd-server：请把 sd-server.exe 放到 %s", filepath.Join(progDir, "bin"))
	}

	go watchEngine()

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", handleHealth)
	mux.HandleFunc("/admin", handleAdminPage)
	mux.HandleFunc("/admin/", handleAdminPage)
	mux.HandleFunc("/admin/state", handleAdminState)
	mux.HandleFunc("/admin/generate", handleAdminGenerate)
	mux.HandleFunc("/admin/cancel", handleAdminCancel)
	mux.HandleFunc("/admin/key/rotate", handleKeyRotate)
	mux.HandleFunc("/v1/images/generations", handleOpenAI)
	mux.HandleFunc("/sdapi/v1/txt2img", handleSDAPI)
	mux.HandleFunc("/sdapi/v1/img2img", handleSDAPI)
	mux.HandleFunc("/api/txt2img", handleSDAPI)
	mux.HandleFunc("/api/img2img", handleSDAPI)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		http.Redirect(w, r, "/admin", http.StatusFound)
	})

	srv := &http.Server{
		Addr:              fmt.Sprintf("0.0.0.0:%d", cfg.Port),
		Handler:           logRequests(mux),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      0, // 出图可能几分钟，不能写超时
		IdleTimeout:       120 * time.Second,
	}
	if err := srv.ListenAndServe(); err != nil {
		log.Fatalf("监听 %d 失败: %v（端口被占用？请改 config 里的 port）", cfg.Port, err)
	}
}

func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: 200}
		next.ServeHTTP(sw, r)
		quiet := r.URL.Path == "/healthz" || r.URL.Path == "/admin/state"
		if !quiet || sw.status >= 400 {
			log.Printf("%s %s -> %d (%s)", r.Method, r.URL.Path, sw.status, time.Since(start).Round(time.Millisecond))
		}
	})
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) { w.status = code; w.ResponseWriter.WriteHeader(code) }
func (w *statusWriter) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func loadConfig() {
	if b, err := os.ReadFile(cfgPath); err == nil {
		if json.Unmarshal(b, &cfg) == nil && cfg.APIKey != "" {
			normalizeConfig()
			if cfg.PrevKey != "" {
				if t, err := time.Parse(time.RFC3339, cfg.PrevKeyUntil); err == nil {
					prevKey = cfg.PrevKey
					prevKeyUntil = t
				}
			}
			return
		}
		// 配置损坏：先尽量把旧 Key 抢救出来，避免所有客户端掉线
		if k := extractAPIKey(string(b)); k != "" {
			cfg = Config{Port: 8080, InnerPort: 17860, Threads: 2, Steps: 20, CFG: 7,
				Width: 512, Height: 512, AutoStart: true}
			cfg.APIKey = k
			cfg.ModelPath = detectModel()
			normalizeConfig()
			saveConfig()
			_ = os.WriteFile(cfgPath+".broken-"+time.Now().Format("20060102-150405"), b, 0o644)
			log.Printf("配置损坏，已保留原 API Key 并备份原文件")
			return
		}
	}
	cfg = Config{
		Port: 8080, InnerPort: 17860,
		EnableAPIKey: false,
		Threads:      runtime.NumCPU() / 2,
		Steps:        20, CFG: 7, Width: 512, Height: 512,
		AutoStart: true,
	}
	if cfg.Threads < 2 {
		cfg.Threads = 2
	}
	cfg.APIKey = genKey(46)
	cfg.ModelPath = detectModel()
	normalizeConfig()
	saveConfig()
	log.Printf("已生成配置: %s", cfgPath)
}

func extractAPIKey(s string) string {
	const key = "\"api_key\""
	i := strings.Index(s, key)
	if i < 0 {
		return ""
	}
	rest := s[i+len(key):]
	q := strings.Index(rest, "\"")
	if q < 0 {
		return ""
	}
	rest = rest[q+1:]
	end := strings.Index(rest, "\"")
	if end <= 0 {
		return ""
	}
	v := strings.TrimSpace(rest[:end])
	if len(v) >= 8 && !strings.ContainsAny(v, "{} :,") {
		return v
	}
	return ""
}

func normalizeConfig() {
	if cfg.Port == 0 {
		cfg.Port = 8080
	}
	if cfg.InnerPort == 0 {
		cfg.InnerPort = 17860
	}
	if cfg.ModelPath == "" {
		cfg.ModelPath = detectModel()
	}
	if cfg.EnginePath == "" {
		cfg.EnginePath = detectEngine()
	}
	if cfg.Threads <= 0 {
		cfg.Threads = 2
	}
	if cfg.Steps <= 0 {
		cfg.Steps = 20
	}
	cfg.Steps = clampInt(cfg.Steps, 1, 150)
	if cfg.CFG < 1 || cfg.CFG > 30 {
		cfg.CFG = 7
	}
	if cfg.Width <= 0 {
		cfg.Width = 512
	}
	if cfg.Height <= 0 {
		cfg.Height = 512
	}
	cfg.Width = round8(clampInt(cfg.Width, 64, 2048))
	cfg.Height = round8(clampInt(cfg.Height, 64, 2048))
}

func saveConfig() {
	_ = os.MkdirAll(filepath.Dir(cfgPath), 0o755)
	keyMu.RLock()
	snap := cfg
	keyMu.RUnlock()
	b, _ := json.MarshalIndent(snap, "", "  ")
	tmp := cfgPath + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		log.Printf("写入配置失败: %v", err)
		return
	}
	if err := os.Rename(tmp, cfgPath); err != nil {
		log.Printf("替换配置失败: %v", err)
	}
}

func detectModel() string {
	dir := filepath.Join(progDir, "models")
	var found string
	_ = filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil || d == nil || d.IsDir() || found != "" {
			return nil
		}
		n := strings.ToLower(d.Name())
		if strings.HasSuffix(n, ".safetensors") || strings.HasSuffix(n, ".gguf") || strings.HasSuffix(n, ".ckpt") {
			found = p
		}
		return nil
	})
	return found
}

func detectEngine() string {
	names := []string{"sd-server", "sd-server.exe", "stable-diffusion", "sd"}
	dirs := []string{filepath.Join(progDir, "bin"), progDir}
	var found string
	for _, n := range names {
		for _, d := range dirs {
			p := filepath.Join(d, n)
			if st, err := os.Stat(p); err == nil && !st.IsDir() {
				return p
			}
		}
	}
	_ = filepath.WalkDir(progDir, func(p string, d fs.DirEntry, err error) error {
		if err != nil || d == nil || d.IsDir() || found != "" {
			return nil
		}
		n := strings.ToLower(d.Name())
		if n == "sd-server.exe" || n == "sd-server" {
			found = p
		}
		return nil
	})
	return found
}

func genKey(n int) string {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		log.Printf("crypto/rand 失败，退回时间种子: %v", err)
		r := mathrand.New(mathrand.NewSource(time.Now().UnixNano()))
		for i := range b {
			b[i] = alphabet[r.Intn(len(alphabet))]
		}
		return string(b)
	}
	for i := range b {
		b[i] = alphabet[int(b[i])%len(alphabet)]
	}
	return string(b)
}

func randomSeed() int64 {
	var b [8]byte
	if _, err := rand.Read(b[:]); err == nil {
		v := int64(b[0])<<56 | int64(b[1])<<48 | int64(b[2])<<40 | int64(b[3])<<32 |
			int64(b[4])<<24 | int64(b[5])<<16 | int64(b[6])<<8 | int64(b[7])
		v &= 0x7fffffffffffffff
		return v
	}
	return mathrand.Int63()
}

func currentKey() string {
	keyMu.RLock()
	defer keyMu.RUnlock()
	return cfg.APIKey
}

func currentPrevKey() (string, time.Time) {
	keyMu.RLock()
	defer keyMu.RUnlock()
	return prevKey, prevKeyUntil
}

func rotateKey() (string, time.Time) {
	keyMu.Lock()
	defer keyMu.Unlock()
	prevKey = cfg.APIKey
	prevKeyUntil = time.Now().Add(10 * time.Minute)
	cfg.APIKey = genKey(46)
	cfg.PrevKey = prevKey
	cfg.PrevKeyUntil = prevKeyUntil.Format(time.RFC3339)
	return cfg.APIKey, prevKeyUntil
}

// extractAPIKey 是启发式救援：只适用于本程序生成的 46 位字母数字 Key，
// 用于配置 JSON 损坏时避免重新生成 Key 把客户端全部踢下线。
func modelName() string {
	if cfg.ModelPath == "" {
		return "未配置"
	}
	return filepath.Base(cfg.ModelPath)
}

func maskKey(k string) string {
	if len(k) <= 10 {
		return "****"
	}
	return k[:6] + "…" + k[len(k)-4:]
}

/* ---------------- engine ---------------- */

func upstreamReady() bool {
	c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", cfg.InnerPort), 800*time.Millisecond)
	if err != nil {
		return false
	}
	_ = c.Close()
	return true
}

// cachedReady 供 /healthz 用：1 秒内复用探测结果，避免高频轮询反复 Dial。
// 引擎生死判断请用 upstreamReady()（无缓存）。
func cachedReady() bool {
	readyMu.Lock()
	defer readyMu.Unlock()
	if time.Since(lastCheck) < time.Second {
		return lastReady
	}
	lastReady = upstreamReady()
	lastCheck = time.Now()
	return lastReady
}

func watchEngine() {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		if !upstreamReady() && cfg.AutoStart {
			engineMu.Lock()
			if !engineRun {
				startEngineLocked()
			}
			engineMu.Unlock()
		}
		<-ticker.C
	}
}

func absPath(p string) string {
	if p == "" || filepath.IsAbs(p) {
		return p
	}
	return filepath.Join(progDir, p)
}

func startEngineLocked() {
	if cfg.ModelPath == "" || cfg.EnginePath == "" {
		log.Printf("引擎未启动：缺少 model_path 或 engine_path（见 %s）", cfgPath)
		return
	}
	args := []string{
		"--model", absPath(cfg.ModelPath),
		"--listen-ip", "127.0.0.1",
		"--listen-port", fmt.Sprint(cfg.InnerPort),
		"--threads", fmt.Sprint(cfg.Threads),
	}
	cmd := exec.Command(absPath(cfg.EnginePath), args...)
	cmd.Dir = progDir
	logDir := filepath.Join(progDir, "logs")
	_ = os.MkdirAll(logDir, 0o755)
	engineLog, logErr := os.OpenFile(filepath.Join(logDir, "engine.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if logErr != nil {
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
	} else {
		w := io.MultiWriter(os.Stdout, engineLog)
		cmd.Stdout = w
		cmd.Stderr = w
	}
	if err := cmd.Start(); err != nil {
		log.Printf("拉起引擎失败: %v", err)
		return
	}
	engineCmd = cmd
	engineRun = true
	log.Printf("引擎已启动 pid=%d: %s %s", cmd.Process.Pid, cfg.EnginePath, strings.Join(args, " "))
	go func() {
		err := cmd.Wait()
		log.Printf("引擎退出: %v", err)
		engineMu.Lock()
		engineRun = false
		engineCmd = nil
		engineMu.Unlock()
	}()
}

/* ---------------- generation ---------------- */

type GenRequest struct {
	Prompt     string   `json:"prompt"`
	Negative   string   `json:"negative_prompt"`
	Steps      int      `json:"steps"`
	CFG        float64  `json:"cfg_scale"`
	Seed       int64    `json:"seed"`
	Width      int      `json:"width"`
	Height     int      `json:"height"`
	Sampler    string   `json:"sampler_name"`
	InitImages []string `json:"init_images,omitempty"`
	Denoising  *float64 `json:"denoising_strength,omitempty"`
}

type GenError struct {
	Kind       string `json:"kind"`
	Message    string `json:"message"`
	HTTPStatus int    `json:"http_status,omitempty"`
	Upstream   string `json:"upstream,omitempty"`
	Hint       string `json:"hint,omitempty"`
}

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func round8(v int) int { return (v + 7) / 8 * 8 }

func (r *GenRequest) clamp() {
	r.Width = round8(clampInt(r.Width, 64, 2048))
	r.Height = round8(clampInt(r.Height, 64, 2048))
	r.Steps = clampInt(r.Steps, 1, 150)
	if r.CFG < 1 {
		r.CFG = 1
	}
	if r.CFG > 30 {
		r.CFG = 30
	}
	if r.Sampler == "" {
		r.Sampler = "dpm++2m"
	}
	if r.Denoising != nil {
		v := *r.Denoising
		if v > 1 {
			v = 1
		}
		r.Denoising = &v
	}
}

func markQueuedInc() {
	queueMu.Lock()
	queued++
	queueMu.Unlock()
}

func markQueuedDec() {
	queueMu.Lock()
	if queued > 0 {
		queued--
	}
	queueMu.Unlock()
}

func generate(parent context.Context, req GenRequest, timeout time.Duration) (string, time.Duration, *GenError) {
	start := time.Now()
	markQueuedInc()

	select {
	case genSlot <- struct{}{}:
		markQueuedDec()
		defer func() { <-genSlot }()
	case <-parent.Done():
		markQueuedDec()
		return "", time.Since(start), &GenError{Kind: kindCanceled, Message: "请求在排队时被取消"}
	}

	busyMu.Lock()
	busy = true
	busyMu.Unlock()
	defer func() {
		busyMu.Lock()
		busy = false
		busyMu.Unlock()
	}()

	if req.Steps <= 0 {
		req.Steps = cfg.Steps
	}
	if req.CFG <= 0 {
		req.CFG = cfg.CFG
	}
	if req.Width <= 0 {
		req.Width = cfg.Width
	}
	if req.Height <= 0 {
		req.Height = cfg.Height
	}
	req.clamp()
	if req.Seed == 0 {
		req.Seed = randomSeed()
	}

	if len(req.InitImages) > 0 && req.Denoising != nil && *req.Denoising <= 0 {
		return "", time.Since(start), &GenError{
			Kind: kindBadReq, Message: "denoising_strength 必须大于 0（0 等于不改变原图、不会出图）",
			Hint: "想几乎不改原图请用 0.05~0.15；想重新画请 0.5~0.8 或改用 txt2img。",
		}
	}

	endpoint := "/sdapi/v1/txt2img"
	payload := map[string]any{
		"prompt":          req.Prompt,
		"negative_prompt": req.Negative,
		"steps":           req.Steps,
		"cfg_scale":       req.CFG,
		"seed":            req.Seed,
		"width":           req.Width,
		"height":          req.Height,
		"sampler_name":    req.Sampler,
		"batch_size":      1,
		"n_iter":          1,
		"send_images":     true,
	}
	if len(req.InitImages) > 0 {
		endpoint = "/sdapi/v1/img2img"
		payload["init_images"] = req.InitImages
		strength := 0.65
		if req.Denoising != nil {
			strength = *req.Denoising
		}
		payload["denoising_strength"] = strength
	}

	body, _ := json.Marshal(payload)
	url := fmt.Sprintf("http://127.0.0.1:%d%s", cfg.InnerPort, endpoint)
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()

	cancelMu.Lock()
	cancelFn = cancel
	cancelMu.Unlock()
	defer func() {
		cancelMu.Lock()
		cancelFn = nil
		cancelMu.Unlock()
	}()

	hreq, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return "", time.Since(start), &GenError{Kind: kindBadReq, Message: err.Error()}
	}
	hreq.Header.Set("Content-Type", "application/json")

	done := make(chan struct{})
	go func() {
		defer func() { _ = recover() }()
		t := time.NewTicker(10 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-done:
				return
			case <-t.C:
				log.Printf("出图进行中… 已用 %s（步骤 %d，%dx%d）", time.Since(start).Round(time.Second), req.Steps, req.Width, req.Height)
			}
		}
	}()

	resp, err := engineHTTP.Do(hreq)
	close(done)
	if err != nil {
		kind := kindUpstream
		hint := "引擎没有响应：确认 sd-server 正在运行、端口正确、模型已完整下载。"
		if errors.Is(err, context.DeadlineExceeded) || strings.Contains(err.Error(), "deadline") {
			kind = kindTimeout
			hint = "超过设定时间仍无结果：降低步数/尺寸，或检查显存/内存是否不足。"
		} else if errors.Is(err, context.Canceled) {
			kind = kindCanceled
			hint = "出图已被取消。"
		}
		return "", time.Since(start), &GenError{Kind: kind, Message: err.Error(), Hint: hint}
	}
	defer resp.Body.Close()

	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if resp.StatusCode != http.StatusOK {
		return "", time.Since(start), &GenError{
			Kind: kindHTTP, Message: fmt.Sprintf("引擎返回 HTTP %d", resp.StatusCode),
			HTTPStatus: resp.StatusCode, Upstream: truncate(string(raw), 1200),
			Hint: "把这段错误复制给开发者；常见原因：模型格式不支持、参数越界、显存不足。",
		}
	}
	var out struct {
		Images []string `json:"images"`
		Info   string   `json:"info"`
	}
	if err := json.Unmarshal(raw, &out); err != nil || len(out.Images) == 0 {
		return "", time.Since(start), &GenError{
			Kind: kindDecode, Message: "引擎没有返回图片", Upstream: truncate(string(raw), 1200),
			Hint: "把这段错误复制给开发者，通常是模型损坏或引擎版本不匹配。",
		}
	}
	img := out.Images[0]
	if !strings.HasPrefix(img, "data:") {
		if _, err := base64.StdEncoding.DecodeString(img); err != nil {
			return "", time.Since(start), &GenError{Kind: kindDecode, Message: "图片 base64 解码失败", Upstream: truncate(img, 200)}
		}
		img = "data:image/png;base64," + img
	}
	return img, time.Since(start), nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	cut := n
	for cut > 0 && !utf8.RuneStart(s[cut]) {
		cut--
	}
	return s[:cut] + "…"
}

/* ---------------- helpers ---------------- */

func isLocalhost(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func localAddrs() []string {
	var out []string
	ifaces, _ := net.Interfaces()
	for _, i := range ifaces {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := i.Addrs()
		for _, a := range addrs {
			if ip, ok := a.(*net.IPNet); ok && ip.IP.To4() != nil {
				out = append(out, fmt.Sprintf("http://%s:%d", ip.IP.String(), cfg.Port))
			}
		}
	}
	if len(out) == 0 {
		out = append(out, fmt.Sprintf("http://127.0.0.1:%d", cfg.Port))
	}
	return out
}

func engineStatus() map[string]any {
	busyMu.Lock()
	b := busy
	busyMu.Unlock()
	queueMu.Lock()
	q := queued
	queueMu.Unlock()
	engineMu.Lock()
	run := engineRun
	engineMu.Unlock()
	return map[string]any{
		"engine_ready": cachedReady(),
		"engine_run":   run,
		"busy":         b,
		"queued":       q,
		"model":        modelName(),
		"model_path":   cfg.ModelPath,
		"port":         cfg.Port,
	}
}

/* ---------------- handlers ---------------- */

func handleHealth(w http.ResponseWriter, r *http.Request) {
	st := engineStatus()
	st["status"] = "ok"
	writeJSON(w, 200, st)
}

func handleAdminPage(w http.ResponseWriter, r *http.Request) {
	b := adminHTML
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(b)
}

func handleAdminState(w http.ResponseWriter, r *http.Request) {
	st := engineStatus()
	st["addresses"] = localAddrs()
	if isLocalhost(r) {
		st["api_key"] = currentKey()
		st["key_visible"] = true
	} else {
		st["api_key"] = maskKey(currentKey())
		st["key_visible"] = false
	}
	st["enable_api_key"] = cfg.EnableAPIKey
	st["engine"] = cfg.EnginePath
	st["steps"] = cfg.Steps
	st["cfg"] = cfg.CFG
	st["width"] = cfg.Width
	st["height"] = cfg.Height
	st["threads"] = cfg.Threads
	writeJSON(w, 200, st)
}

func handleKeyRotate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "POST only"})
		return
	}
	if !isLocalhost(r) {
		writeJSON(w, 403, map[string]any{"error": "forbidden: 只允许本机操作"})
		return
	}
	newKey, until := rotateKey()
	saveConfig()
	writeJSON(w, 200, map[string]any{"api_key": newKey, "old_key_valid_until": until.Format(time.RFC3339)})
}

func handleAdminCancel(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "POST only"})
		return
	}
	cancelMu.Lock()
	fn := cancelFn
	cancelMu.Unlock()
	if fn == nil {
		writeJSON(w, 200, map[string]any{"ok": false, "message": "当前没有进行中的出图"})
		return
	}
	fn()
	writeJSON(w, 200, map[string]any{"ok": true, "message": "已取消"})
}

func handleAdminGenerate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"ok": false, "error": GenError{Kind: kindBadReq, Message: "POST only"}})
		return
	}
	var req GenRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 4<<20)).Decode(&req); err != nil {
		writeJSON(w, 400, map[string]any{"ok": false, "error": GenError{Kind: kindBadJSON, Message: "请求不是合法 JSON: " + err.Error()}})
		return
	}
	req.Prompt = strings.TrimSpace(req.Prompt)
	if req.Prompt == "" {
		writeJSON(w, 400, map[string]any{"ok": false, "error": GenError{Kind: kindBadReq, Message: "正向提示词不能为空"}})
		return
	}
	log.Printf("收到出图请求: prompt=%q steps=%d %dx%d", truncate(req.Prompt, 40), req.Steps, req.Width, req.Height)
	img, took, gerr := generate(r.Context(), req, 10*time.Minute)
	if gerr != nil {
		writeJSON(w, 200, map[string]any{"ok": false, "error": gerr, "elapsed_ms": took.Milliseconds()})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "image": img, "elapsed_ms": took.Milliseconds()})
}

func apiAuthorized(r *http.Request) bool {
	if !cfg.EnableAPIKey {
		return true
	}
	cur := currentKey()
	authHeader := r.Header.Get("Authorization")
	xkey := strings.TrimSpace(r.Header.Get("X-API-Key"))
	qkey := r.URL.Query().Get("api_key")
	bearer := ""
	if strings.HasPrefix(authHeader, "Bearer ") {
		bearer = strings.TrimSpace(authHeader[7:])
	}
	if bearer == cur || xkey == cur || qkey == cur {
		return true
	}
	if pk, until := currentPrevKey(); pk != "" && time.Now().Before(until) {
		if bearer == pk || xkey == pk || qkey == pk {
			return true
		}
	}
	return false
}

func handleOpenAI(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeOpenAIError(w, 405, kindBadReq, "POST only")
		return
	}
	if !apiAuthorized(r) {
		writeOpenAIError(w, 401, "auth", "invalid api key")
		return
	}
	var in struct {
		Prompt         string `json:"prompt"`
		NegativePrompt string `json:"negative_prompt"`
		N              int    `json:"n"`
		Size           string `json:"size"`
		Steps          int    `json:"steps"`
		ResponseFormat string `json:"response_format"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&in); err != nil {
		writeOpenAIError(w, 400, kindBadJSON, err.Error())
		return
	}
	w2, h2 := cfg.Width, cfg.Height
	if s := strings.Split(strings.ToLower(in.Size), "x"); len(s) == 2 {
		if v, err := strconv.Atoi(strings.TrimSpace(s[0])); err == nil {
			w2 = v
		}
		if v, err := strconv.Atoi(strings.TrimSpace(s[1])); err == nil {
			h2 = v
		}
	}
	req := GenRequest{Prompt: in.Prompt, Negative: in.NegativePrompt, Steps: in.Steps, Width: w2, Height: h2}
	n := clampInt(in.N, 1, 4) // 引擎侧一次一张，n>1 时串行出多张
	data := make([]map[string]string, 0, n)
	for i := 0; i < n; i++ {
		img, _, gerr := generate(r.Context(), req, 10*time.Minute)
		if gerr != nil {
			writeOpenAIError(w, 502, gerr.Kind, gerr.Message+" | "+gerr.Hint)
			return
		}
		data = append(data, map[string]string{"b64_json": strings.TrimPrefix(img, "data:image/png;base64,")})
	}
	writeJSON(w, 200, map[string]any{"created": time.Now().Unix(), "data": data})
}

func writeOpenAIError(w http.ResponseWriter, code int, typ, msg string) {
	writeJSON(w, code, map[string]any{"error": map[string]any{"message": msg, "type": typ}})
}

func handleSDAPI(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "POST only"})
		return
	}
	if !apiAuthorized(r) {
		writeJSON(w, 401, map[string]any{"error": "invalid api key"})
		return
	}
	var req GenRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 8<<20)).Decode(&req); err != nil {
		writeJSON(w, 400, map[string]any{"images": []string{}, "error": GenError{Kind: kindBadJSON, Message: err.Error()}})
		return
	}
	img2imgPath := strings.Contains(r.URL.Path, "img2img")
	if img2imgPath && len(req.InitImages) == 0 {
		writeJSON(w, 400, map[string]any{
			"images": []string{},
			"error": GenError{Kind: kindBadReq, Message: "img2img 需要 init_images（base64 图片数组）；纯文字请用 /sdapi/v1/txt2img",
				Hint: "如果只想文生图，把请求发到 txt2img 接口。"},
		})
		return
	}
	img, took, gerr := generate(r.Context(), req, 10*time.Minute)
	if gerr != nil {
		writeJSON(w, 500, map[string]any{"images": []string{}, "error": gerr, "elapsed_ms": took.Milliseconds()})
		return
	}
	b64 := strings.TrimPrefix(img, "data:image/png;base64,")
	writeJSON(w, 200, map[string]any{"images": []string{b64}, "info": fmt.Sprintf(`{"elapsed_ms":%d}`, took.Milliseconds())})
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
