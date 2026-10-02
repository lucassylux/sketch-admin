# 变更日志

## 0.2.0（2026-09-30）

### Java starter：桥接模式（宿主自研认证接入）

- 新增 `AdminSessionBridge` SPI：宿主实现一个 Bean（`resolve(request)` 返回 `AdminPrincipal(username, role)`，
  未登录返回 null），即可以「桥接模式」挂载 sketch-admin 管理面
- 桥接模式下自动装配只注册 `AdminBridgeApiController`（字典 / 审计 / 用户 / 会话设置四组端点，
  鉴权由桥接回调承担）；starter 自带的 `AdminApiController` / `OidcService` / `SessionRegistry`
  不再注册——宿主自己的 `/api/auth/*` 体系零路径冲突（首个接入方：sqlgoose，认证走 WatchGoose SDK JWT）
- 注册方式：控制器经 AutoConfiguration `@Bean @ConditionalOnBean(AdminSessionBridge.class)` 挂载
  （starter 包不在宿主组件扫描范围内，类路径扫描/条件注解注册均不可靠）

## 0.1.0

- 首版：Go（`go-admin` 纯库 + SQLite）与 Java（spring-boot-starter + H2）双实现，
  契约见 `contracts/api.md`；web 模板（Vue3 + TS + sketch-ui）
- go-admin：本地账号 bcrypt、SSO/OIDC（授权码+PKCE，未配置即休眠）、RBAC 三级、
  登录防爆破（同 IP 连续失败 5 次锁 15 分钟）、`ImportLocalUser` 存量账号直搬、SPA 静态托管
- Java starter：管理库连接池化（Hikari）、SPA 回退放行 `/error`、H2 保留字规避（setting_key/setting_value）

## 0.2.1（2026-10-02）

### 桥接模式补改密端点 + 模板下拉入口

- starter：`AdminBridgeApiController` 补 `POST /api/auth/password`（当前登录用户改自己密码，
  校验旧密/≥8 位；SSO 桥接账号无本地密码，400 提示去认证中心改）
- web 模板：账号下拉菜单新增"修改密码"（原/新/确认弹窗，复用契约端点）
