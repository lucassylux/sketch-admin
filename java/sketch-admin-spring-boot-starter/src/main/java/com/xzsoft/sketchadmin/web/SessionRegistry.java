package com.xzsoft.sketchadmin.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xzsoft.sketchadmin.store.AdminStore;

/**
 * 内存会话（与 go-admin 同构：单实例部署；重启即全员重新登录，可接受）。
 * token → [username, role, subject, 过期时刻]；时长走 settings（对新登录生效）。
 */
public class SessionRegistry {

    public record Sess(String username, String role, String subject, Instant expires) {}

    private final Map<String, Sess> sessions = new ConcurrentHashMap<>();
    private final AdminStore store;

    public SessionRegistry(AdminStore store) { this.store = store; }

    /** 签发会话，返回 token（由 controller 写 cookie） */
    public String issue(String username, String role, String subject) {
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        Instant expires = Instant.now().plus(Duration.ofSeconds(store.sessionTtlSeconds()));
        sessions.put(token, new Sess(username, role, subject, expires));
        return token;
    }

    public Sess get(String token) {
        if (token == null || token.isEmpty()) return null;
        Sess s = sessions.get(token);
        if (s == null || Instant.now().isAfter(s.expires())) {
            if (s != null) sessions.remove(token);
            return null;
        }
        return s;
    }

    public void revoke(String token) {
        if (token != null) sessions.remove(token);
    }

    public int ttlSeconds() { return store.sessionTtlSeconds(); }
}
