# sketch-admin API 契约（v1）

> 前后端可插拔的唯一事实源：web 模板按此消费，Go/Java 后端按此实现。
> 约定：全部 JSON；**错误统一 HTTP 状态码 + `{"error":"中文原因"}`**（无 code 外包层）；
> 会话 cookie 名 `sk_admin_session`（HttpOnly/SameSite=Lax）；写请求带非同源 Origin 一律 403。

## 通用
| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/health` | 无 | `{"status":"ok"}` 探活 |

## 认证
| 方法 | 路径 | 鉴权 | 请求/响应 |
|---|---|---|---|
| POST | `/api/auth/login` | 无 | `{username,password}` → `{"username","role"}` + Set-Cookie |
| POST | `/api/auth/logout` | 无 | 清会话 |
| GET | `/api/auth/me` | 登录 | `{"username","role"}` |
| POST | `/api/auth/password` | 登录 | `{oldPassword,newPassword}`；SSO 绑定账号 400；新密 ≥8 位；旧密错 401 |

角色：`admin`（全权）＞ `editor`（业务数据维护）＞ `viewer`（只读）。后端按端点声明最低角色。

## SSO / OIDC（未配置 issuer+clientId 时整套休眠，登录页按钮隐藏）
| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/auth/oidc/config` | 无 | `{"enabled","logoutUrl"}` |
| GET | `/api/auth/oidc/login?prompt=` | 无 | 302 → Provider 授权端点（授权码+PKCE S256） |
| POST | `/api/auth/oidc/callback` | 无 | `{code,state}` → 与本地登录同会话 cookie；白名单外 403 |
| GET | `/api/auth/oidc/settings` | admin | `{"issuer","clientId","clientSecretSet","redirectBase","allowedUsers","enabled"}`（secret 值永不回传） |
| PUT | `/api/auth/oidc/settings` | admin | 字段 nil 不改/空串清除；secret `"-"`=清除；issuer 形状校验 400 |

SSO 语义：账号按 OIDC `sub` 绑定（同名本地账号拒绝接管）；白名单内固定 `editor` 角色；环境变量
`SK_ADMIN_OIDC_ISSUER/CLIENT_ID/CLIENT_SECRET/ALLOWED_USERS/REDIRECT_BASE` 首启注入（已有值不覆盖）。

## 会话设置
| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/settings/session` | admin | `{"ttlHours"}`（1-168，缺省 12） |
| PUT | `/api/settings/session` | admin | `{ttlHours}`；对新登录生效 |

## 数据字典
| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/dict-types` | 登录 | `[{type,name,count}]` |
| GET | `/api/dicts?type=&enabled=true` | 登录 | `[{id,type,label,value,sort,enabled}]` 按 sort,id |
| POST | `/api/dicts` | editor | 同上单对象（id 空=新增）；同类型 value 唯一冲突 500 |
| DELETE | `/api/dicts/{id}` | editor | |

内置类型种子：`rule-severity`：严重/critical、高/high、中/medium、低/low（label 中文展示、value 编码落库）。

## 审计
| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/audit` | admin | `[{id,at,actor,action,entity,detail}]` 新→旧，默认 500 条 |

## 用户管理
| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/users` | admin | `[{username,role,source,createdAt,lastLoginAt}]`（source: local/oidc） |
| POST | `/api/users` | admin | `{username,password,role}`；密码 ≥8 位 |
| PUT | `/api/users/{username}/role` | admin | `{role}` |
| POST | `/api/users/{username}/password` | admin | `{newPassword}` 管理员重置 |
| DELETE | `/api/users/{username}` | admin | 不可删自己/最后一个 admin |

## 项目自定义端点
业务端点（规则/虚拟机/备份任务等）由项目自建，挂在同一路由树即可复用会话与 RBAC 中间件。
