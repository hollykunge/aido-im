package com.aido.server.todo;

import com.aido.server.common.TimeLabels;
import com.aido.server.todo.TodoDtos.Source;
import com.aido.server.todo.TodoDtos.TodoDto;
import com.aido.server.todo.TodoDtos.UpdateTodo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TodoRepository {

  private final JdbcClient jdbc;
  private final TimeLabels time;

  public TodoRepository(JdbcClient jdbc, TimeLabels time) {
    this.jdbc = jdbc;
    this.time = time;
  }

  private static final String SELECT = """
      SELECT t.*, m.conversation_id AS src_conv, coalesce(c.name, peer.name) AS src_conv_name,
             m.sender_id AS src_sender, su.name AS src_sender_name,
             coalesce(m.body, m.attachment_name) AS src_text, m.sent_at AS src_sent_at
      FROM todo t
      LEFT JOIN message m ON m.id = t.source_message_id
      LEFT JOIN conversation c ON c.id = m.conversation_id
      LEFT JOIN LATERAL (
        SELECT u.name FROM conversation_member cm JOIN app_user u ON u.id = cm.user_id
        WHERE cm.conversation_id = c.id AND cm.user_id <> t.owner_id LIMIT 1
      ) peer ON c.type = 'dm'
      LEFT JOIN app_user su ON su.id = m.sender_id
      """;

  // P0 在前；同优先级按截止时间，只有计划日期的按当天结束算，都没有的排最后
  private static final String ORDER =
      " ORDER BY t.priority NULLS LAST, coalesce(t.due_at, CAST(t.planned_date + 1 AS timestamptz)) NULLS LAST, t.id";

  public List<TodoDto> list(String me, Collection<String> statuses) {
    return jdbc.sql(SELECT + " WHERE t.owner_id = :me AND t.status IN (:statuses)" + ORDER)
        .param("me", me)
        .param("statuses", statuses)
        .query(mapper())
        .list();
  }

  /** 标题模糊匹配；like 需已转义通配符。 */
  public List<TodoDto> search(String me, String like, Collection<String> statuses) {
    return jdbc.sql(SELECT + " WHERE t.owner_id = :me AND t.status IN (:statuses) AND t.title ILIKE :like" + ORDER)
        .param("me", me)
        .param("statuses", statuses)
        .param("like", like)
        .query(mapper())
        .list();
  }

  public Optional<TodoDto> find(String me, long id) {
    return jdbc.sql(SELECT + " WHERE t.owner_id = :me AND t.id = :id")
        .param("me", me)
        .param("id", id)
        .query(mapper())
        .optional();
  }

  public List<TodoDto> findAll(String me, Collection<Long> ids) {
    return jdbc.sql(SELECT + " WHERE t.owner_id = :me AND t.id IN (:ids)" + ORDER)
        .param("me", me)
        .param("ids", ids)
        .query(mapper())
        .list();
  }

  /** 未被忽略、由这条消息整理出的 TODO。 */
  public Optional<Long> findLiveBySource(String me, long messageId) {
    return jdbc.sql("SELECT id FROM todo WHERE owner_id = ? AND source_message_id = ? AND status <> 'dismissed' LIMIT 1")
        .params(me, messageId)
        .query(Long.class)
        .optional();
  }

  public long insert(
      String me, String title, String status, String kind, String priority, LocalDate plannedDate,
      String dueLabel, OffsetDateTime dueAt, String note, Long sourceMessageId, String createdBy) {
    return jdbc.sql("""
        INSERT INTO todo (owner_id, title, status, kind, priority, planned_date, due_label, due_at, note,
                          source_message_id, created_by)
        VALUES (:me, :title, :status, :kind, :priority, :planned, :dueLabel, :dueAt, :note, :source, :createdBy)
        RETURNING id
        """)
        .param("me", me)
        .param("title", title)
        .param("status", status)
        .param("kind", kind)
        .param("priority", priority)
        .param("planned", plannedDate)
        .param("dueLabel", dueLabel)
        .param("dueAt", dueAt)
        .param("note", note)
        .param("source", sourceMessageId)
        .param("createdBy", createdBy)
        .query(Long.class)
        .single();
  }

  public int update(String me, long id, UpdateTodo p) {
    // today：null 不动，true 设为今天，false 清空
    return jdbc.sql("""
        UPDATE todo SET
          title        = coalesce(CAST(:title AS text), title),
          status       = coalesce(CAST(:status AS text), status),
          priority     = coalesce(CAST(:priority AS text), priority),
          planned_date = CASE CAST(:today AS boolean) WHEN true THEN CAST(:todayDate AS date)
                                                      WHEN false THEN NULL ELSE planned_date END,
          due_label    = coalesce(CAST(:dueLabel AS text), due_label),
          due_at       = coalesce(CAST(:dueAt AS timestamptz), due_at),
          note         = coalesce(CAST(:note AS text), note),
          completed_at = CASE WHEN CAST(:status AS text) IS NULL THEN completed_at
                              WHEN CAST(:status AS text) = 'done' THEN coalesce(completed_at, now())
                              ELSE NULL END,
          updated_at   = now()
        WHERE owner_id = :me AND id = :id
        """)
        .param("title", p.title())
        .param("status", p.status())
        .param("priority", p.priority())
        .param("today", p.today())
        .param("todayDate", time.today())
        .param("dueLabel", p.dueLabel())
        .param("dueAt", p.dueAt())
        .param("note", p.note())
        .param("me", me)
        .param("id", id)
        .update();
  }

  public int updateStatus(String me, Collection<Long> ids, String status) {
    return jdbc.sql("""
        UPDATE todo SET status = :status, updated_at = now(),
          completed_at = CASE WHEN :status = 'done' THEN coalesce(completed_at, now()) ELSE NULL END
        WHERE owner_id = :me AND id IN (:ids)
        """)
        .param("status", status)
        .param("me", me)
        .param("ids", ids)
        .update();
  }

  /** 发出回复后，勾掉该会话里第一项「需要回复」的待办。 */
  public Optional<TodoDto> completeReplyTodo(String me, String conversationId) {
    var id = jdbc.sql("""
        UPDATE todo SET status = 'done', completed_at = now(), updated_at = now()
        WHERE id = (
          SELECT t.id FROM todo t JOIN message m ON m.id = t.source_message_id
          WHERE t.owner_id = :me AND t.status = 'open' AND t.kind = 'reply' AND m.conversation_id = :conv
          ORDER BY t.priority NULLS LAST, t.id LIMIT 1
        )
        RETURNING id
        """)
        .param("me", me)
        .param("conv", conversationId)
        .query(Long.class)
        .optional();
    return id.flatMap(i -> find(me, i));
  }

  // —— 小雀识别出的候选事项 ——

  /** 把最早的一条候选转成「待确认」TODO，返回新 TODO 的 id；没有候选时为空。 */
  public Optional<Long> promoteNextCandidate(String me) {
    var candidate = jdbc.sql("""
        SELECT id FROM todo_candidate WHERE owner_id = ? AND promoted_todo_id IS NULL
        ORDER BY found_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
        """)
        .param(me)
        .query(Long.class)
        .optional();
    return candidate.map(cid -> {
      long todoId = jdbc.sql("""
          INSERT INTO todo (owner_id, title, status, priority, planned_date, due_label, note, source_message_id, created_by)
          SELECT owner_id, title, 'suggested', priority, planned_date, due_label, note, source_message_id, 'agent'
          FROM todo_candidate WHERE id = ?
          RETURNING id
          """)
          .param(cid)
          .query(Long.class)
          .single();
      jdbc.sql("UPDATE todo_candidate SET promoted_todo_id = ? WHERE id = ?").params(todoId, cid).update();
      return todoId;
    });
  }

  private RowMapper<TodoDto> mapper() {
    LocalDate today = time.today();
    return (rs, i) -> map(rs, today);
  }

  private TodoDto map(ResultSet rs, LocalDate today) throws SQLException {
    var planned = rs.getObject("planned_date", LocalDate.class);
    var dueAt = rs.getObject("due_at", OffsetDateTime.class);
    var dueLabel = rs.getString("due_label");
    long sourceId = rs.getLong("source_message_id");
    Source source = rs.wasNull() ? null : new Source(
        rs.getString("src_conv"),
        rs.getString("src_conv_name"),
        sourceId,
        rs.getString("src_sender"),
        rs.getString("src_sender_name"),
        rs.getString("src_text"),
        rs.getObject("src_sent_at", OffsetDateTime.class));
    return new TodoDto(
        rs.getLong("id"),
        rs.getString("title"),
        rs.getString("status"),
        rs.getString("kind"),
        rs.getString("priority"),
        today.equals(planned),
        planned,
        dueLabel != null ? dueLabel : dueAt != null ? time.dayLabel(dueAt) : planned != null ? time.dayLabel(planned) : null,
        dueAt,
        rs.getString("note"),
        source,
        rs.getString("created_by"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("completed_at", OffsetDateTime.class));
  }
}
