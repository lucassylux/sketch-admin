package com.xzsoft.sketchadmin.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 宿主会话桥接 SPI：已有自研认证（如 WatchGoose SDK JWT）的应用接入 sketch-admin 管理面时实现。
 * 宿主提供此 Bean 后，自动装配改走 {@link AdminBridgeApiController}——
 * 只注册字典/审计/用户/会话设置四组管理端点（鉴权由宿主桥接），不再注册 starter 自带的登录/SSO 端点
 * （宿主认证体系不动，无路径冲突）。
 */
public interface AdminSessionBridge {

    /**
     * 从当前请求解析操作者；未登录返回 null（由桥接控制器回 401）。
     *
     * @param request 当前请求（宿主自行从 header/SecurityContext/自身会话体系取身份）
     * @return 操作者（username 必填；role 为 sketch-admin 三角色，宿主自行映射）
     */
    AdminPrincipal resolve(HttpServletRequest request);

    /**
     * 桥接模式 OIDC 登录成功后由宿主签发自己的会话凭证（返回给前端保存，如 JWT）。
     * 默认抛 Unsupported——宿主未实现 OIDC 桥接时该流程不可用。
     */
    default String issueSession(AdminPrincipal p) {
        throw new UnsupportedOperationException("宿主未实现 issueSession，桥接 OIDC 登录不可用");
    }

    /** 桥接操作者（username 必填；role 缺省 viewer） */
    record AdminPrincipal(String username, String role) {

        public String roleOrDefault() {
            return (role == null || role.isBlank()) ? "viewer" : role;
        }

        public static AdminPrincipal of(String username, String role) {
            return new AdminPrincipal(username, role);
        }
    }
}
