// OIDC（SSO）登录：授权码 + PKCE，对接任意标准 OIDC Provider。
// 未配置或白名单为空时整套端点休眠，不影响本地账号密码登录。
// 配置存 settings（oidc_*）；环境变量 SK_ADMIN_OIDC_ISSUER / CLIENT_ID / CLIENT_SECRET /
// ALLOWED_USERS / REDIRECT_BASE 首启注入（已有值不覆盖）。
package admin

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	oidclib "github.com/coreos/go-oidc/v3/oidc"
	"golang.org/x/oauth2"
)

const (
	oidcScope     = "openid profile email"
	oidcStateTTL  = 10 * time.Minute
	oidcMaxStates = 1000
)

type oidcLoginState struct {
	verifier string
	expires  time.Time
}

type oidcRuntime struct {
	issuer, clientID, secret string
	provider                 *oidclib.Provider
	verifier                 *oidclib.IDTokenVerifier
	endLogout                string
}

// oidc 挂起登录态与 Provider 运行时缓存（配置变化时按值失效重建）
var (
	oidcMu     sync.Mutex
	oidcCache  *oidcRuntime
	oidcStates = map[string]oidcLoginState{}
)

// seedOIDCEnv 首启注入：环境变量写入 settings（已有值不覆盖）
func (s *Store) seedOIDCEnv() {
	pairs := [][2]string{
		{"SK_ADMIN_OIDC_ISSUER", "oidc_issuer"},
		{"SK_ADMIN_OIDC_CLIENT_ID", "oidc_client_id"},
		{"SK_ADMIN_OIDC_CLIENT_SECRET", "oidc_client_secret"},
		{"SK_ADMIN_OIDC_ALLOWED_USERS", "oidc_allowed_users"},
		{"SK_ADMIN_OIDC_REDIRECT_BASE", "oidc_redirect_base"},
	}
	for _, p := range pairs {
		if v := strings.TrimSpace(os.Getenv(p[0])); v != "" && s.SettingGet(p[1]) == "" {
			s.SettingSet(p[1], v)
		}
	}
}

// oidcConfigured issuer+client_id 齐备即视为已配置；secret 可选（PKCE 公开客户端）
func (s *Store) oidcConfigured() bool {
	return s.SettingGet("oidc_issuer") != "" && s.SettingGet("oidc_client_id") != ""
}

// oidcEnabled 已配置且白名单非空（白名单制：空 = 不放行任何人）
func (s *Store) oidcEnabled() bool {
	return s.oidcConfigured() && strings.TrimSpace(s.SettingGet("oidc_allowed_users")) != ""
}

// oidcRuntimeGet 获取（并缓存）Provider/Verifier：发现端点只打一次
func (s *Store) oidcRuntimeGet() (*oidcRuntime, error) {
	if !s.oidcConfigured() {
		return nil, errors.New("OIDC 未配置")
	}
	issuer, clientID, secret := s.SettingGet("oidc_issuer"), s.SettingGet("oidc_client_id"), s.SettingGet("oidc_client_secret")
	oidcMu.Lock()
	defer oidcMu.Unlock()
	if c := oidcCache; c != nil && c.issuer == issuer && c.clientID == clientID && c.secret == secret {
		return c, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	provider, err := oidclib.NewProvider(ctx, issuer)
	if err != nil {
		return nil, err
	}
	var disc struct {
		EndSessionEndpoint string `json:"end_session_endpoint"`
	}
	_ = provider.Claims(&disc)
	oidcCache = &oidcRuntime{
		issuer: issuer, clientID: clientID, secret: secret,
		provider: provider, verifier: provider.Verifier(&oidclib.Config{ClientID: clientID}),
		endLogout: disc.EndSessionEndpoint,
	}
	return oidcCache, nil
}

// oidcRedirectURI 回调地址：设置 oidc_redirect_base 时以其为基准（反代场景），否则按请求推断
func (s *Store) oidcRedirectURI(r *http.Request) string {
	if base := strings.TrimSpace(s.SettingGet("oidc_redirect_base")); base != "" {
		return strings.TrimSuffix(base, "/") + "/oidc/callback"
	}
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	} else if p := r.Header.Get("X-Forwarded-Proto"); p != "" {
		scheme = p
	}
	return scheme + "://" + r.Host + "/oidc/callback"
}

// evictStatesLocked 清理挂起 state（需持锁）：先清过期，仍满逐出最早过期项
func evictStatesLocked(nowT time.Time) {
	for k, v := range oidcStates {
		if v.expires.Before(nowT) {
			delete(oidcStates, k)
		}
	}
	for len(oidcStates) >= oidcMaxStates {
		var oldestKey string
		var oldest time.Time
		first := true
		for k, v := range oidcStates {
			if first || v.expires.Before(oldest) {
				oldestKey, oldest, first = k, v.expires, false
			}
		}
		delete(oidcStates, oldestKey)
	}
}

// handleOidcConfig GET /api/auth/oidc/config（公开）：登录页据此决定是否显示 SSO 按钮
func (s *Server) handleOidcConfig(w http.ResponseWriter, _ *http.Request) {
	logout := ""
	if s.store.oidcEnabled() {
		if rt, err := s.store.oidcRuntimeGet(); err == nil {
			logout = rt.endLogout
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"enabled": s.store.oidcEnabled(), "logoutUrl": logout})
}

// handleOidcLogin GET /api/auth/oidc/login（公开）：302 到 Provider 授权端点。
// ?prompt=login/consent/select_account 取值白名单防注入
func (s *Server) handleOidcLogin(w http.ResponseWriter, r *http.Request) {
	rt, err := s.store.oidcRuntimeGet()
	if err != nil {
		writeErr(w, http.StatusServiceUnavailable, "SSO 未启用："+err.Error())
		return
	}
	state, verifier, err := oidcNewPair()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "生成 state 失败")
		return
	}
	oidcMu.Lock()
	nowT := time.Now()
	evictStatesLocked(nowT)
	oidcStates[state] = oidcLoginState{verifier: verifier, expires: nowT.Add(oidcStateTTL)}
	oidcMu.Unlock()

	oauth := &oauth2.Config{
		ClientID: rt.clientID, ClientSecret: rt.secret,
		Endpoint: rt.provider.Endpoint(), RedirectURL: s.store.oidcRedirectURI(r),
		Scopes: strings.Fields(oidcScope),
	}
	opts := []oauth2.AuthCodeOption{
		oauth2.SetAuthURLParam("code_challenge", oidcS256(verifier)),
		oauth2.SetAuthURLParam("code_challenge_method", "S256"),
	}
	switch r.URL.Query().Get("prompt") {
	case "login", "consent", "select_account":
		opts = append(opts, oauth2.SetAuthURLParam("prompt", r.URL.Query().Get("prompt")))
	}
	http.Redirect(w, r, oauth.AuthCodeURL(state, opts...), http.StatusFound)
}

// handleOidcCallback POST /api/auth/oidc/callback {code,state}（公开）：
// 换码校验 ID token → 白名单 → 按 subject 落户（固定 editor）→ 签发本站会话
func (s *Server) handleOidcCallback(w http.ResponseWriter, r *http.Request) {
	var req struct{ Code, State string }
	if !readJSON(w, r, &req) {
		return
	}
	rt, err := s.store.oidcRuntimeGet()
	if err != nil {
		writeErr(w, http.StatusServiceUnavailable, "SSO 未启用："+err.Error())
		return
	}
	oidcMu.Lock()
	st, ok := oidcStates[req.State]
	delete(oidcStates, req.State)
	oidcMu.Unlock()
	if !ok || time.Now().After(st.expires) {
		writeErr(w, http.StatusUnauthorized, "登录状态已过期，请重新发起 SSO 登录")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	oauth := &oauth2.Config{
		ClientID: rt.clientID, ClientSecret: rt.secret,
		Endpoint: rt.provider.Endpoint(), RedirectURL: s.store.oidcRedirectURI(r),
	}
	tok, err := oauth.Exchange(ctx, req.Code, oauth2.SetAuthURLParam("code_verifier", st.verifier))
	if err != nil {
		writeErr(w, http.StatusUnauthorized, "SSO 授权码交换失败")
		return
	}
	rawID, _ := tok.Extra("id_token").(string)
	if rawID == "" {
		writeErr(w, http.StatusUnauthorized, "Provider 未返回 id_token")
		return
	}
	idTok, err := rt.verifier.Verify(ctx, rawID)
	if err != nil {
		writeErr(w, http.StatusUnauthorized, "ID token 校验失败")
		return
	}
	var idClaims struct {
		Sub               string `json:"sub"`
		PreferredUsername string `json:"preferred_username"`
		Email             string `json:"email"`
	}
	if err := idTok.Claims(&idClaims); err != nil {
		writeErr(w, http.StatusUnauthorized, "解析用户信息失败")
		return
	}
	identity := idClaims.PreferredUsername
	if identity == "" {
		identity = idClaims.Email
	}
	if identity == "" {
		identity = idClaims.Sub
	}
	if identity == "" || idClaims.Sub == "" {
		writeErr(w, http.StatusForbidden, "Provider 未返回可用身份标识")
		return
	}
	if !oidcAllow(s.store.SettingGet("oidc_allowed_users"), identity) {
		writeErr(w, http.StatusForbidden, "该 SSO 账号未在白名单中")
		return
	}
	// 白名单制：SSO 用户固定「编辑」角色；管理面走本地管理员账号
	u, err := s.store.UpsertSSOUser(identity, idClaims.Sub)
	if errors.Is(err, ErrConflict) {
		writeErr(w, http.StatusForbidden, err.Error())
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "SSO 用户落库失败: "+err.Error())
		return
	}
	s.store.TouchLogin(u.Username)
	_ = s.store.Audit(u.Username, "sso-login", "user/"+u.Username, "role="+u.Role)
	s.issueSession(w, *u)
	writeJSON(w, http.StatusOK, map[string]any{"username": u.Username, "role": u.Role})
}

// handleOidcSettingsGet GET /api/auth/oidc/settings（admin）：secret 只回"是否已设置"
func (s *Server) handleOidcSettingsGet(w http.ResponseWriter, _ *http.Request, _ *User) {
	writeJSON(w, http.StatusOK, map[string]any{
		"issuer":          s.store.SettingGet("oidc_issuer"),
		"clientId":        s.store.SettingGet("oidc_client_id"),
		"clientSecretSet": s.store.SettingGet("oidc_client_secret") != "",
		"redirectBase":    s.store.SettingGet("oidc_redirect_base"),
		"allowedUsers":    s.store.SettingGet("oidc_allowed_users"),
		"enabled":         s.store.oidcEnabled(),
	})
}

// handleOidcSettingsPut PUT /api/auth/oidc/settings（admin）：字段 nil 不改/空串清除；
// secret 空串不改、"-" 清除；变更即时生效（运行时缓存按值失效）
func (s *Server) handleOidcSettingsPut(w http.ResponseWriter, r *http.Request, _ *User) {
	var req struct {
		Issuer       *string `json:"issuer"`
		ClientID     *string `json:"clientId"`
		ClientSecret *string `json:"clientSecret"`
		RedirectBase *string `json:"redirectBase"`
		AllowedUsers *string `json:"allowedUsers"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	for _, v := range []struct{ name, raw string }{
		{"issuer", deref(req.Issuer)},
		{"回调基准地址", deref(req.RedirectBase)},
	} {
		if v.raw != "" && !httpURLValid(v.raw) {
			writeErr(w, http.StatusBadRequest, v.name+" 需为完整 http(s) 地址")
			return
		}
	}
	set := func(key string, p *string) {
		if p != nil {
			s.store.SettingSet(key, strings.TrimSpace(*p))
		}
	}
	set("oidc_issuer", req.Issuer)
	set("oidc_client_id", req.ClientID)
	set("oidc_redirect_base", req.RedirectBase)
	set("oidc_allowed_users", req.AllowedUsers)
	if req.ClientSecret != nil {
		v := strings.TrimSpace(*req.ClientSecret)
		if v == "-" {
			s.store.SettingSet("oidc_client_secret", "")
		} else if v != "" {
			s.store.SettingSet("oidc_client_secret", v)
		}
	}
	_ = s.store.Audit(s.sessionUser(r).Username, "update", "settings/oidc", "")
	s.handleOidcSettingsGet(w, r, nil)
}

func deref(p *string) string {
	if p == nil {
		return ""
	}
	return strings.TrimSpace(*p)
}

// httpURLValid http(s) 完整地址形状校验
func httpURLValid(v string) bool {
	u, err := url.Parse(v)
	return err == nil && (u.Scheme == "http" || u.Scheme == "https") && u.Host != ""
}

// oidcAllow 白名单匹配：逗号/空格/换行分隔，忽略大小写
func oidcAllow(raw, identity string) bool {
	for _, item := range strings.FieldsFunc(raw, func(rr rune) bool {
		return rr == ',' || rr == ' ' || rr == '\n' || rr == '\t' || rr == '\r' || rr == ';'
	}) {
		if strings.EqualFold(strings.TrimSpace(item), identity) {
			return true
		}
	}
	return false
}

func oidcNewPair() (state, verifier string, err error) {
	sb, err := randomHex(32)
	if err != nil {
		return "", "", err
	}
	vb, err := randomHex(32)
	if err != nil {
		return "", "", err
	}
	return sb, vb, nil
}

func oidcS256(verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}
