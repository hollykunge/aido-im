package com.aido.server.chat;

import com.aido.server.chat.ChatDtos.Attachment;
import com.aido.server.chat.ChatDtos.ConversationDto;
import com.aido.server.chat.ChatDtos.LinkedTodo;
import com.aido.server.chat.ChatDtos.MessageDto;
import com.aido.server.chat.ChatDtos.SummaryDto;
import com.aido.server.user.UserDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChatRepository {

  private final JdbcClient jdbc;

  public ChatRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // 当前用户视角的会话：私聊取对方的名字和颜色，预览取最后一条消息
  private static final String CONVERSATION_SELECT = """
      SELECT c.id, c.type, coalesce(c.name, pu.name) AS title, peer.user_id AS peer_user_id,
             coalesce(c.color, pu.color) AS color, c.member_count,
             cm.pinned, cm.muted, cm.unread_count AS unread, cm.agent_flag,
             lm.preview AS last_message, c.last_message_at
      FROM conversation c
      JOIN conversation_member cm ON cm.conversation_id = c.id AND cm.user_id = :me
      LEFT JOIN LATERAL (
        SELECT user_id FROM conversation_member
        WHERE conversation_id = c.id AND user_id <> :me ORDER BY joined_at LIMIT 1
      ) peer ON c.type = 'dm'
      LEFT JOIN app_user pu ON pu.id = peer.user_id
      LEFT JOIN LATERAL (
        SELECT CASE WHEN c.type = 'group' AND m.sender_id <> :me THEN su.name || '：' ELSE '' END
               || coalesce(m.body, CASE WHEN m.attachment_type LIKE 'image/%' THEN '[图片]'
                                         ELSE '[文件] ' || m.attachment_name END) AS preview
        FROM message m JOIN app_user su ON su.id = m.sender_id
        WHERE m.conversation_id = c.id
        ORDER BY m.sent_at DESC, m.id DESC LIMIT 1
      ) lm ON true
      """;

  /** filter：all 全部 / unread 未读 / flag 小雀精选。置顶在前，其余按最后消息时间倒序。 */
  public List<ConversationDto> listConversations(String me, String filter) {
    return jdbc.sql(CONVERSATION_SELECT + """
        WHERE (:filter = 'all'
           OR (:filter = 'unread' AND cm.unread_count > 0)
           OR (:filter = 'flag' AND cm.agent_flag IS NOT NULL AND cm.unread_count > 0))
        ORDER BY cm.pinned DESC, c.last_message_at DESC NULLS LAST, c.id
        """)
        .param("me", me)
        .param("filter", filter)
        .query(ConversationDto.class)
        .list();
  }

  public List<ConversationDto> listGroups(String me) {
    return jdbc.sql(CONVERSATION_SELECT + " WHERE c.type = 'group' ORDER BY c.name")
        .param("me", me)
        .query(ConversationDto.class)
        .list();
  }

  public Optional<ConversationDto> findConversation(String me, String id) {
    return jdbc.sql(CONVERSATION_SELECT + " WHERE c.id = :id")
        .param("me", me)
        .param("id", id)
        .query(ConversationDto.class)
        .optional();
  }

  public Optional<String> findDm(String me, String peer) {
    return jdbc.sql("""
        SELECT c.id FROM conversation c
        JOIN conversation_member a ON a.conversation_id = c.id AND a.user_id = :me
        JOIN conversation_member b ON b.conversation_id = c.id AND b.user_id = :peer
        WHERE c.type = 'dm' LIMIT 1
        """)
        .param("me", me)
        .param("peer", peer)
        .query(String.class)
        .optional();
  }

  public void createDm(String id, String me, String peer) {
    jdbc.sql("INSERT INTO conversation (id, type, member_count) VALUES (?, 'dm', 2)").param(id).update();
    jdbc.sql("INSERT INTO conversation_member (conversation_id, user_id) VALUES (:id, :me), (:id, :peer)")
        .param("id", id)
        .param("me", me)
        .param("peer", peer)
        .update();
  }

  private static final String MESSAGE_SELECT = """
      SELECT m.id, m.conversation_id, m.sender_id, m.body, m.attachment_name, m.attachment_size,
             m.attachment_type, m.attachment_key, m.sent_at,
             EXISTS (SELECT 1 FROM message_mention mm WHERE mm.message_id = m.id AND mm.user_id = :me) AS mentions_me,
             t.id AS todo_id, t.status AS todo_status
      FROM message m
      LEFT JOIN LATERAL (
        SELECT id, status FROM todo
        WHERE source_message_id = m.id AND owner_id = :me AND status <> 'dismissed'
        ORDER BY (status = 'suggested'), id LIMIT 1
      ) t ON true
      """;

  /** 按 id 倒序分页取 before 之前的 limit 条，再按时间正序返回。 */
  public List<MessageDto> listMessages(String me, String conversationId, Long before, int limit) {
    var page = jdbc.sql(MESSAGE_SELECT + """
        WHERE m.conversation_id = :conv AND (CAST(:before AS bigint) IS NULL OR m.id < :before)
        ORDER BY m.sent_at DESC, m.id DESC
        LIMIT :limit
        """)
        .param("me", me)
        .param("conv", conversationId)
        .param("before", before)
        .param("limit", limit)
        .query(ChatRepository::mapMessage)
        .list();
    return page.reversed();
  }

  public Optional<MessageDto> findMessage(String me, long id) {
    return jdbc.sql(MESSAGE_SELECT + " WHERE m.id = :id")
        .param("me", me)
        .param("id", id)
        .query(ChatRepository::mapMessage)
        .optional();
  }

  static MessageDto mapMessage(ResultSet rs, int row) throws SQLException {
    String fileName = rs.getString("attachment_name");
    long todoId = rs.getLong("todo_id");
    LinkedTodo todo = rs.wasNull() ? null : new LinkedTodo(todoId, rs.getString("todo_status"));
    return new MessageDto(
        rs.getLong("id"),
        rs.getString("conversation_id"),
        rs.getString("sender_id"),
        rs.getString("body"),
        fileName == null ? null : new Attachment(fileName, rs.getLong("attachment_size"),
            rs.getString("attachment_type"), rs.getString("attachment_key") != null),
        rs.getBoolean("mentions_me"),
        rs.getObject("sent_at", OffsetDateTime.class),
        todo);
  }

  public List<ChatDtos.MemberState> memberStates(String conversationId) {
    return jdbc.sql("SELECT user_id, unread_count AS unread FROM conversation_member WHERE conversation_id = ?")
        .param(conversationId)
        .query(ChatDtos.MemberState.class)
        .list();
  }

  public Set<String> mentionedUsers(long messageId) {
    return Set.copyOf(jdbc.sql("SELECT user_id FROM message_mention WHERE message_id = ?")
        .param(messageId)
        .query(String.class)
        .list());
  }

  public boolean isMember(String me, String conversationId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM conversation_member WHERE conversation_id = ? AND user_id = ?)")
        .param(conversationId)
        .param(me)
        .query(Boolean.class)
        .single();
  }

  public long insertMessage(String conversationId, String senderId, String text, OffsetDateTime sentAt) {
    return jdbc.sql("""
        INSERT INTO message (conversation_id, sender_id, body, sent_at) VALUES (?, ?, ?, ?) RETURNING id
        """)
        .params(conversationId, senderId, text, sentAt)
        .query(Long.class)
        .single();
  }

  public long insertFileMessage(
      String conversationId, String senderId, String name, long size, String type, String key, OffsetDateTime sentAt) {
    return jdbc.sql("""
        INSERT INTO message (conversation_id, sender_id, attachment_name, attachment_size, attachment_type, attachment_key, sent_at)
        VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
        """)
        .params(conversationId, senderId, name, size, type, key, sentAt)
        .query(Long.class)
        .single();
  }

  /** 附件的存储信息（下载用）。 */
  public record StoredFile(String conversationId, String name, long size, String type, String key) {}

  public Optional<StoredFile> findFile(long messageId) {
    return jdbc.sql("""
        SELECT conversation_id, attachment_name AS name, attachment_size AS size, attachment_type AS type,
               attachment_key AS key
        FROM message WHERE id = ? AND attachment_name IS NOT NULL
        """)
        .param(messageId)
        .query(StoredFile.class)
        .optional();
  }

  /** 会话成员（@ 提及时选人用），按姓名排序。 */
  public List<UserDto> members(String conversationId) {
    return jdbc.sql("""
        SELECT u.id, u.name, u.role, u.dept, u.color
        FROM conversation_member cm JOIN app_user u ON u.id = cm.user_id
        WHERE cm.conversation_id = ? ORDER BY u.name
        """)
        .param(conversationId)
        .query(UserDto.class)
        .list();
  }

  /** 按「@名字」匹配会话成员，记录被 @ 的人。 */
  public void insertMentions(long messageId, String conversationId, String text) {
    jdbc.sql("""
        INSERT INTO message_mention (message_id, user_id)
        SELECT :mid, u.id FROM conversation_member cm JOIN app_user u ON u.id = cm.user_id
        WHERE cm.conversation_id = :conv AND position('@' || u.name IN :text) > 0
        ON CONFLICT DO NOTHING
        """)
        .param("mid", messageId)
        .param("conv", conversationId)
        .param("text", text)
        .update();
  }

  /** 新消息：更新会话时间；发送人视为已读，并清掉小雀给他的「待回复」类标记；其他成员未读 +1。 */
  public void afterMessage(String conversationId, String senderId, OffsetDateTime sentAt) {
    jdbc.sql("UPDATE conversation SET last_message_at = greatest(last_message_at, :at) WHERE id = :conv")
        .param("at", sentAt)
        .param("conv", conversationId)
        .update();
    jdbc.sql("""
        UPDATE conversation_member SET
          unread_count = CASE WHEN user_id = :sender THEN 0 ELSE unread_count + 1 END,
          last_read_at = CASE WHEN user_id = :sender THEN :at ELSE last_read_at END,
          agent_flag = CASE WHEN user_id = :sender THEN NULL ELSE agent_flag END
        WHERE conversation_id = :conv
        """)
        .param("sender", senderId)
        .param("at", sentAt)
        .param("conv", conversationId)
        .update();
  }

  public void markRead(String me, String conversationId, OffsetDateTime at) {
    jdbc.sql("""
        UPDATE conversation_member SET unread_count = 0, last_read_at = :at
        WHERE conversation_id = :conv AND user_id = :me
        """)
        .param("at", at)
        .param("conv", conversationId)
        .param("me", me)
        .update();
  }

  public Optional<SummaryDto> findSummary(String me, String conversationId) {
    return jdbc.sql("""
        SELECT conversation_id, message_count, points, generated_at FROM conversation_summary
        WHERE conversation_id = ? AND owner_id = ?
        """)
        .params(conversationId, me)
        .query((rs, i) -> new SummaryDto(
            rs.getString("conversation_id"),
            rs.getInt("message_count"),
            Arrays.asList((String[]) rs.getArray("points").getArray()),
            rs.getObject("generated_at", OffsetDateTime.class)))
        .optional();
  }

  /** kind：quick 快捷回复 / draft 起草的整段回复。会话没有专属建议时，draft 退回到通用兜底。 */
  public List<String> findSuggestions(String me, String conversationId, String kind) {
    return jdbc.sql("""
        SELECT body FROM reply_suggestion
        WHERE owner_id = :me AND kind = :kind
          AND (conversation_id = :conv OR (conversation_id IS NULL AND NOT EXISTS (
                SELECT 1 FROM reply_suggestion WHERE owner_id = :me AND kind = :kind AND conversation_id = :conv)))
        ORDER BY position, id
        """)
        .param("me", me)
        .param("kind", kind)
        .param("conv", conversationId)
        .query(String.class)
        .list();
  }
}
