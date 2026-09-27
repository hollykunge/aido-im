# AIDo 5.0 后端服务

为前端（仓库根目录的 Vue 应用）提供消息、TODO、日程、记忆、搜索和小A助理的接口。

- Java 25 · Spring Boot 4.1 · Spring MVC（虚拟线程）· WebSocket 实时推送
- PostgreSQL 17 · Flyway 迁移 · Spring `JdbcClient`（直接写 SQL，不用 ORM）
- 错误统一返回 RFC 9457 Problem Details（`application/problem+json`）

## 快速开始

```bash
# 1. 启动数据库（映射到本机 55432 端口）
docker compose up -d

# 2. 启动服务（默认 demo profile：首次启动自动灌入演示数据）
mvn spring-boot:run

# 3. 试一下
curl localhost:8080/api/conversations
```

前端：在仓库根目录 `npm run dev`，Vite 会把 `/api` 转发到 `http://localhost:8080`（后端在别处时设置 `VITE_API_TARGET`）。
后端没启动时前端会显示「暂时无法加载」和重试按钮。

运行测试（用 Testcontainers 起一个临时 PostgreSQL，需要本机有 Docker）：

```bash
mvn test
```

### 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:55432/aido` / `aido` / `aido` | 数据库连接 |
| `PORT` | `8080` | 服务端口 |
| `SPRING_PROFILES_ACTIVE` | `demo` | 生产环境设为其他值，不会灌演示数据 |
| `AIDO_COOKIE_SECURE` | `false` | 生产环境走 HTTPS 时设为 `true`，会话 Cookie 加上 `Secure` |
| `aido.login-throttle.*` | 15 分钟内账号 5 次 / IP 20 次 | 登录失败限流 |
| `AIDO_CORS_ORIGINS` | demo 下为 `http://localhost:5173` | 允许跨域的前端地址，逗号分隔 |
| `AIDO_AGENT_ENABLED` | `true` | 设为 `false` 模拟小A不可用，AI 接口返回 503 |
| `aido.realtime.*` | 见 application.yml | 心跳间隔、空闲断开时间、每条连接的发送队列上限、每人最大连接数 |

## 登录鉴权

服务端会话 + `HttpOnly` Cookie，会话存在 PostgreSQL（Spring Session JDBC）：服务重启不掉登录，多实例共享。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | `{login, password}`，成功返回当前用户并下发 `AIDO_SESSION` Cookie |
| POST | `/api/auth/logout` | 结束会话，同时断开这个会话的 WebSocket；返回 204 |
| POST | `/api/me/password` | `{currentPassword, newPassword}` 修改自己的密码；返回 204 |

- **Cookie**：`AIDO_SESSION` 为 `HttpOnly; SameSite=Lax`，两周不活动过期；生产环境走 HTTPS 时设 `AIDO_COOKIE_SECURE=true` 加上 `Secure`。
- **密码**：bcrypt，哈希带 `{bcrypt}` 算法前缀；以后升级算法时，旧哈希在用户下次登录成功时自动换新。
- **CSRF**：Spring Security 的 SPA 模式。每个响应带可读的 `XSRF-TOKEN` Cookie，写请求（POST/PATCH/DELETE）要在 `X-XSRF-TOKEN` 头里原样带回，否则 403。
- **会话固定**：登录成功时更换会话 id 和 CSRF 令牌。
- **限流**：同一账号 15 分钟内失败 5 次、同一 IP 失败 20 次后暂时拒绝（429，带 `Retry-After`）。账号不存在和密码错误返回同样的提示，不透露账号是否存在。
- **停用**：`app_user.disabled = true` 的账号不能登录（403）。
- **修改密码**：要验证当前密码，输错和登录失败一起计入限流。新密码规则参照 NIST SP 800-63B：至少 8 位、不超过 72 字节（bcrypt 的上限）、不能和当前密码相同、不能包含账号名、不能是常见弱密码（含演示初始密码），不强制符号组合。改成功后该用户其他所有登录会话立即失效、推送断开；当前会话保留但更换会话 id（推送以关闭码 4012 断开后自动重连）。当前密码错误返回 400 而不是 401，免得前端当成会话失效。
- 未登录访问 `/api/**` 返回 401，被拒返回 403，都是 Problem Details JSON。`/actuator/health` 不需要登录。
- 登录后安全上下文里的用户名就是用户 id，controller 用 `@CurrentUser String me` 取；所有数据按用户隔离（看不到别人的 TODO、记忆，读不到自己不在其中的会话，统一 404）。

**演示账号**（仅 demo profile，见 `db/demo/V101__demo_accounts.sql`）：账号为姓名拼音——`linzhou`（林舟）、`zhouyuan`、`chensiqi`、`wanglei`、`zhaoyifan`、`linan`、`sunyue`、`hejing`，初始密码都是 `aido1234`。

还没有注册、找回密码；新账号目前需要直接在库里写入 `login` 和 `password_hash`。

## 目录结构

```
src/main/java/com/aido/server/
  common/     当前用户解析、错误处理、时区与「今天/明天」换算、跨域
  user/       用户、通讯录、个人偏好（主题、小A形象）
  workspace/  顶部 tab 角标
  chat/       会话、消息、已读、私聊、小A摘要与建议回复
  todo/       TODO、从消息生成、批量确认/忽略、重新整理
  calendar/   日程
  memory/     记忆：画像标签、具体记忆、学习来源
  search/     全局搜索、最近搜索
  agent/      小A：对话流、提议卡片、草稿卡片、自动化能力开关
  realtime/   WebSocket 实时推送：握手票据、连接表、心跳、事务提交后分发事件
src/main/resources/db/
  migration/  表结构（所有环境）
  demo/       演示数据（仅 demo profile）
```

## 数据库

| 表 | 用途 |
| --- | --- |
| `app_user` | 人：姓名、角色、部门、头像色 |
| `user_preference` | 主题、小A形象（jsonb）、是否允许从日常工作中学习 |
| `conversation` | 私聊 / 群聊 |
| `conversation_member` | 成员及其视角的会话状态：置顶、免打扰、未读数、小A精选标记 |
| `message` / `message_mention` | 消息（正文或附件）及被 @ 的人 |
| `conversation_summary` | 小A对会话的未读摘要 |
| `reply_suggestion` | 小A的快捷回复（quick）和起草的整段回复（draft） |
| `todo` | TODO：suggested 待确认 / open / done / dismissed，可追溯到来源消息 |
| `todo_candidate` | 小A识别出、「重新整理」时才放进待确认的候选事项 |
| `calendar_event` | 日程及小A的会前提示 |
| `memory_profile_tag` / `memory_item` / `memory_source` | 记忆页的画像、具体记忆、学习来源 |
| `agent_capability` | 小A的自动化能力开关 |
| `agent_feed_item` | 小A对话流，不同卡片的内容放在 `payload`（jsonb） |
| `agent_quick_prompt` | 小A输入框上方的快捷指令 |
| `search_history` | 最近搜索（保留 6 条） |

搜索用 `ILIKE` 做子串匹配（中文不需要分词），`pg_trgm` 的 GIN 索引在关键词 ≥ 3 个字时加速。

演示数据里的时间都相对「今天」生成，哪天启动都像当天的工作现场。截止时间的展示文案（「今天」「明天」「周三」「9月28日」）由后端按业务时区实时换算，只有小A给的模糊说法（「14:00 前」「待定」）才存进 `due_label`。

## 接口

所有接口以 `/api` 开头，时间为 ISO 8601。

### 用户与偏好

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/me` | 当前用户，另含本人的登录账号 `login` 和本次登录时间 `loggedInAt` |
| PATCH | `/me` | `{name?, color?}` 改自己的姓名（1–20 字）和头像颜色（`#RRGGBB`）；推送 `user.updated` 给所有在线用户 |
| GET | `/users` · `/users/{id}` | 通讯录 |
| GET · PATCH | `/me/preferences` | `{theme, agentLook, memoryLearning}`，PATCH 只改传了的字段 |
| GET | `/workspace/counters` | `{unreadTotal, openTodoCount, suggestedCount}`，tab 角标 |

### 消息

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/conversations?filter=all\|unread\|flag` | 消息坞，置顶在前；`flag` 为小A精选 |
| GET | `/conversations/groups` | 群组分栏 |
| GET | `/conversations/{id}` | 会话详情 |
| POST | `/conversations/dm` | `{userId}` 打开或新建私聊 |
| GET | `/conversations/{id}/messages?before=&limit=50` | 消息按时间正序；每条带 `mentionsMe` 和关联的 `todo` |
| POST | `/conversations/{id}/messages` | `{text}` 发送；对方未读 +1，解析 `@名字` |
| POST | `/conversations/{id}/read` | 标记已读 |
| GET | `/conversations/{id}/summary` | 小A摘要，没有时 204 |
| GET | `/conversations/{id}/suggested-replies` | 建议回复 |

### TODO

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/todos?status=suggested,open,done` | 默认不含已忽略；每项带 `today`、`due` 展示文案和 `source` 来源消息 |
| GET | `/todos/{id}` | |
| POST | `/todos` | `{title, priority?, today?, dueAt?}` 手动新建，默认放进今天 |
| POST | `/todos/from-message` | `{messageId}` 聊天里「转为 TODO」，重复转换返回 409 |
| PATCH | `/todos/{id}` | `{title?, status?, priority?, today?, dueLabel?, dueAt?, note?}` |
| POST | `/todos/status` | `{ids, status}` 批量确认（open）/ 忽略（dismissed） |
| POST | `/todos/rescan` | 小A重新整理：找到新事项 200，已是最新 204 |

### 日程 · 记忆 · 搜索

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/schedule?date=2026-09-25` | 某天日程，默认今天；带 `done`、`now` |
| GET | `/memory` | `{learning, profile, items, sources}` |
| POST · DELETE | `/memory/profile-tags` · `/memory/profile-tags/{id}` | 画像标签 |
| POST · PATCH · DELETE | `/memory/items` · `/memory/items/{id}` | 具体记忆 |
| PATCH | `/memory/sources/{key}` | `{enabled}` 学习来源开关 |
| GET | `/search?q=&scope=all&perSection=3&includeSuggested=true` | 各类 `{total, items}`；`all` 时每类最多 `perSection` 条 |
| GET · POST · DELETE | `/search/history` | 最近搜索 |

### 小A

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/agent` | `{name, tagline, available, quickPrompts}` |
| GET | `/agent/feed?limit=100` | 对话流，按时间正序 |
| POST | `/agent/messages` | `{text, context: {view, label, conversationId}}` → `{steps, items}` |
| POST | `/agent/feed/{id}/accept` · `/dismiss` | 确认 / 忽略提议卡片里的 TODO |
| POST | `/agent/feed/{id}/draft/insert` · `/draft/dismiss` | 草稿放进输入框 / 不用了 |
| POST | `/agent/feed/{id}/draft/send` | `{text?}` 直接发送草稿，并勾掉该会话里需要回复的 TODO |
| GET · PATCH | `/agent/capabilities` · `/agent/capabilities/{key}` | 自动化能力开关 |

小A不可用（`AIDO_AGENT_ENABLED=false`）时，对话流和对话相关接口返回 503，前端据此隐藏 AI 元素，其余功能照常。

## 实时推送（WebSocket）

写操作仍然走 HTTP 接口；WebSocket 只做服务端 → 浏览器的推送，客户端只回心跳。

**连接**
1. 已登录的会话 `POST /api/realtime/ticket` 换一张一次性票据（30 秒内有效，只能用一次）。比直接靠 Cookie 握手多一层：别的网站即使诱导浏览器发起 WebSocket 连接（CSWSH），也拿不到票据。
2. 连接 `ws(s)://<host>/api/realtime?ticket=…&client=<标签页 id>`。票据无效时握手返回 401。
3. 连上后先收到 `hello`；之后服务端每 25 秒发一次 `ping`，客户端回 `{"type":"pong"}`。60 秒没有任何回音的连接会被断开。

**事件**（`{"type": "...", "data": {...}}`，都在数据库事务提交之后才推，回滚的改动不会推出去）

| type | data | 推给谁 |
| --- | --- | --- |
| `message.created` | `{message, unread}` | 会话所有成员；`unread`、`message.mentionsMe` 按收件人计算 |
| `conversation.read` | `{conversationId}` | 本人的其他标签页 |
| `todos.changed` | `{todos}` | 本人的其他标签页 |
| `agent.items` | `{items}` | 本人的其他标签页（小A对话流新增或更新的卡片） |
| `user.updated` | `{user}` | 所有在线用户（某人改了姓名或头像颜色；不含登录账号） |
| `memory.changed` | 记忆页完整快照 `{learning, profile, items, sources}` | 本人的其他标签页 |
| `preferences.changed` | `{theme, agentLook, memoryLearning}` | 本人的其他标签页 |
| `capabilities.changed` | `{capabilities}` | 本人的其他标签页（小A能力开关） |

记忆、偏好、能力开关都很小，每次推整份快照，客户端直接替换，不用处理增量合并。前端记着后端当前保存的偏好值，收到推送后本地值和它一致就不再回写，避免多个标签页之间来回保存。

HTTP 请求带 `X-Client-Id`（标签页 id），推送时跳过发起这次改动的标签页，它已经从 HTTP 响应里拿到结果了。

**可靠性**
- 每条连接一个有界发送队列 + 专属写线程（虚拟线程）：推送方只入队，不会被慢客户端卡住；同一连接内消息有序。队列积压超过上限（默认 256）即断开（关闭码 4008），客户端重连后重新拉取。
- 客户端断线后指数退避 + 随机抖动重连（1s → 30s），网络恢复或标签页回到前台时立即重连；**每次连上都重新拉一次数据**，补上断线期间漏掉的事件。
- 同一用户最多 8 条连接，超出时挤掉最久没动静的一条（关闭码 4009，客户端不自动重连）。
- 票据记着签发它的登录会话：退出登录立即断开该会话的连接；会话过期后每分钟检查一次并断开（关闭码 4401，客户端回到登录页）。
- 停机时向所有连接发 1001（Going Away），客户端退避后重连到新实例。
- 在线连接数暴露为指标 `aido.realtime.connections`。

**容量**：`RealtimeIntegrationTest` 里的压测：100 人同时在线，在同一个群里各连发 5 条（500 条消息、50000 次推送），本机约 1.7 秒全部送达，端到端延迟 p50 约 50 ms、p99 约 165 ms。

**横向扩容**：现在是单实例（连接表在内存里）。多实例部署时，在 `RealtimeDispatcher` 前加一层跨实例广播（Redis Pub/Sub 或 PostgreSQL `LISTEN/NOTIFY`），每个实例只推给自己持有的连接即可，其余代码不用改。

## 小A的实现

`agent/AgentBrain` 是接口，现在的 `RuleBasedAgentBrain` 按关键词识别意图（总结会话、今天的会、起草回复、TODO 排序、整理待办、记忆），行为与前端原来的 `stores/agent.js` 一致，数据改为读库。接入大模型时实现这个接口替换即可，接口和前端都不用改。

## 还没做的

- 注册、找回密码
- 多实例部署时的跨实例推送广播（见上文「横向扩容」）
- 文件上传与下载（现在只存附件名和大小）
- 小A接入大模型、定时任务（早间未读整理、周报草拟）
