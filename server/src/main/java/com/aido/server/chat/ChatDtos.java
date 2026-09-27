package com.aido.server.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class ChatDtos {

  private ChatDtos() {}

  /**
   * 会话列表项（当前用户视角）。
   *
   * @param title       群名；私聊为对方名字
   * @param peerUserId  私聊对象，群聊为空
   * @param lastMessage 最后一条消息的预览，群聊带「发送人：」前缀
   */
  public record ConversationDto(
      String id,
      String type,
      String title,
      String peerUserId,
      String color,
      int memberCount,
      boolean pinned,
      boolean muted,
      int unread,
      String agentFlag,
      String lastMessage,
      OffsetDateTime lastMessageAt) {}

  /**
   * @param type      MIME 类型
   * @param available 有没有可下载的内容（演示数据里的文件消息只有文件名）
   */
  public record Attachment(String name, long size, String type, boolean available) {}

  /** 消息整理成的 TODO（前端在消息下方显示「已在 TODO」等标记）。 */
  public record LinkedTodo(long id, String status) {}

  public record MessageDto(
      long id,
      String conversationId,
      String senderId,
      String text,
      Attachment file,
      boolean mentionsMe,
      OffsetDateTime sentAt,
      LinkedTodo todo) {

    /** 推送给某位收件人时，按他的视角重算是否 @ 了他。 */
    public MessageDto withMentionsMe(boolean mentions) {
      return new MessageDto(id, conversationId, senderId, text, file, mentions, sentAt, todo);
    }
  }

  /** 会话成员及其未读数（推送新消息时按人下发）。 */
  public record MemberState(String userId, int unread) {}

  public record SendMessage(@NotBlank @Size(max = 4000) String text) {}

  public record OpenDm(@NotBlank String userId) {}

  /** 小雀对会话未读的摘要。 */
  public record SummaryDto(String conversationId, int count, List<String> points, OffsetDateTime generatedAt) {}
}
