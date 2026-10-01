package com.xzsoft.sketchadmin.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.xzsoft.sketchadmin.oidc.OidcService;
import com.xzsoft.sketchadmin.store.AdminStore;

/**
 * 契约全量端点（contracts/api.md）。错误统一 {"error": "..."} + HTTP 状态码；
 * 会话 cookie：sk_admin_session（HttpOnly/SameSite=Lax）。
 */
@RestController
public class AdminApiController {

    public static final String COOKIE = "sk_admin_session";
    private static final Set<String> ROLES = Set.of("admin", "editor", "viewer");
    private static final Map<String, Integer> RANK = Map.of("viewer", 1, "editor", 2, "admin", 3);

    private final AdminStore store;
    private final SessionRegistry sessions;
    private final OidcService oidc;

    public AdminApiController(AdminStore store, SessionRegistry sessions, OidcService oidc) {
        this.store = store;
        this.sessions = sessions;
        this.oidc = oidc;
    }

    // ---------- 通用 ----------

    @GetMapping("/api/health")
    public Map<String, String> health() { return Map.of("status", "ok"); }

    // ---------- 认证 ----------

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletResponse resp) {
        var u = store.findUser(body.getOrDefault("username", ""));
        if (u == null || !store.checkPassword(body.getOrDefault("password", ""), (String) u.get("passwordHash"))) {
            return err(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        store.touchLogin((String) u.get("username"));
        String token = sessions.issue((String) u.get("username"), (String) u.get("role"), (String) u.get("subject"));
        setCookie(resp, token);
        return ok(Map.of("username", u.get("username"), "role", u.get("role")));
    }

    @PostMapping("/api/auth/logout")
    public Map<String, Boolean> logout(@CookieValue(value = COOKIE, required = false) String token,
                                       HttpServletResponse resp) {
        sessions.revoke(token);
        expireCookie(resp);
        return Map.of("ok", true);
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<?> me(@CookieValue(value = COOKIE, required = false) String token) {
        var s = sessions.get(token);
        if (s == null) return err(HttpStatus.UNAUTHORIZED, "未登录");
        return ok(Map.of("username", s.username(), "role", s.role()));
    }

    @PostMapping("/api/auth/password")
    public ResponseEntity<?> changePassword(@CookieValue(value = COOKIE, required = false) String token,
                                            @RequestBody Map<String, String> body) {
        var sess = sessions.get(token);
        if (sess == null) return err(HttpStatus.UNAUTHORIZED, "未登录");
        if (!sess.subject().isEmpty()) return err(HttpStatus.BAD_REQUEST, "SSO 账号无本地密码，请使用 SSO 登录");
        if (body.getOrDefault("newPassword", "").length() < 8) return err(HttpStatus.BAD_REQUEST, "新密码至少 8 位");
        var current = store.findUser(sess.username());
        if (current == null || !store.checkPassword(body.getOrDefault("oldPassword", ""), (String) current.get("passwordHash"))) {
            return err(HttpStatus.UNAUTHORIZED, "原密码错误");
        }
        store.updatePassword(sess.username(), store.encode(body.get("newPassword")));
        store.audit(sess.username(), "update", "user/" + sess.username() + "/password", "");
        return ok(Map.of("ok", true));
    }

    // ---------- SSO/OIDC ----------

    @GetMapping("/api/auth/oidc/config")
    public Map<String, Object> oidcConfig() {
        String logoutUrl = "";
        if (oidc.enabled()) {
            try { logoutUrl = oidc.endSessionEndpoint(); } catch (Exception ignored) { }
        }
        return Map.of("enabled", oidc.enabled(), "logoutUrl", logoutUrl);
    }

    @GetMapping("/api/auth/oidc/login")
    public ResponseEntity<?> oidcLogin(@RequestParam(required = false) String prompt,
                                       HttpServletRequest req, HttpServletResponse resp) {
        try {
            return ResponseEntity.status(302).location(URI.create(oidc.authorizeUrl(prompt, req))).build();
        } catch (IllegalStateException e) {
            return err(HttpStatus.SERVICE_UNAVAILABLE, "SSO 未启用：" + e.getMessage());
        } catch (Exception e) {
            return err(HttpStatus.SERVICE_UNAVAILABLE, "SSO 未启用：" + e.getMessage());
        }
    }

    @PostMapping("/api/auth/oidc/callback")
    public ResponseEntity<?> oidcCallback(@RequestBody Map<String, String> body,
                                          HttpServletRequest req, HttpServletResponse resp) {
        try {
            OidcService.SsoIdentity identity = oidc.exchange(body.get("code"), body.get("state"), req);
            var u = store.upsertSsoUser(identity.username(), identity.subject());
            if (u == null) return err(HttpStatus.FORBIDDEN, "用户名已被本地或其他 SSO 账号占用");
            store.touchLogin((String) u.get("username"));
            store.audit((String) u.get("username"), "sso-login", "user/" + u.get("username"), "role=editor");
            String token = sessions.issue((String) u.get("username"), (String) u.get("role"), (String) u.getOrDefault("subject", identity.subject()));
            setCookie(resp, token);
            return ok(Map.of("username", u.get("username"), "role", u.get("role")));
        } catch (OidcService.NotAllowedException e) {
            return err(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.UNAUTHORIZED, e.getMessage());
        } catch (Exception e) {
            return err(HttpStatus.UNAUTHORIZED, "SSO 登录失败: " + e.getMessage());
        }
    }

    @GetMapping("/api/auth/oidc/settings")
    public ResponseEntity<?> oidcSettingsGet(@CookieValue(value = COOKIE, required = false) String token) {
        if (notAdmin(token) != null) return notAdmin(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issuer", store.settingGet("oidc_issuer"));
        out.put("clientId", store.settingGet("oidc_client_id"));
        out.put("clientSecretSet", !store.settingGet("oidc_client_secret").isEmpty());
        out.put("redirectBase", store.settingGet("oidc_redirect_base"));
        out.put("allowedUsers", store.settingGet("oidc_allowed_users"));
        out.put("enabled", oidc.enabled());
        return ok(out);
    }

    @PutMapping("/api/auth/oidc/settings")
    public ResponseEntity<?> oidcSettingsPut(@CookieValue(value = COOKIE, required = false) String token,
                                             @RequestBody Map<String, String> body) {
        var denied = require(token, "admin");
        if (denied != null) return denied;
        for (String k : new String[]{"issuer", "redirectBase"}) {
            String v = body.getOrDefault(k, "");
            if (body.containsKey(k) && !v.isBlank() && !v.matches("^https?://[^/\\s]+.*")) {
                return err(HttpStatus.BAD_REQUEST, (k.equals("issuer") ? "issuer" : "回调基准地址") + " 需为完整 http(s) 地址");
            }
        }
        setIfPresent(body, "issuer", "oidc_issuer");
        setIfPresent(body, "clientId", "oidc_client_id");
        setIfPresent(body, "redirectBase", "oidc_redirect_base");
        setIfPresent(body, "allowedUsers", "oidc_allowed_users");
        if (body.containsKey("clientSecret")) {
            String v = body.get("clientSecret").trim();
            if (v.equals("-")) store.settingSet("oidc_client_secret", "");
            else if (!v.isEmpty()) store.settingSet("oidc_client_secret", v);
        }
        store.audit(sessions.get(token).username(), "update", "settings/oidc", "");
        return oidcSettingsGet(token);
    }

    // ---------- 会话设置 ----------

    @GetMapping("/api/settings/session")
    public ResponseEntity<?> sessionGet(@CookieValue(value = COOKIE, required = false) String token) {
        var denied = notAdmin(token);
        if (denied != null) return denied;
        return ok(Map.of("ttlHours", store.sessionTtlHours()));
    }

    @PutMapping("/api/settings/session")
    public ResponseEntity<?> sessionPut(@CookieValue(value = COOKIE, required = false) String token,
                                        @RequestBody Map<String, Integer> body) {
        var denied = notAdmin(token);
        if (denied != null) return denied;
        Integer ttl = body.get("ttlHours");
        if (ttl == null || ttl < 1 || ttl > 168) return err(HttpStatus.BAD_REQUEST, "会话时长需在 1-168 小时之间");
        store.settingSet("session_ttl_hours", String.valueOf(ttl));
        store.audit(sessions.get(token).username(), "update", "settings/session", ttl + "h");
        return ok(Map.of("ttlHours", store.sessionTtlHours()));
    }

    // ---------- 字典 ----------

    @GetMapping("/api/dict-types")
    public ResponseEntity<?> dictTypes(@CookieValue(value = COOKIE, required = false) String token) {
        var denied = require(token, null);
        if (denied != null) return denied;
        List<Map<String, Object>> list = store.listDictTypes();
        for (Map<String, Object> t : list) {
            t.put("name", "rule-severity".equals(t.get("type")) ? "规则严重级" : t.get("type"));
        }
        return ok(list);
    }

    @GetMapping("/api/dicts")
    public ResponseEntity<?> dicts(@CookieValue(value = COOKIE, required = false) String token,
                                   @RequestParam String type,
                                   @RequestParam(required = false, defaultValue = "false") boolean enabled) {
        var denied = require(token, null);
        if (denied != null) return denied;
        return ok(store.listDicts(type, enabled));
    }

    @PostMapping("/api/dicts")
    public ResponseEntity<?> dictSave(@CookieValue(value = COOKIE, required = false) String token,
                                      @RequestBody Map<String, Object> body) {
        var denied2 = require(token, "editor");
        if (denied2 != null) return denied2;
        try {
            Long id = body.get("id") == null ? null : Long.valueOf(body.get("id").toString());
            var saved = store.saveDict(id,
                    str(body, "type"), str(body, "label"), str(body, "value"),
                    body.get("sort") == null ? 0 : Integer.parseInt(body.get("sort").toString()),
                    !Boolean.FALSE.equals(body.get("enabled")));
            store.audit(sessions.get(token).username(), id == null ? "create" : "update",
                    "dict/" + saved.get("type") + "/" + saved.get("value"), String.valueOf(saved.get("label")));
            return ok(saved);
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/api/dicts/{id}")
    public ResponseEntity<?> dictDelete(@CookieValue(value = COOKIE, required = false) String token,
                                        @PathVariable long id) {
        var denied3 = require(token, "editor");
        if (denied3 != null) return denied3;
        if (store.deleteDict(id) == 0) return err(HttpStatus.NOT_FOUND, "不存在");
        store.audit(sessions.get(token).username(), "delete", "dict/" + id, "");
        return ok(Map.of("ok", true));
    }

    // ---------- 审计 / 用户 ----------

    @GetMapping("/api/audit")
    public ResponseEntity<?> audit(@CookieValue(value = COOKIE, required = false) String token) {
        var denied = notAdmin(token);
        if (denied != null) return denied;
        return ok(store.listAudit(500));
    }

    @GetMapping("/api/users")
    public ResponseEntity<?> users(@CookieValue(value = COOKIE, required = false) String token) {
        var denied = notAdmin(token);
        if (denied != null) return denied;
        List<Map<String, Object>> list = store.listUsers();
        for (Map<String, Object> u : list) {
            u.put("source", u.get("subject").toString().isEmpty() ? "local" : "oidc");
            u.remove("subject");
        }
        return ok(list);
    }

    @PostMapping("/api/users")
    public ResponseEntity<?> userCreate(@CookieValue(value = COOKIE, required = false) String token,
                                        @RequestBody Map<String, String> body) {
        var denied = notAdmin(token);
        if (denied != null) return denied;
        String username = body.getOrDefault("username", "");
        if (username.isBlank() || body.getOrDefault("password", "").length() < 8) {
            return err(HttpStatus.BAD_REQUEST, "用户名必填且密码至少 8 位");
        }
        if (!ROLES.contains(body.get("role"))) return err(HttpStatus.BAD_REQUEST, "角色需为 admin/editor/viewer");
        if (store.createUser(username, store.encode(body.get("password")), body.get("role")) == 0) {
            return err(HttpStatus.CONFLICT, "用户名已存在");
        }
        store.audit(sessions.get(token).username(), "create", "user/" + username, "role=" + body.get("role"));
        return ok(Map.of("ok", true));
    }

    @PutMapping("/api/users/{username}/role")
    public ResponseEntity<?> userRole(@CookieValue(value = COOKIE, required = false) String token,
                                      @PathVariable String username, @RequestBody Map<String, String> body) {
        var s = notAdmin(token);
        if (s != null) return s;
        if (!ROLES.contains(body.get("role"))) return err(HttpStatus.BAD_REQUEST, "角色需为 admin/editor/viewer");
        String me = sessions.get(token).username();
        if (me.equals(username) && store.adminCountExcluding(username) == 0 && !"admin".equals(body.get("role"))) {
            return err(HttpStatus.BAD_REQUEST, "不能降级最后一个管理员（含自己）");
        }
        if (store.updateRole(username, body.get("role")) == 0) return err(HttpStatus.NOT_FOUND, "不存在");
        store.audit(me, "update", "user/" + username + "/role", body.get("role"));
        return ok(Map.of("ok", true));
    }

    @PostMapping("/api/users/{username}/password")
    public ResponseEntity<?> userResetPassword(@CookieValue(value = COOKIE, required = false) String token,
                                               @PathVariable String username, @RequestBody Map<String, String> body) {
        var denied = notAdmin(token);
        if (denied != null) return denied;
        if (body.getOrDefault("newPassword", "").length() < 8) return err(HttpStatus.BAD_REQUEST, "新密码至少 8 位");
        if (store.updatePassword(username, store.encode(body.get("newPassword"))) == 0) {
            return err(HttpStatus.NOT_FOUND, "不存在");
        }
        store.audit(sessions.get(token).username(), "reset-password", "user/" + username, "");
        return ok(Map.of("ok", true));
    }

    @DeleteMapping("/api/users/{username}")
    public ResponseEntity<?> userDelete(@CookieValue(value = COOKIE, required = false) String token,
                                        @PathVariable String username) {
        var denied = notAdmin(token);
        if (denied != null) return denied;
        String me = sessions.get(token).username();
        if (me.equals(username)) return err(HttpStatus.BAD_REQUEST, "不能删除自己");
        if (store.deleteUser(username) == 0) return err(HttpStatus.NOT_FOUND, "不存在");
        store.audit(me, "delete", "user/" + username, "");
        return ok(Map.of("ok", true));
    }

    // ---------- 内部 ----------

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private void setIfPresent(Map<String, String> body, String from, String settingKey) {
        if (body.containsKey(from)) store.settingSet(settingKey, body.get(from).trim());
    }

    /** 未登录返回 401 响应；已登录且角色足够返回 null */
    private ResponseEntity<Map<String, String>> require(String token, String minRole) {
        var s = sessions.get(token);
        if (s == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        if (minRole != null && RANK.getOrDefault(s.role(), 0) < RANK.get(minRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "角色权限不足（需要 " + minRole + "）"));
        }
        return null;
    }

    /** admin 专用：非 admin 或未登录返回错误响应，admin 返回 null */
    private ResponseEntity<Map<String, String>> notAdmin(String token) { return require(token, "admin"); }

    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok(body); }

    private static ResponseEntity<Map<String, String>> err(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }

    private void setCookie(HttpServletResponse resp, String token) {
        Cookie c = new Cookie(COOKIE, token);
        c.setPath("/");
        c.setHttpOnly(true);
        c.setMaxAge(sessions.ttlSeconds());
        resp.addCookie(c);
    }

    private void expireCookie(HttpServletResponse resp) {
        Cookie c = new Cookie(COOKIE, "");
        c.setPath("/");
        c.setMaxAge(0);
        resp.addCookie(c);
    }
}
