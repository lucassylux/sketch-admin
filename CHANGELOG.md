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

## 0.2.2（2026-10-02）

### 品牌外观配置（登录页 logo / 页脚文案进系统设置）

- 契约新增 `GET /api/settings/brand`（登录前公开读）与 `PUT /api/settings/brand`（admin）——
  桥接与独立两种模式均提供
- 管理面"系统设置 → 品牌外观"：logo SVG 源码（≤20KB，留空恢复默认）+ 页脚文案（≤200 字符），带实时预览
- 登录页读取配置渲染；SVG 经前端白名单消毒（仅保留绘图标签与属性，剥 script/事件属性）——
  v-html 渲染用户 SVG 是存储型 XSS 面，必须过滤

## 0.2.3（2026-10-02）

- 品牌字段升级：`logoSvg`（完整 Logo，登录页等大场景）+ `iconSvg`（方形图标，侧边栏/favicon）
  + `copyrightText`（版权文案，原 footText 改名）——SVG 矢量缩放，尺寸由使用场景 CSS 决定

## 0.2.4（2026-10-02）

- 品牌字段再扩：`appName`（应用名称，登录页标题/侧边栏/浏览器标签）+ `tagline`（副标题）；
  长度校验 应用名≤64 / 副标题≤128 / 版权≤200

### lettermark 通用兜底

- 新增 `web/src/utils/brandMark.ts`：品牌读取 + SVG 白名单消毒 + 首字标生成
  （Logo 未配置时按应用名首字动态生成 SVG 徽标，favicon 同样适用）
- BrandLogo / 登录页 / 侧边栏 / 系统设置"品牌信息"页全部接入：配置 SVG 优先，
  空则 lettermark（应用名 → 占位符名 → 首字符）

### 桥接模式通用 OIDC（0.2.5 内）

- 新增 `AdminBridgeOidcController`：任意 OIDC Provider 的登录/回调/配置端点
  （GET /api/auth/oidc/login|config、POST /api/auth/oidc/callback、GET/PUT /api/settings/oidc），
  复用独立模式 OidcService（discovery/PKCE/JWKS 验签/白名单），会话经 issueSession 由宿主签发
- `AdminSessionBridge.issueSession` SPI：宿主实现即可获得通用 OIDC 能力
- OidcService 增强：完整回调地址 oidc_redirect_uri 覆盖（SPA 回调路径非 /oidc/callback 时用）、
  scope 可配（oidc_scopes）、白名单支持 `*` 全放行；OidcService Bean 改两模式共用
- 桥接回调同名不拒：SSO 身份与本地账号同名时跳过落库直接签发（用户体系在宿主/IdP 侧）

## 0.2.6（2026-10-02）

- 会话时长改为**秒**口径：存储键 session_ttl_seconds、GET/PUT 返回/接收 ttlSeconds、
  SessionRegistry 运行时按秒；旧 session_ttl_hours 自动迁移（×3600），缺省 12 小时

## 0.2.7（2026-10-02）

- users 表新增 `status`（1=启用 0=禁用，存量库自动补列）；`PUT /api/users/{username}/status`
  启停端点（admin；不可禁用自己），禁用对本地登录与 SSO 登录同时生效
- `upsertSsoUser` 撞名不再拒绝：绑定 subject 到既有账号（同一账号支持本地密码 + SSO 双通道）
- 用户列表返回 status；OIDC 回调拒绝禁用账号
