package com.xzsoft.sketchadmin.web;

import com.xzsoft.sketchadmin.oidc.OidcService;
import com.xzsoft.sketchadmin.store.AdminStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桥接模式通用 OIDC 登录（任意 Provider：看门鹅/Keycloak/Casdoor/Auth0……）。
 * 复用独立模式的 OidcService（discovery + PKCE + JWKS 验签 + 白名单），
 * 差异仅在会话产物：独立模式发 sketch-admin cookie，桥接模式经
 * {@link AdminSessionBridge#issueSession} 由宿主签发自己的凭证（如 JWT）返回前端。
 * 用户记录落在 AdminStore（sso 绑定语义与独立模式一致）。
 * 默认注册；宿主自带 OIDC 体系时设 sketch-admin.bridge.oidc-endpoint=false。
 */
@RestController
public class AdminBridgeOidcController {

    private final AdminStore store;
    private final AdminSessionBridge bridge;
    private final OidcService oidc;

    public AdminBridgeOidcController(AdminStore store, AdminSessionBridge bridge, OidcService oidc) {
        this.store = store;
        this.bridge = bridge;
        this.oidc = oidc;
    }

    /** 登录页公开探测 */
    @GetMapping("/api/auth/oidc/config")
    public Map<String, Object> config() {
        String logoutUrl = "";
        if (oidc.enabled()) {
            try { logoutUrl = oidc.endSessionEndpoint(); } catch (Exception ignored) { }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", oidc.enabled());
        out.put("logoutUrl", logoutUrl);
        return out;
    }

    /** 发起：302 到 Provider 授权端点（PKCE） */
    @GetMapping("/api/auth/oidc/login")
    public ResponseEntity<?> login(@org.springframework.web.bind.annotation.RequestParam(required = false) String prompt,
                                   HttpServletRequest req) {
        try {
            return ResponseEntity.status(302).location(URI.create(oidc.authorizeUrl(prompt, req))).build();
        } catch (Exception e) {
            return err(HttpStatus.SERVICE_UNAVAILABLE, "SSO 未启用或未配置完整：" + e.getMessage());
        }
    }

    /** 回调：code+state 换身份 → 用户绑定 → 宿主签发会话凭证（前端保存） */
    @PostMapping("/api/auth/oidc/callback")
    public ResponseEntity<?> callback(@RequestBody Map<String, String> body, HttpServletRequest req) {
        try {
            OidcService.SsoIdentity identity = oidc.exchange(body.get("code"), body.get("state"), req);
            // 桥接模式用户体系在宿主/IdP 侧：落库仅为例行记录，与本地账号同名（upsert 冲突）不拒绝登录
            var u = store.upsertSsoUser(identity.username(), identity.subject());
            String username = u != null ? (String) u.get("username") : identity.username();
            String role = u != null ? (String) u.get("role") : "editor";
            if (u != null) store.touchLogin(username);
            store.audit(username, "sso-login", "user/" + username, "bridge-oidc");
            String token = bridge.issueSession(AdminSessionBridge.AdminPrincipal.of(username, role));
            return ok(Map.of("token", token, "username", username, "role", role));
        } catch (OidcService.NotAllowedException e) {
            return err(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.UNAUTHORIZED, e.getMessage());
        } catch (UnsupportedOperationException e) {
            return err(HttpStatus.NOT_IMPLEMENTED, e.getMessage());
        } catch (Exception e) {
            return err(HttpStatus.UNAUTHORIZED, "SSO 登录失败: " + e.getMessage());
        }
    }

    // ---------- OIDC 连接配置（通用 Provider；admin） ----------

    @GetMapping("/api/settings/oidc")
    public ResponseEntity<?> settingsGet(HttpServletRequest req) {
        var denied = require(req, "admin");
        if (denied != null) return denied;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issuer", store.settingGet("oidc_issuer"));
        out.put("clientId", store.settingGet("oidc_client_id"));
        out.put("clientSecretSet", !store.settingGet("oidc_client_secret").isEmpty());
        out.put("redirectUri", store.settingGet("oidc_redirect_uri"));
        out.put("redirectBase", store.settingGet("oidc_redirect_base"));
        out.put("allowedUsers", store.settingGet("oidc_allowed_users"));
        out.put("scopes", store.settingGet("oidc_scopes"));
        out.put("configured", oidc.enabled());
        return ok(out);
    }

    @PutMapping("/api/settings/oidc")
    public ResponseEntity<?> settingsPut(HttpServletRequest req, @RequestBody Map<String, String> body) {
        var denied = require(req, "admin");
        if (denied != null) return denied;
        for (String k : new String[]{"issuer", "redirectBase", "redirectUri"}) {
            String v = body.getOrDefault(k, "");
            if (body.containsKey(k) && !v.isBlank() && !v.matches("^https?://[^/\\s]+.*")) {
                return err(HttpStatus.BAD_REQUEST, k + " 需为完整 http(s) 地址");
            }
        }
        setIfPresent(body, "issuer", "oidc_issuer");
        setIfPresent(body, "clientId", "oidc_client_id");
        setIfPresent(body, "redirectUri", "oidc_redirect_uri");
        setIfPresent(body, "redirectBase", "oidc_redirect_base");
        setIfPresent(body, "allowedUsers", "oidc_allowed_users");
        setIfPresent(body, "scopes", "oidc_scopes");
        if (body.containsKey("clientSecret")) {
            String v = body.get("clientSecret").trim();
            store.settingSet("oidc_client_secret", v.equals("-") ? "" : v);
        }
        store.audit(bridge.resolve(req).username(), "update", "settings/oidc", "");
        return settingsGet(req);
    }

    // ---------- 内部 ----------

    private ResponseEntity<Map<String, String>> err(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }

    private ResponseEntity<Object> ok(Object data) {
        return ResponseEntity.ok(data);
    }

    private ResponseEntity<Map<String, String>> require(HttpServletRequest req, String minRole) {
        AdminSessionBridge.AdminPrincipal p = bridge.resolve(req);
        if (p == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        int rank = RANK.getOrDefault(p.roleOrDefault(), 0);
        if (RANK.get(minRole) > rank) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "角色权限不足（需要 " + minRole + "）"));
        }
        return null;
    }

    private static final Map<String, Integer> RANK = Map.of("viewer", 1, "editor", 2, "admin", 3);

    private void setIfPresent(Map<String, String> body, String key, String settingKey) {
        if (body.containsKey(key)) {
            store.settingSet(settingKey, body.getOrDefault(key, "").trim());
        }
    }
}
