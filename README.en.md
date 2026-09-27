# AIDo 5.0

[简体中文](README.md) | English

A prototype workplace IM with a personal AI assistant, **Xiao A (小A)**: messages, TODOs, memory, search, and an assistant panel that stays on the right while you work.
Vue 3 frontend, Java 25 + Spring Boot 4 + PostgreSQL backend, real-time push over WebSocket, usable at both desktop and phone widths.

> The UI is in Simplified Chinese.

## Screenshots

**Messages + Xiao A**: conversation list and chat window on the left; on the right, Xiao A answers based on the conversation you are looking at

![Messages with the Xiao A panel](docs/screenshots/desktop-chat.png)

**TODO (dark mode)**: items Xiao A picked out of your messages, waiting for confirmation, grouped into Today / Later, each traceable to its source message

![TODO page, dark mode](docs/screenshots/desktop-todo-dark.png)

**Phone width**: conversation list with bottom navigation, full-screen conversation, full-screen Xiao A panel

<p>
  <img src="docs/screenshots/mobile-list.png" alt="Phone: conversation list" width="260" />
  <img src="docs/screenshots/mobile-chat.png" alt="Phone: conversation" width="260" />
  <img src="docs/screenshots/mobile-agent.png" alt="Phone: Xiao A panel" width="260" />
</p>

## Features

- **Messages**: direct and group chats, unread counts, pin and mute, @ mentions (type @ to pick a person; mentions are highlighted), emoji, attachments (file picker / drag and drop / paste a screenshot, with image thumbnails)
- **TODO**: Xiao A turns messages into TODOs (traceable to the source message); To confirm / Today / Later / Done; convert any message into a TODO in one click
- **Xiao A**: answers based on what the main window is showing: summarizes conversations, drafts replies (send directly and tick off the matching TODO), prioritizes TODOs, checks your schedule; its look and colors are customizable
- **Memory**: the profile, specific memories and learning sources Xiao A keeps, all editable
- **Search**: contacts, groups, messages, files and TODOs in one place
- **Real time**: new messages, read state, TODOs, memory, settings and profile changes are pushed over WebSocket; multiple tabs stay in sync; automatic reconnect with catch-up after a disconnect
- **Accounts**: log in / log out, change password, change display name and avatar color
- **Phone width**: bottom navigation, full-screen conversations, full-screen Xiao A panel, settings in a bottom sheet

> Xiao A is currently a keyword-based rule engine (`server/.../agent/RuleBasedAgentBrain`). To plug in an LLM, implement the `AgentBrain` interface and swap it in.

## Tech stack

| | |
| --- | --- |
| Frontend | Vue 3 · Pinia · Vue Router · Vite · lucide icons |
| Backend | Java 25 · Spring Boot 4.1 (Spring MVC, virtual threads) · Spring Security · Spring Session JDBC · WebSocket |
| Data | PostgreSQL 17 · Flyway migrations · `JdbcClient` (plain SQL) |
| Tests | JUnit 5 · Testcontainers (real PostgreSQL), including a 100-user concurrent push load test |

## Quick start

Requires Node.js 20.19+ or 22.12+, JDK 25, Maven and Docker.

```bash
# 1. Database (local port 55432)
cd server && docker compose up -d

# 2. Backend (8080; creates the schema and loads demo data on first start)
mvn spring-boot:run

# 3. Frontend (5173; /api is proxied to the backend)
cd .. && npm install && npm run dev
```

Open http://localhost:5173 and log in with a demo account (accounts and the initial password are listed in [server/README.en.md](server/README.en.md#authentication)).

Run the backend tests (requires Docker):

```bash
cd server && mvn test
```

## Project layout

```
src/                frontend
  api/              API wrappers, real-time push connection
  stores/           Pinia: workspace, Xiao A, memory, real-time events
  views/            messages, TODO, search, memory, login
  components/       Xiao A panel, chat (emoji / @ / message text), TODO, shared components
  layouts/          app shell (top tab bar, bottom navigation, Xiao A panel container)
server/             backend; see server/README.en.md (API, schema, push protocol, auth)
```

## Notes

- The database password and demo account password in this repository are for local development and demos only. For a real deployment, override them with environment variables (see "Configuration" in server/README.en.md) and turn off the `demo` profile.
- Attachments are stored in `server/data/files` by default; for a multi-instance deployment, swap in an object-storage implementation.

## License

See [LICENSE](LICENSE).
