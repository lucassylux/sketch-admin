package com.xzsoft.sketchadmin;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.xzsoft.sketchadmin.oidc.OidcService;
import com.xzsoft.sketchadmin.store.AdminStore;
import com.xzsoft.sketchadmin.web.AdminApiController;
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

    @Bean
    @ConditionalOnMissingBean
    public SessionRegistry sessionRegistry(AdminStore store) { return new SessionRegistry(store); }

    @Bean
    @ConditionalOnMissingBean
    public OidcService oidcService(AdminStore store) { return new OidcService(store); }

    @Bean
    @ConditionalOnMissingBean
    public AdminApiController adminApiController(
            AdminStore store, SessionRegistry sessions, OidcService oidc) {
        return new AdminApiController(store, sessions, oidc);
    }

    private static void seedEnv(AdminStore store, String env, String key) {
        String v = System.getenv(env);
        if (v != null && !v.isBlank() && store.settingGet(key).isEmpty()) {
            store.settingSet(key, v.trim());
        }
    }
}
