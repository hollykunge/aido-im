package com.aido.server.workspace;

import com.aido.server.common.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkspaceController {

  private final JdbcClient jdbc;

  public WorkspaceController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param unreadTotal   未免打扰会话的未读合计（「消息」tab 角标）
   * @param openTodoCount 待办数（「TODO」tab 角标）
   * @param suggestedCount 小雀建议、待你确认的 TODO 数
   */
  public record Counters(int unreadTotal, int openTodoCount, int suggestedCount) {}

  /** 顶部 tab 栏的角标。 */
  @GetMapping("/api/workspace/counters")
  public Counters counters(@CurrentUser String me) {
    return jdbc.sql("""
        SELECT
          (SELECT coalesce(sum(unread_count), 0) FROM conversation_member WHERE user_id = :me AND NOT muted) AS unread_total,
          (SELECT count(*) FROM todo WHERE owner_id = :me AND status = 'open') AS open_todo_count,
          (SELECT count(*) FROM todo WHERE owner_id = :me AND status = 'suggested') AS suggested_count
        """)
        .param("me", me)
        .query(Counters.class)
        .single();
  }
}
