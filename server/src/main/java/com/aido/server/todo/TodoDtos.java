package com.aido.server.todo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class TodoDtos {

  private TodoDtos() {}

  /** TODO 的来源消息，前端据此显示「来自 xx」和「查看原消息」。 */
  public record Source(
      String conversationId, String conversationName, long messageId, String senderId, String senderName,
      String text, OffsetDateTime sentAt) {}

  /**
   * @param today 是否在「今天」分组（planned_date 等于今天）
   * @param due   截止时间的展示文案：优先用小雀给的说法（如「14:00 前」），否则按 dueAt 换算成「明天」「9月27日」
   */
  public record TodoDto(
      long id,
      String title,
      String status,
      String kind,
      String priority,
      boolean today,
      LocalDate plannedDate,
      String due,
      OffsetDateTime dueAt,
      String note,
      Source source,
      String createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime completedAt) {}

  public record CreateTodo(
      @NotBlank @Size(max = 200) String title,
      @Pattern(regexp = "P[012]") String priority,
      Boolean today,
      OffsetDateTime dueAt) {}

  public record FromMessage(long messageId) {}

  /** 只修改传了值的字段；today=true 放进今天，false 移出今天。 */
  public record UpdateTodo(
      @Size(min = 1, max = 200) String title,
      @Pattern(regexp = "suggested|open|done|dismissed") String status,
      @Pattern(regexp = "P[012]") String priority,
      Boolean today,
      @Size(max = 40) String dueLabel,
      OffsetDateTime dueAt,
      @Size(max = 500) String note) {}

  /** 批量改状态：确认小雀的建议（open）或忽略（dismissed）。 */
  public record BatchStatus(@NotEmpty List<Long> ids, @NotBlank @Pattern(regexp = "open|done|dismissed") String status) {}
}
