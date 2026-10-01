package com.xzsoft.sketchadmin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** sketch-admin 配置（前缀 sketch-admin） */
@ConfigurationProperties(prefix = "sketch-admin")
public class SketchAdminProperties {

    /** H2 库文件路径（无扩展名）；配置了 jdbc-url 时忽略 */
    private String dbPath = "data/admin";

    /** 外部数据库 JDBC URL（如 MySQL：jdbc:mysql://host:3306/myapp_dev）——配置后优先于 H2 */
    private String jdbcUrl = "";

    private String jdbcUsername = "";

    private String jdbcPassword = "";

    public String jdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String v) { this.jdbcUrl = v; }

    public String jdbcUsername() { return jdbcUsername; }
    public void setJdbcUsername(String v) { this.jdbcUsername = v; }

    public String jdbcPassword() { return jdbcPassword; }
    public void setJdbcPassword(String v) { this.jdbcPassword = v; }

    /** 首启 admin 口令；留空则随机生成并日志打印一次 */
    private String seedAdminPassword = "";

    public String dbPath() { return dbPath; }
    public void setDbPath(String v) { this.dbPath = v; }

    public String seedAdminPassword() { return seedAdminPassword; }
    public void setSeedAdminPassword(String v) { this.seedAdminPassword = v; }
}
