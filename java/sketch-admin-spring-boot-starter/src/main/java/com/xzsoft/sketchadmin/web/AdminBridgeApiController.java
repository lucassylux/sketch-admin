package com.xzsoft.sketchadmin.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.xzsoft.sketchadmin.store.AdminStore;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 桥接模式管理端点：宿主已带认证（实现 {@link AdminSessionBridge}）时装配，
 * 只提供字典/审计/用户/会话设置四组管理端点；登录与 SSO 由宿主自己的体系承担。
 * 错误与契约口径同 {@link AdminApiController}（{"error": "..."} + HTTP 状态码）。
 */
@RestController
public class AdminBridgeApiController {

    private static final Set<String> ROLES = Set.of("admin", "editor", "viewer");
    private static final Map<String, Integer> RANK = Map.of("viewer", 1, "editor", 2, "admin", 3);

    private final AdminStore store;
    private final AdminSessionBridge bridge;

    public AdminBridgeApiController(AdminStore store, AdminSessionBridge bridge) {
        this.store = store;
        this.bridge = bridge;
    }


    private ResponseEntity<Map<String, String>> require(HttpServletRequest req, String minRole) {
        AdminSessionBridge.AdminPrincipal p = bridge.resolve(req);
        if (p == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        if (minRole != null && RANK.getOrDefault(p.roleOrDefault(), 0) < RANK.get(minRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "角色权限不足（需要 " + minRole + "）"));
        }
        return null;
    }

    private AdminSessionBridge.AdminPrincipal user(HttpServletRequest req) {
        return bridge.resolve(req);
    }

    // ---------- 字典 ----------

    @GetMapping("/api/dict-types")
    public ResponseEntity<?> dictTypes(HttpServletRequest req) {
        var denied = require(req, null);
        if (denied != null) return denied;
        List<Map<String, Object>> list = store.listDictTypes();
        for (Map<String, Object> t : list) {
            t.put("name", "rule-severity".equals(t.get("type")) ? "规则严重级" : t.get("type"));
        }
        return ok(list);
    }

    @GetMapping("/api/dicts")
    public ResponseEntity<?> dicts(HttpServletRequest req, String type, boolean enabled) {
        var denied = require(req, null);
        if (denied != null) return denied;
        return ok(store.listDicts(type, enabled));
    }

    @PostMapping("/api/dicts")
    public ResponseEntity<?> dictSave(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        var denied = require(req, "editor");
        if (denied != null) return denied;
        try {
            Long id = body.get("id") == null ? null : Long.valueOf(body.get("id").toString());
            var saved = store.saveDict(id, str(body, "type"), str(body, "label"), str(body, "value"),
                    body.get("sort") == null ? 0 : Integer.parseInt(body.get("sort").toString()),
                    !Boolean.FALSE.equals(body.get("enabled")));
            store.audit(user(req).username(), id == null ? "create" : "update",
                    "dict/" + saved.get("type") + "/" + saved.get("value"), String.valueOf(saved.get("label")));
            return ok(saved);
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/api/dicts/{id}")
    public ResponseEntity<?> dictDelete(HttpServletRequest req, @PathVariable long id) {
        var denied = require(req, "editor");
        if (denied != null) return denied;
        if (store.deleteDict(id) == 0) return err(HttpStatus.NOT_FOUND, "不存在");
        store.audit(user(req).username(), "delete", "dict/" + id, "");
        return ok(Map.of("ok", true));
    }

    // ---------- 审计 / 用户 ----------

    @GetMapping("/api/audit")
    public ResponseEntity<?> audit(HttpServletRequest req) {
        var denied = require(req, "admin");
        if (denied != null) return denied;
        return ok(store.listAudit(500));
    }

    @GetMapping("/api/users")
    public ResponseEntity<?> users(HttpServletRequest req) {
        var denied = require(req, "admin");
        if (denied != null) return denied;
        return ok(store.listUsers());
    }

    @PostMapping("/api/users")
    public ResponseEntity<?> userCreate(HttpServletRequest req, @RequestBody Map<String, String> body) {
        var denied = require(req, "admin");
        if (denied != null) return denied;
        if (body.getOrDefault("username", "").isBlank() || body.getOrDefault("password", "").length() < 8) {
            return err(HttpStatus.BAD_REQUEST, "用户名必填且密码至少 8 位");
        }
        if (!ROLES.contains(body.get("role"))) return err(HttpStatus.BAD_REQUEST, "角色需为 admin/editor/viewer");
        if (store.createUser(body.get("username"), store.encode(body.get("password")), body.get("role")) == 0) {
            return err(HttpStatus.CONFLICT, "用户名已存在");
        }
        store.audit(user(req).username(), "create", "user/" + body.get("username"), "role=" + body.get("role"));
        return ok(Map.of("ok", true));
    }

    @PutMapping("/api/users/{username}/role")
    public ResponseEntity<?> userRole(HttpServletRequest req, @PathVariable String username, @RequestBody Map<String, String> body) {
        var denied = require(req, "admin");
        if (denied != null) return denied;
        if (!ROLES.contains(body.get("role"))) return err(HttpStatus.BAD_REQUEST, "角色需为 admin/editor/viewer");
        if (store.updateRole(username, body.get("role")) == 0) return err(HttpStatus.NOT_FOUND, "不存在");
        store.audit(user(req).username(), "update", "user/" + username + "/role", body.get("role"));
        return ok(Map.of("ok", true));
    }

    @PostMapping("/api/users/{username}/password")
    public ResponseEntity<?> userResetPassword(HttpServletRequest req, @PathVariable String username, @RequestBody Map<String, String> body) {
        var denied = require(req, "admin");
        if (denied != null) return denied;
        if (body.getOrDefault("newPassword", "").length() < 8) return err(HttpStatus.BAD_REQUEST, "新密码至少 8 位");
        if (store.updatePassword(username, store.encode(body.get("newPassword"))) == 0) {
            return err(HttpStatus.NOT_FOUND, "不存在");
        }
        store.audit(user(req).username(), "reset-password", "user/" + username, "");
        return ok(Map.of("ok", true));
    }

    @DeleteMapping("/api/users/{username}")
    public ResponseEntity<?> userDelete(HttpServletRequest req, @PathVariable String username) {
        var denied = require(req, "admin");
        if (denied != null) return denied;
        String me = user(req).username();
        if (me.equals(username)) return err(HttpStatus.BAD_REQUEST, "不能删除自己");
        if (store.deleteUser(username) == 0) return err(HttpStatus.NOT_FOUND, "不存在");
        store.audit(me, "delete", "user/" + username, "");
        return ok(Map.of("ok", true));
    }

    /** 用户启停（禁用后本地登录与 SSO 登录均拒绝；不可禁用自己） */
    @PutMapping("/api/users/{username}/status")
    public ResponseEntity<?> userStatus(HttpServletRequest req, @PathVariable String username,
                                        @RequestBody Map<String, Boolean> body) {
        var denied = require(req, "admin");
        if (denied != null) return denied;
        Boolean enabled = body.get("enabled");
        if (enabled == null) return err(HttpStatus.BAD_REQUEST, "enabled 必填");
        if (!enabled && user(req).username().equals(username)) {
            return err(HttpStatus.BAD_REQUEST, "不能禁用自己");
        }
        if (store.setUserEnabled(username, enabled) == 0) return err(HttpStatus.NOT_FOUND, "不存在");
        store.audit(user(req).username(), "update", "user/" + username + "/status", enabled ? "enabled" : "disabled");
        return ok(Map.of("username", username, "enabled", enabled));
    }

    // ---------- 会话设置（桥接模式下仅供展示；桥接会话由宿主管理） ----------

    @GetMapping("/api/settings/session")
    public ResponseEntity<?> sessionGet(HttpServletRequest req) {
        var denied = require(req, "admin");
        if (denied != null) return denied;
        return ok(Map.of("ttlSeconds", store.sessionTtlSeconds()));
    }

    @PutMapping("/api/settings/session")
    public ResponseEntity<?> sessionPut(HttpServletRequest req, @RequestBody Map<String, Integer> body) {
        var denied = require(req, "admin");
        if (denied != null) return denied;
        Integer ttl = body.get("ttlSeconds");
        if (ttl == null || ttl < 60 || ttl > 365 * 86400) return err(HttpStatus.BAD_REQUEST, "会话时长需在 1 分钟 - 365 天之间（秒）");
        store.settingSet("session_ttl_seconds", String.valueOf(ttl));
        store.audit(user(req).username(), "update", "settings/session", ttl + "s");
        return ok(Map.of("ttlSeconds", store.sessionTtlSeconds()));
    }

    // ---------- 品牌外观（登录页 logo / 页脚文案；GET 登录前公开读，PUT admin） ----------

    @GetMapping("/api/settings/brand")
    public ResponseEntity<?> brandGet() {
        return ok(Map.of(
                "logoSvg", store.settingGet("brand_logo_svg"),
                "iconSvg", store.settingGet("brand_icon_svg"),
                "appName", store.settingGet("brand_app_name"),
                "tagline", store.settingGet("brand_tagline"),
                "copyrightText", store.settingGet("brand_copyright_text")));
    }

    @PutMapping("/api/settings/brand")
    public ResponseEntity<?> brandPut(HttpServletRequest req, @RequestBody Map<String, String> body) {
        var denied = require(req, "admin");
        if (denied != null) return denied;
        String logo = body.getOrDefault("logoSvg", "");
        String icon = body.getOrDefault("iconSvg", "");
        String appName = body.getOrDefault("appName", "");
        String tagline = body.getOrDefault("tagline", "");
        String copyright = body.getOrDefault("copyrightText", "");
        if (logo.length() > 20000 || icon.length() > 20000) return err(HttpStatus.BAD_REQUEST, "SVG 过大（各 ≤20KB）");
        if (appName.length() > 64 || tagline.length() > 128 || copyright.length() > 200) {
            return err(HttpStatus.BAD_REQUEST, "文案过长（应用名 ≤64 / 副标题 ≤128 / 版权 ≤200 字符）");
        }
        store.settingSet("brand_logo_svg", logo);
        store.settingSet("brand_icon_svg", icon);
        store.settingSet("brand_app_name", appName);
        store.settingSet("brand_tagline", tagline);
        store.settingSet("brand_copyright_text", copyright);
        store.audit(user(req).username(), "update", "settings/brand",
                "logo=" + (logo.isBlank() ? "reset" : "custom") + ",icon=" + (icon.isBlank() ? "reset" : "custom") + ",copyright=" + (copyright.isBlank() ? "reset" : "custom"));
        return ok(Map.of("logoSvg", logo, "iconSvg", icon, "appName", appName, "tagline", tagline, "copyrightText", copyright));
    }

    // ---------- 内部 ----------

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok(body); }

    private static ResponseEntity<Map<String, String>> err(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }

    static Map<String, Object> healthBody() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("mode", "bridged");
        return m;
    }
}
