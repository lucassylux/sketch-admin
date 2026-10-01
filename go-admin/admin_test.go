package admin

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

// newTestServer 每用例独立临时库 + 已登录 admin 的客户端封装
type testApp struct {
	t       *testing.T
	srv     *httptest.Server
	store   *Store
	adminPw string
	session *http.Client
}

func newTestServer(t *testing.T) *testApp {
	t.Helper()
	store, adminPw, err := Open(filepath.Join(t.TempDir(), "admin.db"), "")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if adminPw == "" {
		t.Fatal("首启应返回一次性初始口令")
	}
	srv := httptest.NewServer(NewServer(store, nil).Handler())
	t.Cleanup(func() { srv.Close(); _ = store.Close() })

	jar, _ := cookiejar.New(nil)
	app := &testApp{t: t, srv: srv, store: store, adminPw: adminPw, session: &http.Client{Jar: jar}}
	app.login("admin", adminPw)
	return app
}

func (a *testApp) login(username, password string) {
	a.t.Helper()
	body, _ := json.Marshal(map[string]string{"username": username, "password": password})
	resp, err := a.session.Post(a.srv.URL+"/api/auth/login", "application/json", bytes.NewReader(body))
	if err != nil {
		a.t.Fatalf("login: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		a.t.Fatalf("login 状态码 %d", resp.StatusCode)
	}
}

func (a *testApp) do(method, path string, body any) (int, map[string]any) {
	a.t.Helper()
	var rd *bytes.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	} else {
		rd = bytes.NewReader(nil)
	}
	req, _ := http.NewRequest(method, a.srv.URL+path, rd)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := a.session.Do(req)
	if err != nil {
		a.t.Fatalf("%s %s: %v", method, path, err)
	}
	defer resp.Body.Close()
	var m map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&m)
	return resp.StatusCode, m
}

func (a *testApp) getArray(path string) []map[string]any {
	a.t.Helper()
	req, _ := http.NewRequest(http.MethodGet, a.srv.URL+path, nil)
	resp, err := a.session.Do(req)
	if err != nil {
		a.t.Fatalf("GET %s: %v", path, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		a.t.Fatalf("GET %s: %d", path, resp.StatusCode)
	}
	var out []map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		a.t.Fatalf("GET %s 解码: %v", path, err)
	}
	return out
}

func TestHealthAndAuth(t *testing.T) {
	app := newTestServer(t)
	// health 公开
	resp, _ := http.Get(app.srv.URL + "/api/health")
	if resp.StatusCode != 200 {
		t.Fatalf("health: %d", resp.StatusCode)
	}
	resp.Body.Close()
	// 未登录 401
	req, _ := http.NewRequest(http.MethodGet, app.srv.URL+"/api/auth/me", nil)
	resp2, _ := http.DefaultClient.Do(req)
	if resp2.StatusCode != 401 {
		t.Fatalf("未登录 me 应 401: %d", resp2.StatusCode)
	}
	resp2.Body.Close()
	// 登录后 me
	code, m := app.do("GET", "/api/auth/me", nil)
	if code != 200 || m["role"] != "admin" {
		t.Fatalf("me: %d %v", code, m)
	}
	// 错误口令统一文案
	jar, _ := cookiejar.New(nil)
	c := &http.Client{Jar: jar}
	body, _ := json.Marshal(map[string]string{"username": "admin", "password": "x"})
	resp3, _ := c.Post(app.srv.URL+"/api/auth/login", "application/json", bytes.NewReader(body))
	var em map[string]any
	_ = json.NewDecoder(resp3.Body).Decode(&em)
	resp3.Body.Close()
	if resp3.StatusCode != 401 || em["error"] != "用户名或密码错误" {
		t.Fatalf("错误口令: %d %v", resp3.StatusCode, em)
	}
}

func TestDictEndpoints(t *testing.T) {
	app := newTestServer(t)
	types := app.getArray("/api/dict-types")
	if len(types) != 1 || types[0]["name"] != "规则严重级" {
		t.Fatalf("内置字典类型: %v", types)
	}
	items := app.getArray("/api/dicts?type=rule-severity")
	if len(items) != 4 || items[0]["label"] != "严重" {
		t.Fatalf("严重级种子: %v", items)
	}
	code, m := app.do("POST", "/api/dicts", map[string]any{"type": "demo", "label": "显示", "value": "show", "sort": 1, "enabled": true})
	if code != 200 {
		t.Fatalf("新增字典: %d %v", code, m)
	}
	// 同类型 value 唯一
	code, _ = app.do("POST", "/api/dicts", map[string]any{"type": "demo", "label": "又", "value": "show", "sort": 2, "enabled": true})
	if code == 200 {
		t.Fatal("重复 value 不应成功")
	}
	// enabled 过滤
	app.do("POST", "/api/dicts", map[string]any{"type": "demo", "label": "隐藏", "value": "hide", "sort": 3, "enabled": false})
	demo := app.getArray("/api/dicts?type=demo&enabled=true")
	if len(demo) != 1 {
		t.Fatalf("enabled 过滤: %v", demo)
	}
}

func TestUserManagement(t *testing.T) {
	app := newTestServer(t)
	code, m := app.do("POST", "/api/users", map[string]any{"username": "alice", "password": "Alice#2026", "role": "editor"})
	if code != 200 {
		t.Fatalf("建用户: %d %v", code, m)
	}
	users := app.getArray("/api/users")
	if len(users) != 2 {
		t.Fatalf("用户列表: %v", users)
	}
	// 短密码拒绝
	code, _ = app.do("POST", "/api/users", map[string]any{"username": "bob", "password": "short", "role": "viewer"})
	if code != 400 {
		t.Fatal("短密码应 400")
	}
	// 角色调整 + 最后一个 admin 保护
	code, _ = app.do("PUT", "/api/users/admin/role", map[string]any{"role": "viewer"})
	if code == 200 {
		t.Fatal("降级唯一 admin 应被拒")
	}
	code, _ = app.do("PUT", "/api/users/alice/role", map[string]any{"role": "viewer"})
	if code != 200 {
		t.Fatal("调整 alice 角色失败")
	}
	// 删除自己拒绝
	code, _ = app.do("DELETE", "/api/users/admin", nil)
	if code == 200 {
		t.Fatal("删除自己应被拒")
	}
	// 管理员重置口令后可登录
	code, _ = app.do("POST", "/api/users/alice/password", map[string]any{"newPassword": "Reset#2026"})
	if code != 200 {
		t.Fatal("重置口令失败")
	}
	jar, _ := cookiejar.New(nil)
	c := &http.Client{Jar: jar}
	body, _ := json.Marshal(map[string]string{"username": "alice", "password": "Reset#2026"})
	resp, err := c.Post(app.srv.URL+"/api/auth/login", "application/json", bytes.NewReader(body))
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("alice 新口令登录: %v %v", resp, err)
	}
	resp.Body.Close()
}

func TestChangePasswordAndSessionSettings(t *testing.T) {
	app := newTestServer(t)
	_, m := app.do("GET", "/api/settings/session", nil)
	if m["ttlHours"] != float64(12) {
		t.Fatalf("默认会话时长: %v", m["ttlHours"])
	}
	if code, _ := app.do("PUT", "/api/settings/session", map[string]any{"ttlHours": 300}); code != 400 {
		t.Fatal("越界应 400")
	}
	if code, _ := app.do("PUT", "/api/settings/session", map[string]any{"ttlHours": 24}); code != 200 {
		t.Fatal("保存失败")
	}
	_, m = app.do("GET", "/api/settings/session", nil)
	if m["ttlHours"] != float64(24) {
		t.Fatalf("未持久化: %v", m)
	}

	if code, _ := app.do("POST", "/api/auth/password", map[string]any{"oldPassword": "wrong", "newPassword": "NewPass#2026"}); code != 401 {
		t.Fatal("旧密码错误应 401")
	}
	if code, _ := app.do("POST", "/api/auth/password", map[string]any{"oldPassword": app.adminPw, "newPassword": "short"}); code != 400 {
		t.Fatal("过短应 400")
	}
	if code, _ := app.do("POST", "/api/auth/password", map[string]any{"oldPassword": app.adminPw, "newPassword": "NewPass#2026"}); code != 200 {
		t.Fatal("改密失败")
	}
	jar, _ := cookiejar.New(nil)
	c := &http.Client{Jar: jar}
	body, _ := json.Marshal(map[string]string{"username": "admin", "password": "NewPass#2026"})
	resp, err := c.Post(app.srv.URL+"/api/auth/login", "application/json", bytes.NewReader(body))
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("新口令登录: %v %v", resp, err)
	}
	resp.Body.Close()
}

func TestOidcDormantAndSettings(t *testing.T) {
	app := newTestServer(t)
	// 未配置：config disabled、login 503
	resp, _ := http.Get(app.srv.URL + "/api/auth/oidc/config")
	var cfg map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&cfg)
	resp.Body.Close()
	if cfg["enabled"] != false {
		t.Fatal("未配置不应启用")
	}
	code, m := app.do("GET", "/api/auth/oidc/login", nil)
	if code != 503 {
		t.Fatalf("未配置 login 应 503: %d %v", code, m)
	}
	// 配置写入（含形状校验）
	if code, _ = app.do("PUT", "/api/auth/oidc/settings", map[string]any{"issuer": "not-a-url"}); code != 400 {
		t.Fatal("坏 issuer 应 400")
	}
	code, m = app.do("PUT", "/api/auth/oidc/settings", map[string]any{
		"issuer": "http://localhost:8080", "clientId": "demo-app", "allowedUsers": "admin, bob",
	})
	if code != 200 || m["enabled"] != true {
		t.Fatalf("保存 SSO 配置: %d %v", code, m)
	}
	if _, has := m["clientSecret"]; has {
		t.Fatal("secret 值不得回传")
	}
	// 白名单清空 → 停用
	app.do("PUT", "/api/auth/oidc/settings", map[string]any{"allowedUsers": ""})
	_, m = app.do("GET", "/api/auth/oidc/settings", nil)
	if m["enabled"] != false {
		t.Fatal("白名单清空应停用")
	}
}

func TestUpsertSSOUser(t *testing.T) {
	store, _, err := Open(filepath.Join(t.TempDir(), "admin.db"), "seed-pw")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer store.Close()

	u, err := store.UpsertSSOUser("alice", "sub-1")
	if err != nil || u.Role != "editor" {
		t.Fatalf("SSO 建户: %+v err=%v", u, err)
	}
	if u, err = store.UpsertSSOUser("alice", "sub-1"); err != nil || u.Role != "editor" {
		t.Fatalf("重复登录: %+v err=%v", u, err)
	}
	// 本地账号同名拒绝
	if _, err := store.UpsertSSOUser("admin", "sub-2"); err == nil || !strings.Contains(err.Error(), "本地账号") {
		t.Fatalf("本地撞名应拒绝: %v", err)
	}
	// 其他 sub 抢已有 SSO 用户名拒绝
	if _, err := store.UpsertSSOUser("alice", "sub-3"); err == nil || !strings.Contains(err.Error(), "其他 SSO 账号") {
		t.Fatalf("SSO 互撞应拒绝: %v", err)
	}
	// SSO 用户密码为随机值，不可本地登录
	local, _ := store.GetUser("alice")
	for _, pw := range []string{"", "seed-pw", "alice", "editor"} {
		if bcryptCompare(local.PasswordHash, pw) {
			t.Fatalf("SSO 随机密码不应匹配 %q", pw)
		}
	}
}

func TestAuditTrail(t *testing.T) {
	app := newTestServer(t)
	app.do("POST", "/api/dicts", map[string]any{"type": "demo", "label": "显示", "value": "show", "sort": 1, "enabled": true})
	list := app.getArray("/api/audit")
	if len(list) == 0 {
		t.Fatal("应有审计记录")
	}
	if list[0]["actor"] != "admin" {
		t.Fatalf("审计 actor: %v", list[0])
	}
}

func TestLoginRateLimit(t *testing.T) {
	app := newTestServer(t)
	jar, _ := cookiejar.New(nil)
	c := &http.Client{Jar: jar}
	body, _ := json.Marshal(map[string]string{"username": "admin", "password": "wrong"})
	for i := 1; i <= 5; i++ {
		resp, _ := c.Post(app.srv.URL+"/api/auth/login", "application/json", bytes.NewReader(body))
		resp.Body.Close()
		if i < 5 && resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("第 %d 次应 401: %d", i, resp.StatusCode)
		}
	}
	// 第 5 次失败后锁定：正确口令也 429
	resp, _ := c.Post(app.srv.URL+"/api/auth/login", "application/json",
		bytes.NewReader([]byte(`{"username":"admin","password":"`+app.adminPw+`"}`)))
	resp.Body.Close()
	if resp.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("锁定后正确口令应 429，实得 %d", resp.StatusCode)
	}
}
