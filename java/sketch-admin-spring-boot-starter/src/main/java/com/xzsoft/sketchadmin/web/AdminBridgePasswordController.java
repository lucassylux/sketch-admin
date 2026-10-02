package com.xzsoft.sketchadmin.web;

import com.xzsoft.sketchadmin.store.AdminStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 桥接模式改自己密码（契约 POST /api/auth/password；登录即可，不限角色）。
 * 独立成 Bean 而非并入 AdminBridgeApiController：宿主自带 /api/auth/password 时
 * （如 sqlgoose 的本地账号体系）会同路径冲突——这类宿主设
 * sketch-admin.bridge.password-endpoint=false 关掉本端点，用自己的。
 */
@RestController
public class AdminBridgePasswordController {

    private final AdminStore store;
    private final AdminSessionBridge bridge;

    public AdminBridgePasswordController(AdminStore store, AdminSessionBridge bridge) {
        this.store = store;
        this.bridge = bridge;
    }

    @PostMapping("/api/auth/password")
    public ResponseEntity<?> changeOwnPassword(HttpServletRequest req, @RequestBody Map<String, String> body) {
        AdminSessionBridge.AdminPrincipal p = bridge.resolve(req);
        if (p == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        String me = p.username();
        Map<String, Object> u = store.findUser(me);
        String oldHash = u == null ? null : (String) u.get("password_hash");
        // SSO 桥接账号无本地密码（口令在宿主/认证中心），与独立模式同口径拒绝
        if (oldHash == null || oldHash.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "该账号为 SSO 登录，请在认证中心修改密码"));
        }
        if (!store.checkPassword(body.getOrDefault("oldPassword", ""), oldHash)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "旧密码不正确"));
        }
        String nw = body.getOrDefault("newPassword", "");
        if (nw.length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("error", "新密码至少 8 位"));
        }
        store.updatePassword(me, store.encode(nw));
        store.audit(me, "update", "user/" + me + "/password", "self");
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
