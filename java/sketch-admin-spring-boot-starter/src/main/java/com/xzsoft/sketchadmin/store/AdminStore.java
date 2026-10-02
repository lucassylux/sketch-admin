package com.xzsoft.sketchadmin.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 管理后台存储：H2 单文件（与 go-admin 的 SQLite 同构定位——零运维单实例）。
 * 表：users / dicts / audit / settings；首启种子 admin + 内置字典。
 */
public class AdminStore implements AutoCloseable {

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;
    private final DataSource dataSource;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** H2 单文件模式（零运维默认） */
    public AdminStore(String dbPath) {
        this("jdbc:h2:file:" + normalizeH2Path(dbPath) + ";MODE=MySQL;AUTO_SERVER=TRUE", "sa", "", "org.h2.Driver");
    }

    /**
     * 通用 JDBC（MySQL 等）——由 starter 配置 sketch-admin.jdbc-* 注入。
     * 连接池化（Hikari，classpath 缺失时退 DriverManager）+ 每次操作借还连接：
     * Tomcat 多线程共享单条 JDBC 连接会协议错乱（MySQL Connector/J 非线程安全，
     * 连接被标记关闭后全部请求报 "No operations allowed after connection closed"）
     */
    public AdminStore(String jdbcUrl, String username, String password, String driverClass) {
        try {
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(jdbcUrl);
            hc.setUsername(username);
            hc.setPassword(password);
            if (driverClass != null && !driverClass.isBlank()) {
                hc.setDriverClassName(driverClass);
            }
            hc.setMaximumPoolSize(4);
            hc.setMinimumIdle(1);
            hc.setPoolName("sketch-admin");
            this.dataSource = new HikariDataSource(hc);
            try (Connection c = dataSource.getConnection()) {
                migrate(c);
                seed(c);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("管理库初始化失败: " + e.getMessage(), e);
        }
    }

    private static String normalizeH2Path(String dbPath) {
        return dbPath.startsWith("/") || dbPath.startsWith("\\")
                || dbPath.matches("^[A-Za-z]:.*") || dbPath.startsWith("~/") || dbPath.startsWith("./")
                ? dbPath : "./" + dbPath;
    }

    private void migrate(Connection conn) throws SQLException {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS users(" +
                "username VARCHAR(64) PRIMARY KEY, password_hash VARCHAR(100) NOT NULL," +
                "role VARCHAR(16) NOT NULL, subject VARCHAR(128) NOT NULL DEFAULT ''," +
                "last_login_at VARCHAR(40) NOT NULL DEFAULT '', created_at VARCHAR(40) NOT NULL)",
            "CREATE TABLE IF NOT EXISTS dicts(" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, dict_type VARCHAR(100) NOT NULL," +
                "label VARCHAR(100) NOT NULL, `value` VARCHAR(100) NOT NULL, sort INT NOT NULL DEFAULT 0," +
                "enabled INT NOT NULL DEFAULT 1, created_at VARCHAR(40) NOT NULL," +
                "CONSTRAINT uk_dicts UNIQUE (dict_type, `value`))",
            "CREATE TABLE IF NOT EXISTS audit(" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, at VARCHAR(40) NOT NULL, actor VARCHAR(64) NOT NULL," +
                "action VARCHAR(50) NOT NULL, entity VARCHAR(200) NOT NULL, detail VARCHAR(500) NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS settings(setting_key VARCHAR(100) PRIMARY KEY, setting_value VARCHAR(1000) NOT NULL)",
        };
        try (Statement st = conn.createStatement()) {
            for (String q : ddl) st.execute(q);
        }
    }

    private void seed(Connection conn) throws SQLException {
        long userCount = count("users");
        String generated = null;
        if (userCount == 0) {
            generated = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            update("INSERT INTO users(username,password_hash,role,created_at) VALUES('admin',?,'admin',?)",
                    encoder.encode(generated), now());
            this.generatedAdminPassword = generated;
        }
        if (count("dicts") == 0) {
            String[][] seed = {
                {"rule-severity", "严重", "critical", "1"},
                {"rule-severity", "高", "high", "2"},
                {"rule-severity", "中", "medium", "3"},
                {"rule-severity", "低", "low", "4"},
            };
            for (String[] d : seed) {
                update("INSERT INTO dicts(dict_type,label,`value`,sort,enabled,created_at) VALUES(?,?,?,?,1,?)",
                        d[0], d[1], d[2], Integer.parseInt(d[3]), now());
            }
        }
    }

    private String generatedAdminPassword;
    /** 首启生成的 admin 一次性口令（仅首启构造后可读，调用方负责打印） */
    public String generatedAdminPassword() { return generatedAdminPassword; }

    // ---------- 用户 ----------

    public Map<String, Object> findUser(String username) {
        return queryOne("SELECT username,password_hash,role,subject,last_login_at,created_at FROM users WHERE username=?", username);
    }

    public Map<String, Object> findUserBySubject(String subject) {
        return queryOne("SELECT username,password_hash,role,subject,last_login_at,created_at FROM users WHERE subject=? AND subject!=''", subject);
    }

    public List<Map<String, Object>> listUsers() {
        return queryList("SELECT username,role,subject,created_at,last_login_at FROM users ORDER BY username");
    }

    public int createUser(String username, String passwordHash, String role) {
        return update("INSERT INTO users(username,password_hash,role,created_at) VALUES(?,?,?,?)",
                username, passwordHash, role, now());
    }

    public int updateRole(String username, String role) {
        return update("UPDATE users SET role=? WHERE username=?", role, username);
    }

    public int updatePassword(String username, String passwordHash) {
        return update("UPDATE users SET password_hash=? WHERE username=?", passwordHash, username);
    }

    public void touchLogin(String username) {
        update("UPDATE users SET last_login_at=? WHERE username=?", now(), username);
    }

    public int deleteUser(String username) {
        return update("DELETE FROM users WHERE username=?", username);
    }

    public int adminCountExcluding(String username) {
        Long n = (Long) queryScalar("SELECT COUNT(*) FROM users WHERE role='admin' AND username<>?", username);
        return n == null ? 0 : n.intValue();
    }

    /**
     * SSO 登录落库：按 subject 找到则同步角色，否则建户（固定 editor）。
     * 用户名被本地账号或其他 SSO 账号占用时返回 null（上层报 403）。
     */
    public Map<String, Object> upsertSsoUser(String username, String subject) {
        Map<String, Object> existing = findUserBySubject(subject);
        if (existing != null) {
            if (!"editor".equals(existing.get("role"))) {
                updateRole((String) existing.get("username"), "editor");
                existing.put("role", "editor");
            }
            return existing;
        }
        Map<String, Object> sameName = findUser(username);
        if (sameName != null) return null; // 撞名拒绝
        String random = UUID.randomUUID().toString();
        createUser(username, encoder.encode(random), "editor");
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("username", username);
        created.put("role", "editor");
        return created;
    }

    public boolean checkPassword(String raw, String hash) { return encoder.matches(raw, hash); }
    public String encode(String raw) { return encoder.encode(raw); }

    // ---------- 字典 ----------

    public List<Map<String, Object>> listDictTypes() {
        return queryList("SELECT dict_type AS type, COUNT(*) AS count FROM dicts GROUP BY dict_type ORDER BY dict_type");
    }

    public List<Map<String, Object>> listDicts(String type, boolean onlyEnabled) {
        if (onlyEnabled) {
            return queryList("SELECT id,dict_type,label,`value`,sort,enabled FROM dicts WHERE dict_type=? AND enabled=1 ORDER BY sort,id", type);
        }
        return queryList("SELECT id,dict_type,label,`value`,sort,enabled FROM dicts WHERE dict_type=? ORDER BY sort,id", type);
    }

    /** 新增或更新字典项；同类型 value 冲突抛 IllegalArgumentException */
    public Map<String, Object> saveDict(Long id, String type, String label, String value, int sort, boolean enabled) {
        try {
            if (id == null || id == 0) {
                update("INSERT INTO dicts(dict_type,label,`value`,sort,enabled,created_at) VALUES(?,?,?,?,?,?)",
                        type, label, value, sort, enabled ? 1 : 0, now());
            } else {
                update("UPDATE dicts SET dict_type=?,label=?,`value`=?,sort=?,enabled=? WHERE id=?",
                        type, label, value, sort, enabled ? 1 : 0, id);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("同类型下 value 需唯一");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type); out.put("label", label); out.put("value", value);
        out.put("sort", sort); out.put("enabled", enabled);
        return out;
    }

    public int deleteDict(long id) {
        return update("DELETE FROM dicts WHERE id=?", id);
    }

    // ---------- 审计 ----------

    public void audit(String actor, String action, String entity, String detail) {
        update("INSERT INTO audit(at,actor,action,entity,detail) VALUES(?,?,?,?,?)",
                now(), actor, action, entity, detail == null ? "" : detail);
    }

    public List<Map<String, Object>> listAudit(int limit) {
        return queryList("SELECT id,at,actor,action,entity,detail FROM audit ORDER BY id DESC LIMIT " + Math.max(1, limit));
    }

    // ---------- 设置 ----------

    public String settingGet(String key) {
        Object v = queryScalar("SELECT setting_value FROM settings WHERE setting_key=?", key);
        return v == null ? "" : v.toString();
    }

    public void settingSet(String key, String value) {
        update("INSERT INTO settings(setting_key,setting_value) VALUES(?,?) ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value)", key, value);
    }

    /** 会话时长（秒；缺省/非法回退 12 小时）。存储键 session_ttl_seconds，
     *  旧 session_ttl_hours 自动迁移（×3600），改设置时写新键。 */
    public int sessionTtlSeconds() {
        try {
            String v = settingGet("session_ttl_seconds");
            if (v.isEmpty()) {
                String legacy = settingGet("session_ttl_hours");
                return legacy.isEmpty() ? 12 * 3600 : Math.max(1, Integer.parseInt(legacy.trim())) * 3600;
            }
            int sec = Integer.parseInt(v.trim());
            return (sec >= 60 && sec <= 365 * 86400) ? sec : 12 * 3600;
        } catch (Exception e) {
            return 12 * 3600;
        }
    }

    // ---------- JDBC 助手 ----------

    private String now() { return TS.format(Instant.now()); }

    private long count(String table) {
        Long n = (Long) queryScalar("SELECT COUNT(*) FROM " + table);
        return n == null ? 0 : n;
    }

    private int update(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private Object queryScalar(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private Map<String, Object> queryOne(String sql, Object... args) {
        List<Map<String, Object>> list = queryList(sql, args);
        return list.isEmpty() ? null : list.get(0);
    }

    private List<Map<String, Object>> queryList(String sql, Object... args) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        String col = toCamel(rs.getMetaData().getColumnLabel(i).toLowerCase());
                        Object v = rs.getObject(i);
                        if (v instanceof Timestamp ts) v = ts.toInstant().toString();
                        row.put(col, v);
                    }
                    out.add(row);
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
    }

    /** snake_case 列名 → lowerCamelCase（契约口径：与 go-admin JSON 标签一致） */
    private static String toCamel(String col) {
        StringBuilder sb = new StringBuilder(col.length());
        boolean upper = false;
        for (char c : col.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    @Override
    public void close() {
        if (dataSource instanceof HikariDataSource h) {
            h.close();
        }
    }
}
