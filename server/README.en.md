# AIDo 5.0 Backend

[简体中文](README.md) | English

Serves the frontend (the Vue app at the repository root): messages, TODOs, schedule, memory, search, and the Xiao A (小A) assistant.

- Java 25 · Spring Boot 4.1 · Spring MVC (virtual threads) · WebSocket real-time push
- PostgreSQL 17 · Flyway migrations · Spring `JdbcClient` (plain SQL, no ORM)
- Errors are always RFC 9457 Problem Details (`application/problem+json`)

## Quick start

```bash
# 1. Start the database (mapped to local port 55432)
docker compose up -d

# 2. Start the service (demo profile by default: loads demo data on first start)
mvn spring-boot:run

# 3. Try it
curl localhost:8080/api/conversations
```

Frontend: run `npm run dev` at the repository root. Vite proxies `/api` to `http://localhost:8080` (set `VITE_API_TARGET` if the backend runs elsewhere).
If the backend is down, the frontend shows a "cannot load" message with a retry button.

Run the tests (Testcontainers starts a throwaway PostgreSQL; requires Docker locally):

```bash
mvn test
```

### Configuration

| Environment variable | Default | Description |
| --- | --- | --- |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:55432/aido` / `aido` / `aido` | Database connection |
| `PORT` | `8080` | Server port |
| `SPRING_PROFILES_ACTIVE` | `demo` | Set to something else in production so no demo data is loaded |
| `AIDO_COOKIE_SECURE` | `false` | Set to `true` behind HTTPS in production to add `Secure` to the session cookie |
| `AIDO_FILES_DIR` | `./data/files` | Directory where attachments are stored |
| `aido.login-throttle.*` | 5 per account / 20 per IP within 15 minutes | Failed-login throttling |
| `AIDO_CORS_ORIGINS` | `http://localhost:5173` under demo | Allowed frontend origins, comma-separated |
| `AIDO_AGENT_ENABLED` | `true` | Set to `false` to simulate Xiao A being unavailable; AI endpoints return 503 |
| `aido.realtime.*` | see application.yml | Heartbeat interval, idle timeout, per-connection send queue limit, max connections per user |

## Authentication

Server-side sessions with an `HttpOnly` cookie; sessions are stored in PostgreSQL (Spring Session JDBC), so logins survive restarts and are shared across instances.

| Method | Path | Description |
| --- | --- | --- |
| POST | `/api/auth/login` | `{login, password}`; on success returns the current user and sets the `AIDO_SESSION` cookie |
| POST | `/api/auth/logout` | Ends the session and closes that session's WebSockets; returns 204 |
| POST | `/api/me/password` | `{currentPassword, newPassword}` changes your own password; returns 204 |

- **Cookie**: `AIDO_SESSION` is `HttpOnly; SameSite=Lax` and expires after two weeks of inactivity; set `AIDO_COOKIE_SECURE=true` behind HTTPS in production to add `Secure`.
- **Passwords**: bcrypt, stored with the `{bcrypt}` algorithm prefix; if the algorithm is upgraded later, old hashes are re-hashed on the user's next successful login.
- **CSRF**: Spring Security SPA mode. Every response carries a readable `XSRF-TOKEN` cookie; write requests (POST/PATCH/DELETE) must echo it in the `X-XSRF-TOKEN` header, or they get 403.
- **Session fixation**: the session id and CSRF token are rotated on successful login.
- **Throttling**: after 5 failures for one account or 20 failures from one IP within 15 minutes, logins are temporarily refused (429 with `Retry-After`). Unknown accounts and wrong passwords get the same message, so account existence is not revealed.
- **Disabled accounts**: accounts with `app_user.disabled = true` cannot log in (403).
- **Changing the password**: requires the current password; wrong attempts count toward the same throttle as failed logins. New-password rules follow NIST SP 800-63B: at least 8 characters, at most 72 bytes (the bcrypt limit), not the same as the current password, must not contain the account name, and must not be a common weak password (including the demo initial password); no forced symbol mix. On success, all of that user's other sessions are invalidated immediately and their push connections closed; the current session is kept but gets a new session id (its push connection closes with code 4012 and reconnects automatically). A wrong current password returns 400 rather than 401, so the frontend doesn't mistake it for an expired session.
- Unauthenticated requests to `/api/**` get 401 and forbidden ones 403, both as Problem Details JSON. `/actuator/health` needs no login.
- After login, the username in the security context is the user id; controllers get it via `@CurrentUser String me`. All data is isolated per user (you can't see other people's TODOs or memories, and conversations you are not a member of return 404).

**Demo accounts** (demo profile only; see `db/demo/V101__demo_accounts.sql`): logins are the pinyin of each person's name: `linzhou` (林舟), `zhouyuan`, `chensiqi`, `wanglei`, `zhaoyifan`, `linan`, `sunyue`, `hejing`. The initial password for all of them is `aido1234`.

There is no sign-up or password reset yet; new accounts currently have to be inserted into the database directly with `login` and `password_hash`.

## Project layout

```
src/main/java/com/aido/server/
  common/     current-user resolution, error handling, time zone and "today/tomorrow" conversion, CORS
  user/       users, directory, personal preferences (theme, Xiao A look)
  workspace/  top tab badges
  chat/       conversations, messages, read state, direct chats, Xiao A summaries and suggested replies
  files/      attachment upload, download and storage
  todo/       TODOs, creating from messages, bulk confirm/dismiss, rescan
  calendar/   schedule
  memory/     memory: profile tags, specific memories, learning sources
  search/     global search, recent searches
  agent/      Xiao A: feed, proposal cards, draft cards, automation capability toggles
  realtime/   WebSocket push: handshake tickets, connection registry, heartbeat, dispatch after commit
src/main/resources/db/
  migration/  schema (all environments)
  demo/       demo data (demo profile only)
```

## Database

| Table | Purpose |
| --- | --- |
| `app_user` | People: name, role, department, avatar color |
| `user_preference` | Theme, Xiao A look (jsonb), whether learning from daily work is allowed |
| `conversation` | Direct and group chats |
| `conversation_member` | Members and their view of the conversation: pinned, muted, unread count, Xiao A highlight flag |
| `message` / `message_mention` | Messages (text or attachment) and the people @-mentioned |
| `conversation_summary` | Xiao A's summary of unread messages in a conversation |
| `reply_suggestion` | Xiao A's quick replies (quick) and drafted full replies (draft) |
| `todo` | TODOs: suggested (to confirm) / open / done / dismissed, traceable to the source message |
| `todo_candidate` | Candidate items Xiao A found, moved into "to confirm" only on rescan |
| `calendar_event` | Schedule entries and Xiao A's pre-meeting notes |
| `memory_profile_tag` / `memory_item` / `memory_source` | The memory page's profile, specific memories and learning sources |
| `agent_capability` | Xiao A's automation capability toggles |
| `agent_feed_item` | Xiao A's feed; each card's content lives in `payload` (jsonb) |
| `agent_quick_prompt` | Quick prompts above Xiao A's input box |
| `search_history` | Recent searches (the latest 6 are kept) |

Search uses `ILIKE` substring matching (Chinese needs no tokenization); a `pg_trgm` GIN index speeds it up once the keyword has 3 or more characters.

All times in the demo data are generated relative to "today", so whichever day you start it, it looks like a live workday. Due-date labels ("today", "tomorrow", "Wednesday", "Sep 28") are computed by the backend in the business time zone at request time; only Xiao A's vague phrasings (such as "before 14:00" or "TBD") are stored in `due_label`.

## API

All endpoints start with `/api`; times are ISO 8601.

### Users and preferences

| Method | Path | Description |
| --- | --- | --- |
| GET | `/me` | Current user, plus your own login name `login` and this login's time `loggedInAt` |
| PATCH | `/me` | `{name?, color?}` changes your display name (1–20 characters) and avatar color (`#RRGGBB`); pushes `user.updated` to all online users |
| GET | `/users` · `/users/{id}` | Directory |
| GET · PATCH | `/me/preferences` | `{theme, agentLook, memoryLearning}`; PATCH only changes the fields you send |
| GET | `/workspace/counters` | `{unreadTotal, openTodoCount, suggestedCount}` for tab badges |

### Messages

| Method | Path | Description |
| --- | --- | --- |
| GET | `/conversations?filter=all\|unread\|flag` | Inbox, pinned first; `flag` means highlighted by Xiao A |
| GET | `/conversations/groups` | Groups tab |
| GET | `/conversations/{id}` | Conversation details |
| POST | `/conversations/dm` | `{userId}` opens or creates a direct chat |
| GET | `/conversations/{id}/messages?before=&limit=50` | Messages in chronological order; each includes `mentionsMe` and its linked `todo` |
| POST | `/conversations/{id}/messages` | `{text}` sends a message; the other members' unread count goes up by 1; `@name` is parsed |
| POST | `/conversations/{id}/files` | `multipart/form-data` with field `file`, up to 50 MB each; uploads and sends it as an attachment message; returns 201 |
| GET | `/files/{messageId}` | Downloads an attachment, visible to conversation members only; safe image formats are shown inline (thumbnails), everything else, or any file with `?download`, is served as a download |
| POST | `/conversations/{id}/read` | Marks as read |
| GET | `/conversations/{id}/summary` | Xiao A's summary; 204 if there is none |
| GET | `/conversations/{id}/suggested-replies` | Suggested replies |

### TODO

| Method | Path | Description |
| --- | --- | --- |
| GET | `/todos?status=suggested,open,done` | Dismissed items are excluded by default; each item includes the `today` flag, a `due` label and its `source` message |
| GET | `/todos/{id}` | |
| POST | `/todos` | `{title, priority?, today?, dueAt?}` creates one manually, placed in Today by default |
| POST | `/todos/from-message` | `{messageId}` "Convert to TODO" from chat; converting the same message twice returns 409 |
| PATCH | `/todos/{id}` | `{title?, status?, priority?, today?, dueLabel?, dueAt?, note?}` |
| POST | `/todos/status` | `{ids, status}` bulk confirm (open) / dismiss (dismissed) |
| POST | `/todos/rescan` | Xiao A rescans: 200 if new items were found, 204 if already up to date |

### Schedule · Memory · Search

| Method | Path | Description |
| --- | --- | --- |
| GET | `/schedule?date=2026-09-25` | Schedule for a day, today by default; includes `done` and `now` |
| GET | `/memory` | `{learning, profile, items, sources}` |
| POST · DELETE | `/memory/profile-tags` · `/memory/profile-tags/{id}` | Profile tags |
| POST · PATCH · DELETE | `/memory/items` · `/memory/items/{id}` | Specific memories |
| PATCH | `/memory/sources/{key}` | `{enabled}` toggles a learning source |
| GET | `/search?q=&scope=all&perSection=3&includeSuggested=true` | `{total, items}` per category; with `all`, at most `perSection` items per category |
| GET · POST · DELETE | `/search/history` | Recent searches |

### Xiao A

| Method | Path | Description |
| --- | --- | --- |
| GET | `/agent` | `{name, tagline, available, quickPrompts}` |
| GET | `/agent/feed?limit=100` | Feed in chronological order |
| POST | `/agent/messages` | `{text, context: {view, label, conversationId}}` → `{steps, items}` |
| POST | `/agent/feed/{id}/accept` · `/dismiss` | Confirms / dismisses the TODOs in a proposal card |
| POST | `/agent/feed/{id}/draft/insert` · `/draft/dismiss` | Puts a draft into the input box / discards it |
| POST | `/agent/feed/{id}/draft/send` | `{text?}` sends the draft directly and ticks off that conversation's reply TODO |
| GET · PATCH | `/agent/capabilities` · `/agent/capabilities/{key}` | Automation capability toggles |

When Xiao A is unavailable (`AIDO_AGENT_ENABLED=false`), the feed and chat endpoints return 503; the frontend then hides AI elements and everything else keeps working.

## Real-time push (WebSocket)

Writes still go through the HTTP API; the WebSocket only carries server → browser pushes, and the client only answers heartbeats.

**Connecting**
1. A logged-in session calls `POST /api/realtime/ticket` to get a one-time ticket (valid for 30 seconds, usable once). This adds a layer on top of cookie-based handshakes: even if another site tricks the browser into opening a WebSocket (CSWSH), it cannot get a ticket.
2. Connect to `ws(s)://<host>/api/realtime?ticket=…&client=<tab id>`. An invalid ticket gets 401 on the handshake.
3. The first frame is `hello`; after that the server sends a `ping` every 25 seconds and the client replies `{"type":"pong"}`. Connections that stay silent for 60 seconds are closed.

**Events** (`{"type": "...", "data": {...}}`, always pushed after the database transaction commits, so rolled-back changes are never pushed)

| type | data | Sent to |
| --- | --- | --- |
| `message.created` | `{message, unread}` | All conversation members; `unread` and `message.mentionsMe` are computed per recipient |
| `conversation.read` | `{conversationId}` | Your other tabs |
| `todos.changed` | `{todos}` | Your other tabs |
| `agent.items` | `{items}` | Your other tabs (cards added to or updated in the Xiao A feed) |
| `user.updated` | `{user}` | All online users (someone changed their name or avatar color; login name excluded) |
| `memory.changed` | Full memory page snapshot `{learning, profile, items, sources}` | Your other tabs |
| `preferences.changed` | `{theme, agentLook, memoryLearning}` | Your other tabs |
| `capabilities.changed` | `{capabilities}` | Your other tabs (Xiao A capability toggles) |

Memory, preferences and capability toggles are small, so each push is a full snapshot that the client simply replaces; no incremental merging. The frontend remembers the preference values the backend currently has, and when a push arrives that matches the local value it doesn't write it back, which prevents tabs from saving back and forth.

HTTP requests carry `X-Client-Id` (the tab id), and pushes skip the tab that made the change, since it already has the result from the HTTP response.

**Reliability**
- Each connection has a bounded send queue plus its own writer thread (a virtual thread): senders only enqueue and are never blocked by slow clients, and messages stay ordered per connection. If the backlog exceeds the limit (256 by default) the connection is closed (code 4008) and the client reloads after reconnecting.
- After a disconnect, the client reconnects with exponential backoff plus random jitter (1s → 30s), and immediately when the network comes back or the tab returns to the foreground; **it reloads data on every connect** to catch up on events missed while offline.
- Each user can have at most 8 connections; beyond that the longest-idle one is evicted (code 4009, and the client does not reconnect automatically).
- Tickets remember the login session that issued them: logging out closes that session's connections immediately; expired sessions are checked every minute and their connections closed (code 4401, the client goes back to the login page).
- On shutdown, all connections get 1001 (Going Away) and clients reconnect to the new instance after backing off.
- The number of live connections is exposed as the metric `aido.realtime.connections`.

**Capacity**: the load test in `RealtimeIntegrationTest` has 100 users online at once, each sending 5 messages in the same group (500 messages, 50,000 pushes). On a local machine everything is delivered in about 1.7 seconds, with end-to-end latency around 50 ms at p50 and 165 ms at p99.

**Scaling out**: it currently runs as a single instance (the connection registry is in memory). For multiple instances, add a cross-instance broadcast in front of `RealtimeDispatcher` (Redis Pub/Sub or PostgreSQL `LISTEN/NOTIFY`) so that each instance pushes only to the connections it holds; nothing else needs to change.

## How Xiao A works

`agent/AgentBrain` is an interface. The current `RuleBasedAgentBrain` recognizes intents by keyword (summarize a conversation, today's meetings, draft a reply, prioritize TODOs, organize tasks, memory) and reads its data from the database. To plug in an LLM, implement this interface and swap it in; neither the API nor the frontend needs to change.

## Not done yet

- Sign-up and password reset
- Cross-instance push broadcast for multi-instance deployments (see "Scaling out" above); moving attachments to object storage (they are currently stored in the local directory `aido.files.dir`, `data/files` by default)
- Connecting Xiao A to an LLM; scheduled jobs (morning unread digest, weekly report drafts)
