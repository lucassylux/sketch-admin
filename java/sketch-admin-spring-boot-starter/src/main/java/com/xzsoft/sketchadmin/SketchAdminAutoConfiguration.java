package com.xzsoft.sketchadmin;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.xzsoft.sketchadmin.oidc.OidcService;
import com.xzsoft.sketchadmin.store.AdminStore;
import com.xzsoft.sketchadmin.web.AdminApiController;
import com.xzsoft.sketchadmin.web.AdminSessionBridge;
import com.xzsoft.sketchadmin.web.SessionRegistry;

/**
 * 自动装配：引入本 starter 即获得 sketch-admin 全量契约端点。
 * 配置项（application.yml）：
 *   sketch-admin:
 *     db-path: data/admin        # H2 库文件（无扩展名）
 *     seed-admin-password: ''    # 首启 admin 口令；留空则随机生成并日志打印一次
 *   环境变量注入 SSO（首启有效）：SK_ADMIN_OIDC_ISSUER / CLIENT_ID / CLIENT_SECRET / ALLOWED_USERS / REDIRECT_BASE
 */
@AutoConfiguration
@EnableConfigurationProperties(SketchAdminProperties.class)
public class SketchAdminAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public AdminStore adminStore(SketchAdminProperties props) {
        // 外部数据库（MySQL 等）优先；未配置则回落 H2 单文件
        AdminStore store = (props.jdbcUrl() != null && !props.jdbcUrl().isBlank())
                ? new AdminStore(props.jdbcUrl(), props.jdbcUsername(), props.jdbcPassword(), null)
                : new AdminStore(props.dbPath());
        // 环境变量首启注入（settings 已有值不覆盖）
        seedEnv(store, "SK_ADMIN_OIDC_ISSUER", "oidc_issuer");
        seedEnv(store, "SK_ADMIN_OIDC_CLIENT_ID", "oidc_client_id");
        seedEnv(store, "SK_ADMIN_OIDC_CLIENT_SECRET", "oidc_client_secret");
        seedEnv(store, "SK_ADMIN_OIDC_ALLOWED_USERS", "oidc_allowed_users");
        seedEnv(store, "SK_ADMIN_OIDC_REDIRECT_BASE", "oidc_redirect_base");
        String generated = store.generatedAdminPassword();
        if (generated != null) {
            String pw = props.seedAdminPassword();
            if (pw != null && !pw.isBlank()) {
                // 配了显式种子口令：以配置为准种入（使用者已知，不打日志）
                store.updatePassword("admin", store.encode(pw));
            } else {
                // 未配置：随机生成的一次性口令只打这一次日志
                org.slf4j.LoggerFactory.getLogger("sketch-admin")
                        .info("=== sketch-admin 首启：管理员账号 admin，初始口令（仅此一次显示）: {} ===", generated);
            }
        }
        return store;
    }

    // 桥接模式（宿主提供 AdminSessionBridge Bean）：以下三 Bean 全部不注册——
    // 管理端点由 AdminBridgeApiController 提供，登录/SSO 由宿主自己的认证体系承担
    @Bean
    @ConditionalOnMissingBean(AdminSessionBridge.class)
    public SessionRegistry sessionRegistry(AdminStore store) { return new SessionRegistry(store); }

    // OidcService 两种模式共用（桥接模式由 AdminBridgeOidcController 复用）
    @Bean
    @ConditionalOnMissingBean(OidcService.class)
    public OidcService oidcService(AdminStore store) { return new OidcService(store); }

    @Bean
    @ConditionalOnMissingBean(AdminSessionBridge.class)
    public AdminApiController adminApiController(
            AdminStore store, SessionRegistry sessions, OidcService oidc) {
        return new AdminApiController(store, sessions, oidc);
    }

    /** 桥接模式（宿主提供 AdminSessionBridge）：只注册管理面端点，登录/SSO 由宿主认证承担 */
    @Bean
    @ConditionalOnBean(AdminSessionBridge.class)
    public com.xzsoft.sketchadmin.web.AdminBridgeApiController adminBridgeApiController(
            AdminStore store, AdminSessionBridge bridge) {
        return new com.xzsoft.sketchadmin.web.AdminBridgeApiController(store, bridge);
    }

    /** 桥接模式通用 OIDC（默认开；宿主自带 OIDC 体系时关掉防路径冲突） */
    @Bean
    @ConditionalOnBean(AdminSessionBridge.class)
    @ConditionalOnProperty(name = "sketch-admin.bridge.oidc-endpoint", havingValue = "true", matchIfMissing = true)
    public com.xzsoft.sketchadmin.web.AdminBridgeOidcController adminBridgeOidcController(
            AdminStore store, AdminSessionBridge bridge, com.xzsoft.sketchadmin.oidc.OidcService oidc) {
        return new com.xzsoft.sketchadmin.web.AdminBridgeOidcController(store, bridge, oidc);
    }

    /** 桥接模式改密端点（默认开；宿主自带 /api/auth/password 时关掉防同路径冲突） */
    @Bean
    @ConditionalOnBean(AdminSessionBridge.class)
    @ConditionalOnProperty(name = "sketch-admin.bridge.password-endpoint", havingValue = "true", matchIfMissing = true)
    public com.xzsoft.sketchadmin.web.AdminBridgePasswordController adminBridgePasswordController(
            AdminStore store, AdminSessionBridge bridge) {
        return new com.xzsoft.sketchadmin.web.AdminBridgePasswordController(store, bridge);
    }

    private static void seedEnv(AdminStore store, String env, String key) {
        String v = System.getenv(env);
        if (v != null && !v.isBlank() && store.settingGet(key).isEmpty()) {
            store.settingSet(key, v.trim());
        }
    }
}
