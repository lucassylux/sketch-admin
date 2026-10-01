// sketch-admin HTTP 服务端：会话（cookie）+ RBAC 中间件 + 契约全量端点 + 可选静态 UI。
// 会话存内存（单实例部署；重启即全员重新登录，可接受），时长走 settings（对新登录生效）。
package admin

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"strings"
	"sync"
	"time"
)

const sessionCookie = "sk_admin_session"

// Server 管理后台服务
type Server struct {
	store *Store
	ui    fs.FS // 前端静态资源（nil 则只提供 API）

	mu       sync.Mutex
	sessions map[string]session
}

type session struct {
	User    User
	Expires time.Time
}

// NewServer 构造；ui 传 nil 则只提供 API
func NewServer(store *Store, ui fs.FS) *Server {
	return &Server{store: store, ui: ui, sessions: map[string]session{}}
}

// Handler 组装完整服务（含静态 UI）；纯 API 项目可用 Register 挂进自己的路由树
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	s.Register(mux)
	if s.ui != nil {
		mux.Handle("GET /", http.HandlerFunc(s.serveUI))
	}
	return s.withCommonHeaders(mux)
}

// Register 把契约全量端点注册到外部 mux（业务项目挂同树共用会话与安全头；
// 注意外部 mux 需自行套 withCommonHeaders，或用 Handler 组合）
func (s *Server) Register(mux *http.ServeMux) { s.registerExcluding(mux, "") }

// RegisterExcept 同 Register，但跳过指定方法+路径（宿主已有同名端点时避免冲突，
// 如 goose-backup 的富信息 /api/health）
func (s *Server) RegisterExcept(mux *http.ServeMux, skipMethodPath string) {
	s.registerExcluding(mux, skipMethodPath)
}

func (s *Server) registerExcluding(mux *http.ServeMux, skip string) {
	handle := func(methodPath string, h http.HandlerFunc) {
		if methodPath == skip {
			return
		}
		mux.HandleFunc(methodPath, h)
	}
	// 认证
	handle("GET /api/health", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	handle("POST /api/auth/login", s.handleLogin)
	handle("POST /api/auth/logout", s.handleLogout)
	handle("GET /api/auth/me", s.requireUI(s.handleMe))
	handle("POST /api/auth/password", s.requireUI(s.handleChangePassword))

	// SSO/OIDC（未配置即休眠）
	handle("GET /api/auth/oidc/config", s.handleOidcConfig)
	handle("GET /api/auth/oidc/login", s.handleOidcLogin)
	handle("POST /api/auth/oidc/callback", s.handleOidcCallback)
	handle("GET /api/auth/oidc/settings", s.requireRole("admin", s.handleOidcSettingsGet))
	handle("PUT /api/auth/oidc/settings", s.requireRole("admin", s.handleOidcSettingsPut))

	// 会话设置
	handle("GET /api/settings/session", s.requireRole("admin", s.handleSessionSettingsGet))
	handle("PUT /api/settings/session", s.requireRole("admin", s.handleSessionSettingsPut))

	// 数据字典
	handle("GET /api/dict-types", s.requireUI(s.handleListDictTypes))
	handle("GET /api/dicts", s.requireUI(s.handleListDicts))
	handle("POST /api/dicts", s.requireRole("editor", s.handleSaveDict))
	handle("DELETE /api/dicts/{id}", s.requireRole("editor", s.handleDeleteDict))

	// 审计与用户管理
	handle("GET /api/audit", s.requireRole("admin", s.handleAudit))
	handle("GET /api/users", s.requireRole("admin", s.handleListUsers))
	handle("POST /api/users", s.requireRole("admin", s.handleCreateUser))
	handle("PUT /api/users/{username}/role", s.requireRole("admin", s.handleUpdateUserRole))
	handle("POST /api/users/{username}/password", s.requireRole("admin", s.handleResetUserPassword))
	handle("DELETE /api/users/{username}", s.requireRole("admin", s.handleDeleteUser))

}

// ---------- 中间件 ----------

// withCommonHeaders 安全头 + 简易 CSRF 面：写请求带 Origin 且非同源即拒绝
func (s *Server) withCommonHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "same-origin")
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			if origin := r.Header.Get("Origin"); origin != "" && !sameOrigin(r, origin) {
				writeErr(w, http.StatusForbidden, "跨源写请求被拒绝")
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

func sameOrigin(r *http.Request, origin string) bool {
	host := r.Host
	if p := r.Header.Get("X-Forwarded-Host"); p != "" {
		host = p
	}
	scheme := "http"
	if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
		scheme = "https"
	}
	return origin == scheme+"://"+host
}

// requireUI 需登录会话（任何角色）
func (s *Server) requireUI(next func(w http.ResponseWriter, r *http.Request, u *User)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		u := s.sessionUser(r)
		if u == nil {
			writeErr(w, http.StatusUnauthorized, "未登录")
			return
		}
		next(w, r, u)
	}
}

// requireRole 需登录且角色满足（admin 全通过；editor 起 viewer 只读）
func (s *Server) requireRole(min string, next func(w http.ResponseWriter, r *http.Request, u *User)) http.HandlerFunc {
	rank := map[string]int{"viewer": 1, "editor": 2, "admin": 3}
	return s.requireUI(func(w http.ResponseWriter, r *http.Request, u *User) {
		if rank[u.Role] < rank[min] {
			writeErr(w, http.StatusForbidden, "角色权限不足（需要 "+min+"）")
			return
		}
		next(w, r, u)
	})
}

// ---------- 认证 ----------

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var body struct{ Username, Password string }
	if !readJSON(w, r, &body) {
		return
	}
	u, err := s.store.GetUser(body.Username)
	if err != nil || !bcryptCompare(u.PasswordHash, body.Password) {
		// 统一文案：防用户名枚举
		writeErr(w, http.StatusUnauthorized, "用户名或密码错误")
		return
	}
	s.store.TouchLogin(u.Username)
	s.issueSession(w, *u)
	writeJSON(w, http.StatusOK, map[string]any{"username": u.Username, "role": u.Role})
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(sessionCookie); err == nil {
		s.mu.Lock()
		delete(s.sessions, c.Value)
		s.mu.Unlock()
	}
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: "", Path: "/", MaxAge: -1})
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (s *Server) handleMe(w http.ResponseWriter, _ *http.Request, u *User) {
	writeJSON(w, http.StatusOK, u)
}

// handleChangePassword 本地账号自助改密；SSO 绑定用户明确拒绝
func (s *Server) handleChangePassword(w http.ResponseWriter, r *http.Request, u *User) {
	var body struct{ OldPassword, NewPassword string }
	if !readJSON(w, r, &body) {
		return
	}
	if u.Subject != "" {
		writeErr(w, http.StatusBadRequest, "SSO 账号无本地密码，请使用 SSO 登录")
		return
	}
	if len(body.NewPassword) < 8 {
		writeErr(w, http.StatusBadRequest, "新密码至少 8 位")
		return
	}
	current, err := s.store.GetUser(u.Username)
	if err != nil || !bcryptCompare(current.PasswordHash, body.OldPassword) {
		writeErr(w, http.StatusUnauthorized, "原密码错误")
		return
	}
	hash, err := bcryptHash(body.NewPassword)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if err := s.store.UpdatePassword(u.Username, hash); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	_ = s.store.Audit(u.Username, "update", "user/"+u.Username+"/password", "")
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// issueSession 签发会话 cookie（时长走 settings，对新登录生效）
func (s *Server) issueSession(w http.ResponseWriter, u User) {
	tok, err := randomHex(24)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	ttl := time.Duration(s.store.SessionTTLHours()) * time.Hour
	s.mu.Lock()
	s.sessions[tok] = session{User: u, Expires: time.Now().Add(ttl)}
	s.mu.Unlock()
	http.SetCookie(w, &http.Cookie{
		Name: sessionCookie, Value: tok, Path: "/", HttpOnly: true,
		SameSite: http.SameSiteLaxMode, MaxAge: int(ttl.Seconds()),
	})
}

// Authenticate 解析请求的会话用户（业务端点复用；未登录返回 nil）
func (s *Server) Authenticate(r *http.Request) *User { return s.sessionUser(r) }

// Audit 业务端点写审计（与 admin 面操作同一张审计表，前端「审计日志」页统一可见）
func (s *Server) Audit(actor, action, entity, detail string) {
	_ = s.store.Audit(actor, action, entity, detail)
}

// RequireUI 业务端点用的会话守卫（未登录 401 JSON，与契约错误格式一致）
func (s *Server) RequireUI(next func(w http.ResponseWriter, r *http.Request, u *User)) http.HandlerFunc {
	return s.requireUI(next)
}

func (s *Server) sessionUser(r *http.Request) *User {
	c, err := r.Cookie(sessionCookie)
	if err != nil {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	sess, ok := s.sessions[c.Value]
	if !ok || time.Now().After(sess.Expires) {
		return nil
	}
	return &sess.User
}

// ---------- 会话设置 ----------

func (s *Server) handleSessionSettingsGet(w http.ResponseWriter, _ *http.Request, _ *User) {
	writeJSON(w, http.StatusOK, map[string]any{"ttlHours": s.store.SessionTTLHours()})
}

func (s *Server) handleSessionSettingsPut(w http.ResponseWriter, r *http.Request, u *User) {
	var body struct{ TtlHours int }
	if !readJSON(w, r, &body) {
		return
	}
	if body.TtlHours < 1 || body.TtlHours > 168 {
		writeErr(w, http.StatusBadRequest, "会话时长需在 1-168 小时之间")
		return
	}
	s.store.SettingSet("session_ttl_hours", fmt.Sprint(body.TtlHours))
	_ = s.store.Audit(u.Username, "update", "settings/session", fmt.Sprintf("%dh", body.TtlHours))
	s.handleSessionSettingsGet(w, r, nil)
}

// ---------- 字典 ----------

func (s *Server) handleListDictTypes(w http.ResponseWriter, _ *http.Request, _ *User) {
	list, err := s.store.ListDictTypes()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if list == nil {
		list = []DictType{}
	}
	writeJSON(w, http.StatusOK, list)
}

func (s *Server) handleListDicts(w http.ResponseWriter, r *http.Request, _ *User) {
	t := r.URL.Query().Get("type")
	if t == "" {
		writeErr(w, http.StatusBadRequest, "type 必填")
		return
	}
	onlyEnabled := r.URL.Query().Get("enabled") == "true"
	list, err := s.store.ListDicts(t, onlyEnabled)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if list == nil {
		list = []DictItem{}
	}
	writeJSON(w, http.StatusOK, list)
}

func (s *Server) handleSaveDict(w http.ResponseWriter, r *http.Request, u *User) {
	var d DictItem
	if !readJSON(w, r, &d) {
		return
	}
	if err := s.store.SaveDict(&d, u.Username); err != nil {
		writeStoreErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, d)
}

func (s *Server) handleDeleteDict(w http.ResponseWriter, r *http.Request, u *User) {
	var id int64
	if _, err := fmt.Sscan(r.PathValue("id"), &id); err != nil {
		writeErr(w, http.StatusBadRequest, "id 非法")
		return
	}
	if err := s.store.DeleteDict(id); err != nil {
		writeStoreErr(w, err)
		return
	}
	_ = s.store.Audit(u.Username, "delete", fmt.Sprintf("dict/%d", id), "")
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// ---------- 审计 / 用户管理 ----------

func (s *Server) handleAudit(w http.ResponseWriter, _ *http.Request, _ *User) {
	list, err := s.store.ListAudit(500)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if list == nil {
		list = []AuditEntry{}
	}
	writeJSON(w, http.StatusOK, list)
}

func (s *Server) handleListUsers(w http.ResponseWriter, _ *http.Request, _ *User) {
	list, err := s.store.ListUsers()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if list == nil {
		list = []UserVO{}
	}
	writeJSON(w, http.StatusOK, list)
}

var validRoles = map[string]bool{"admin": true, "editor": true, "viewer": true}

func (s *Server) handleCreateUser(w http.ResponseWriter, r *http.Request, u *User) {
	var body struct{ Username, Password, Role string }
	if !readJSON(w, r, &body) {
		return
	}
	if body.Username == "" || len(body.Password) < 8 {
		writeErr(w, http.StatusBadRequest, "用户名必填且密码至少 8 位")
		return
	}
	if !validRoles[body.Role] {
		writeErr(w, http.StatusBadRequest, "角色需为 admin/editor/viewer")
		return
	}
	hash, err := bcryptHash(body.Password)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if err := s.store.CreateUser(body.Username, hash, body.Role); err != nil {
		writeErr(w, http.StatusConflict, "用户名已存在")
		return
	}
	_ = s.store.Audit(u.Username, "create", "user/"+body.Username, "role="+body.Role)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (s *Server) handleUpdateUserRole(w http.ResponseWriter, r *http.Request, u *User) {
	username := r.PathValue("username")
	var body struct{ Role string }
	if !readJSON(w, r, &body) {
		return
	}
	if !validRoles[body.Role] {
		writeErr(w, http.StatusBadRequest, "角色需为 admin/editor/viewer")
		return
	}
	// 最后一个 admin 不可降级
	if username == u.Username && u.Role == "admin" && body.Role != "admin" && s.store.AdminCount(username) == 0 {
		writeErr(w, http.StatusBadRequest, "不能降级最后一个管理员（含自己）")
		return
	}
	if err := s.store.UpdateRole(username, body.Role); err != nil {
		writeStoreErr(w, err)
		return
	}
	_ = s.store.Audit(u.Username, "update", "user/"+username+"/role", body.Role)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (s *Server) handleResetUserPassword(w http.ResponseWriter, r *http.Request, u *User) {
	username := r.PathValue("username")
	var body struct{ NewPassword string }
	if !readJSON(w, r, &body) {
		return
	}
	if len(body.NewPassword) < 8 {
		writeErr(w, http.StatusBadRequest, "新密码至少 8 位")
		return
	}
	hash, err := bcryptHash(body.NewPassword)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if err := s.store.UpdatePassword(username, hash); err != nil {
		writeStoreErr(w, err)
		return
	}
	_ = s.store.Audit(u.Username, "reset-password", "user/"+username, "")
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (s *Server) handleDeleteUser(w http.ResponseWriter, r *http.Request, u *User) {
	username := r.PathValue("username")
	if username == u.Username {
		writeErr(w, http.StatusBadRequest, "不能删除自己")
		return
	}
	if err := s.store.DeleteUser(username); err != nil {
		writeStoreErr(w, err)
		return
	}
	_ = s.store.Audit(u.Username, "delete", "user/"+username, "")
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// ---------- 静态 SPA ----------

func (s *Server) serveUI(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/")
	if path == "" {
		path = "index.html"
	}
	if _, err := fs.Stat(s.ui, path); err != nil {
		path = "index.html" // 前端路由回退
	}
	w.Header().Set("Cache-Control", "no-cache")
	http.ServeFileFS(w, r, s.ui, path)
}

// ---------- 助手 ----------

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

func writeStoreErr(w http.ResponseWriter, err error) {
	if errors.Is(err, ErrNotFound) {
		writeErr(w, http.StatusNotFound, "不存在")
		return
	}
	writeErr(w, http.StatusInternalServerError, err.Error())
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return false
	}
	if err := json.Unmarshal(body, v); err != nil {
		writeErr(w, http.StatusBadRequest, "请求体不是合法 JSON: "+err.Error())
		return false
	}
	return true
}

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}
