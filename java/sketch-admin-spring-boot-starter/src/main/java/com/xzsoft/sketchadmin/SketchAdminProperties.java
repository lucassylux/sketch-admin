package com.xzsoft.sketchadmin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** sketch-admin 配置（前缀 sketch-admin） */
@ConfigurationProperties(prefix = "sketch-admin")
public class SketchAdminProperties {

    /** H2 库文件路径（无扩展名） */
    private String dbPath = "data/admin";

    /** 首启 admin 口令；留空则随机生成并日志打印一次 */
    private String seedAdminPassword = "";

    public String dbPath() { return dbPath; }
    public void setDbPath(String v) { this.dbPath = v; }

    public String seedAdminPassword() { return seedAdminPassword; }
    public void setSeedAdminPassword(String v) { this.seedAdminPassword = v; }
}
