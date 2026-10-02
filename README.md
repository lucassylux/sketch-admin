# sketch-admin

sketch-ui 管理后台脚手架：**一套前端模板 + 两种后端实现（Go / Java）**，五个项目（watchgoose / openvps / glm-usage-viewer / goose-backup / leakgoose）的登录页、管理外壳、系统设置、数据字典、审计日志在此一次成型。

```
sketch-admin/
├── contracts/api.md        # ★ API 契约——前后端可插拔的唯一事实源
├── web/                    # 前端模板（Vue3 + TS + sketch-ui，品牌占位可替换）
│   ├── 登录页 / 外壳（SkSidebar+面包屑+主题切换+账号下拉）
│   ├── 系统设置（SSO/会话/改密/关于）、数据字典、审计日志、用户管理
│   └── patches/            # sketch-ui 组件补丁（单选下拉隐藏多选操作条 + 表单校验取值回退）
├── go-admin/               # Go 后端库（SQLite 纯 Go 驱动；go test 全绿）
├── java/
│   ├── sketch-admin-spring-boot-starter/   # Java 后端 starter（H2 单文件；契约冒烟通过）
│   └── demo/               # 示例应用：引依赖即得全套端点
└── init.sh                 # 从模板生成新项目的脚手架脚本
```

## 快速开始

### 生成新项目

```bash
./init.sh my-app "我的应用" "一句话副标题" ~/code/my-app go
# 产物：~/code/my-app/{web,server}，品牌占位符已全部替换
```

### 后端二选一（或都跑）

**Go**（`go-admin` 是库，两行接入）：

```go
store, generated, _ := admin.Open("myapp.db", "") // 首启随机 admin 口令一次性返回
defer store.Close()
srv := admin.NewServer(store, uiFS)               // uiFS 传内嵌前端；nil 则纯 API
http.ListenAndServe(":8280", srv.Handler())
```

**Java**（starter 自动装配）：

```xml
<dependency>
  <groupId>com.xzsoft</groupId>
  <artifactId>sketch-admin-spring-boot-starter</artifactId>
  <version>0.2.0</version>
</dependency>
```

```yaml
sketch-admin:
  db-path: data/myapp-admin     # H2 单文件
  # seed-admin-password: 留空则随机生成并日志打印一次
```

**已有自研认证的应用（桥接模式）**：宿主实现一个 Bean 即可只挂管理端点、不动宿主登录体系：

```java
@Bean
AdminSessionBridge bridge() {
    return request -> {
        var user = MyAuth.currentUser(request);   // 宿主自己的 JWT/会话解析
        if (user == null) return null;            // 未登录 → 管理端点回 401
        return AdminPrincipal.of(user.getName(), "admin"); // role: admin/editor/viewer
    };
}
```

提供该 Bean 后 starter 自动切换：只注册字典/审计/用户/会话设置/品牌外观/改自己密码六组端点（鉴权走桥），
starter 自带的登录/SSO/会话端点不再注册（与宿主 `/api/auth/*` 零冲突）。

### 前端

```bash
cd web
npm install        # postinstall 自动应用 sketch-ui 补丁
npm run dev        # 65175，代理 /api → 127.0.0.1:8280（改 vite.config.js 按需）
```

替换品牌：`src/brand.ts`（应用名/副标题）+ `src/components/BrandLogo.vue`（换成自己的 Logo）。

## 架构原则

1. **契约优先**：一切端点以 `contracts/api.md` 为准；Go/Java 两种实现行为对齐（错误格式 `{"error"}` + HTTP 状态码、cookie 名、RBAC 端点矩阵）
2. **模板而非库（前端）**：页面代码归项目所有，改占位语/列宽零摩擦；模板演进靠本仓库的升级指南
3. **库而非模板（后端）**：业务端点由项目自建挂在同一路由树，复用会话与 RBAC 中间件
4. **单文件零运维**：Go 用 SQLite（纯 Go 驱动），Java 用 H2；内存会话（单实例，重启重新登录可接受）

## 内置能力（契约范围）

- 认证：本地账号（bcrypt）+ SSO/OIDC（授权码+PKCE，未配置即休眠，按 sub 绑定防同名接管）
- RBAC：admin / editor / viewer 三级
- 系统设置：SSO 整组可视化维护、会话时长（1-168h）、本地账号自助改密
- 数据字典：类型/项 CRUD，label 中文展示 + value 编码落库，内置 rule-severity 种子
- 审计日志：全部写操作留痕（新→旧 500 条）
- 用户管理：列表/新建/角色调整/重置口令/删除（最后管理员保护、不可删自己）
