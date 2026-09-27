package com.aido.server.search;

import com.aido.server.common.CurrentUser;
import com.aido.server.todo.TodoDtos.TodoDto;
import com.aido.server.todo.TodoRepository;
import com.aido.server.user.UserDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局搜索：联系人、群组、消息、文件、TODO。
 * 用 ILIKE 做子串匹配（中文不分词也能搜），pg_trgm 的 GIN 索引在关键词 ≥ 3 个字时加速。
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

  private static final Set<String> SCOPES = Set.of("all", "contacts", "groups", "messages", "files", "todos");
  private static final int RECENT_KEEP = 6;

  private final JdbcClient jdbc;
  private final TodoRepository todos;

  public SearchController(JdbcClient jdbc, TodoRepository todos) {
    this.jdbc = jdbc;
    this.todos = todos;
  }

  public record Section<T>(int total, List<T> items) {
    static <T> Section<T> empty() {
      return new Section<>(0, List.of());
    }
  }

  public record GroupHit(String id, String title, String color, int memberCount) {}

  public record MessageHit(
      long messageId, String conversationId, String conversationTitle, String senderId, String senderName,
      String text, String fileName, Long fileSize, OffsetDateTime sentAt) {}

  public record Result(
      String query, int total, Section<UserDto> contacts, Section<GroupHit> groups, Section<MessageHit> messages,
      Section<MessageHit> files, Section<TodoDto> todos) {}

  /**
   * @param scope            all 时每类最多返回 perSection 条，其余只返回该类
   * @param includeSuggested 小雀不可用时传 false，不返回待确认的建议
   */
  @GetMapping
  public Result search(
      @CurrentUser String me,
      @RequestParam String q,
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam(defaultValue = "3") int perSection,
      @RequestParam(defaultValue = "true") boolean includeSuggested) {
    if (!SCOPES.contains(scope)) throw new IllegalArgumentException("scope 只能是 " + SCOPES);
    var kw = q.strip();
    if (kw.isEmpty()) {
      return new Result(kw, 0, Section.empty(), Section.empty(), Section.empty(), Section.empty(), Section.empty());
    }
    var like = "%" + kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    int limit = scope.equals("all") ? Math.clamp(perSection, 1, 50) : 100;

    var contacts = wants(scope, "contacts") ? contacts(me, like, limit) : Section.<UserDto>empty();
    var groups = wants(scope, "groups") ? groups(me, like, limit) : Section.<GroupHit>empty();
    var messages = wants(scope, "messages") ? messages(me, like, limit, false) : Section.<MessageHit>empty();
    var files = wants(scope, "files") ? messages(me, like, limit, true) : Section.<MessageHit>empty();
    var todoHits = wants(scope, "todos") ? todos(me, like, limit, includeSuggested) : Section.<TodoDto>empty();
    int total = contacts.total() + groups.total() + messages.total() + files.total() + todoHits.total();
    return new Result(kw, total, contacts, groups, messages, files, todoHits);
  }

  private static boolean wants(String scope, String key) {
    return scope.equals("all") || scope.equals(key);
  }

  private Section<UserDto> contacts(String me, String like, int limit) {
    var rows = jdbc.sql("""
        SELECT id, name, role, dept, color, count(*) OVER () AS total FROM app_user
        WHERE id <> :me AND (name || ' ' || role || ' ' || dept) ILIKE :like
        ORDER BY name LIMIT :limit
        """)
        .param("me", me).param("like", like).param("limit", limit)
        .query((rs, i) -> new Hit<>(
            new UserDto(rs.getString("id"), rs.getString("name"), rs.getString("role"), rs.getString("dept"),
                rs.getString("color")),
            rs.getInt("total")))
        .list();
    return section(rows);
  }

  private Section<GroupHit> groups(String me, String like, int limit) {
    var rows = jdbc.sql("""
        SELECT c.id, c.name, c.color, c.member_count, count(*) OVER () AS total
        FROM conversation c JOIN conversation_member cm ON cm.conversation_id = c.id AND cm.user_id = :me
        WHERE c.type = 'group' AND c.name ILIKE :like
        ORDER BY c.last_message_at DESC NULLS LAST LIMIT :limit
        """)
        .param("me", me).param("like", like).param("limit", limit)
        .query((rs, i) -> new Hit<>(
            new GroupHit(rs.getString("id"), rs.getString("name"), rs.getString("color"), rs.getInt("member_count")),
            rs.getInt("total")))
        .list();
    return section(rows);
  }

  /** files=true 搜附件名，否则搜消息正文。只搜自己所在的会话。 */
  private Section<MessageHit> messages(String me, String like, int limit, boolean files) {
    var rows = jdbc.sql("""
        SELECT m.id, m.conversation_id, coalesce(c.name, peer.name) AS title, m.sender_id, su.name AS sender_name,
               m.body, m.attachment_name, m.attachment_size, m.sent_at, count(*) OVER () AS total
        FROM message m
        JOIN conversation c ON c.id = m.conversation_id
        JOIN conversation_member cm ON cm.conversation_id = c.id AND cm.user_id = :me
        JOIN app_user su ON su.id = m.sender_id
        LEFT JOIN LATERAL (
          SELECT u.name FROM conversation_member x JOIN app_user u ON u.id = x.user_id
          WHERE x.conversation_id = c.id AND x.user_id <> :me LIMIT 1
        ) peer ON c.type = 'dm'
        WHERE %s ILIKE :like
        ORDER BY m.sent_at DESC LIMIT :limit
        """.formatted(files ? "m.attachment_name" : "m.body"))
        .param("me", me).param("like", like).param("limit", limit)
        .query((rs, i) -> {
          long size = rs.getLong("attachment_size");
          Long fileSize = rs.wasNull() ? null : size;
          return new Hit<>(
              new MessageHit(rs.getLong("id"), rs.getString("conversation_id"), rs.getString("title"),
                  rs.getString("sender_id"), rs.getString("sender_name"), rs.getString("body"),
                  rs.getString("attachment_name"), fileSize, rs.getObject("sent_at", OffsetDateTime.class)),
              rs.getInt("total"));
        })
        .list();
    return section(rows);
  }

  private Section<TodoDto> todos(String me, String like, int limit, boolean includeSuggested) {
    var statuses = includeSuggested ? List.of("suggested", "open", "done") : List.of("open", "done");
    var hits = todos.search(me, like, statuses);
    return new Section<>(hits.size(), hits.stream().limit(limit).toList());
  }

  /** 一行命中 + 该类命中总数（count(*) OVER ()）。 */
  private record Hit<T>(T item, int total) {}

  private static <T> Section<T> section(List<Hit<T>> rows) {
    int total = rows.isEmpty() ? 0 : rows.getFirst().total();
    return new Section<>(total, rows.stream().map(Hit::item).toList());
  }

  // —— 最近搜索 ——

  @GetMapping("/history")
  public List<String> history(@CurrentUser String me) {
    return jdbc.sql("SELECT query FROM search_history WHERE owner_id = ? ORDER BY searched_at DESC LIMIT ?")
        .params(me, RECENT_KEEP)
        .query(String.class)
        .list();
  }

  public record Remember(@NotBlank @Size(max = 100) String query) {}

  /** 记一条最近搜索，只保留最近 6 条。 */
  @PostMapping("/history")
  @Transactional
  public List<String> remember(@CurrentUser String me, @RequestBody @Valid Remember body) {
    jdbc.sql("""
        INSERT INTO search_history (owner_id, query) VALUES (?, ?)
        ON CONFLICT (owner_id, query) DO UPDATE SET searched_at = now()
        """)
        .params(me, body.query().strip())
        .update();
    jdbc.sql("""
        DELETE FROM search_history WHERE owner_id = :me AND query NOT IN (
          SELECT query FROM search_history WHERE owner_id = :me ORDER BY searched_at DESC LIMIT :keep)
        """)
        .param("me", me)
        .param("keep", RECENT_KEEP)
        .update();
    return history(me);
  }

  @DeleteMapping("/history")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void clearHistory(@CurrentUser String me) {
    jdbc.sql("DELETE FROM search_history WHERE owner_id = ?").param(me).update();
  }
}
