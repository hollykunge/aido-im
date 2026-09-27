package com.aido.server.chat;

import com.aido.server.chat.ChatDtos.ConversationDto;
import com.aido.server.chat.ChatDtos.MessageDto;
import com.aido.server.common.NotFoundException;
import com.aido.server.common.TimeLabels;
import com.aido.server.realtime.RealtimeEvents;
import com.aido.server.user.UserDto;
import com.aido.server.user.UserRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {

  private static final Set<String> FILTERS = Set.of("all", "unread", "flag");

  private final ChatRepository repo;
  private final UserRepository users;
  private final TimeLabels time;
  private final RealtimeEvents events;

  public ChatService(ChatRepository repo, UserRepository users, TimeLabels time, RealtimeEvents events) {
    this.repo = repo;
    this.users = users;
    this.time = time;
    this.events = events;
  }

  public List<ConversationDto> list(String me, String filter) {
    if (!FILTERS.contains(filter)) throw new IllegalArgumentException("filter 只能是 all / unread / flag");
    return repo.listConversations(me, filter);
  }

  public ConversationDto get(String me, String conversationId) {
    return repo.findConversation(me, conversationId).orElseThrow(() -> new NotFoundException("会话", conversationId));
  }

  /** 只有会话成员能读写；非成员一律当作不存在，不暴露会话是否存在。 */
  public void requireMember(String me, String conversationId) {
    if (!repo.isMember(me, conversationId)) throw new NotFoundException("会话", conversationId);
  }

  public List<MessageDto> messages(String me, String conversationId, Long before, int limit) {
    requireMember(me, conversationId);
    return repo.listMessages(me, conversationId, before, Math.clamp(limit, 1, 200));
  }

  @Transactional
  public MessageDto send(String me, String conversationId, String text) {
    requireMember(me, conversationId);
    var body = text.strip();
    var now = time.now();
    long id = repo.insertMessage(conversationId, me, body, now);
    repo.insertMentions(id, conversationId, body);
    repo.afterMessage(conversationId, me, now);
    events.messageCreated(id, conversationId);
    return repo.findMessage(me, id).orElseThrow();
  }

  /** 发一条文件消息（文件内容已存好，这里只记元数据）。 */
  @Transactional
  public MessageDto sendFile(String me, String conversationId, String name, long size, String type, String key) {
    requireMember(me, conversationId);
    var now = time.now();
    long id = repo.insertFileMessage(conversationId, me, name, size, type, key, now);
    repo.afterMessage(conversationId, me, now);
    events.messageCreated(id, conversationId);
    return repo.findMessage(me, id).orElseThrow();
  }

  public List<UserDto> members(String me, String conversationId) {
    requireMember(me, conversationId);
    return repo.members(conversationId);
  }

  @Transactional
  public void markRead(String me, String conversationId) {
    requireMember(me, conversationId);
    repo.markRead(me, conversationId, time.now());
    events.conversationRead(me, conversationId);
  }

  /** 打开与某人的私聊，没有就新建。 */
  @Transactional
  public ConversationDto openDm(String me, String peer) {
    if (me.equals(peer)) throw new IllegalArgumentException("不能和自己私聊");
    users.findById(peer).orElseThrow(() -> new NotFoundException("用户", peer));
    var id = repo.findDm(me, peer).orElseGet(() -> {
      var newId = "dm-" + (me.compareTo(peer) < 0 ? me + "-" + peer : peer + "-" + me);
      repo.createDm(newId, me, peer);
      return newId;
    });
    return get(me, id);
  }
}
