package com.aido.server.realtime;

import com.aido.server.agent.AgentFeedRepository;
import com.aido.server.chat.ChatRepository;
import com.aido.server.memory.MemoryService;
import com.aido.server.realtime.RealtimeEvents.AgentItemsChanged;
import com.aido.server.realtime.RealtimeEvents.CapabilitiesChanged;
import com.aido.server.realtime.RealtimeEvents.ConversationRead;
import com.aido.server.realtime.RealtimeEvents.MemoryChanged;
import com.aido.server.realtime.RealtimeEvents.MessageCreated;
import com.aido.server.realtime.RealtimeEvents.PreferencesChanged;
import com.aido.server.realtime.RealtimeEvents.TodosChanged;
import com.aido.server.realtime.RealtimeEvents.UserUpdated;
import com.aido.server.todo.TodoRepository;
import com.aido.server.user.PreferenceRepository;
import com.aido.server.user.UserRepository;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 事务提交后把事件翻译成推送：确定收件人，按收件人视角组装数据，交给 {@link RealtimeRegistry} 入队。
 * 入队不阻塞，所以直接在提交后的请求线程里做，推送顺序与提交顺序一致。
 *
 * <p>推送的事件（客户端 src/api/realtime.js 处理）：
 * <ul>
 *   <li>{@code message.created} {message, unread}：会话成员各自收到，unread 与 mentionsMe 按收件人计算</li>
 *   <li>{@code conversation.read} {conversationId}</li>
 *   <li>{@code todos.changed} {todos}</li>
 *   <li>{@code agent.items} {items}</li>
 *   <li>{@code user.updated} {user}：某人改了姓名或头像颜色，推给所有在线用户</li>
 *   <li>{@code memory.changed} 记忆页完整快照；{@code preferences.changed} 个人偏好；{@code capabilities.changed} {capabilities}</li>
 * </ul>
 * 记忆、偏好、能力开关都很小，每次推整份快照：客户端直接替换，不用处理增量合并。
 */
@Component
public class RealtimeDispatcher {

  private final RealtimeRegistry registry;
  private final ChatRepository chat;
  private final TodoRepository todos;
  private final MemoryService memory;
  private final PreferenceRepository preferences;
  private final AgentFeedRepository agent;
  private final UserRepository users;

  public RealtimeDispatcher(
      RealtimeRegistry registry, ChatRepository chat, TodoRepository todos, MemoryService memory,
      PreferenceRepository preferences, AgentFeedRepository agent, UserRepository users) {
    this.registry = registry;
    this.chat = chat;
    this.todos = todos;
    this.memory = memory;
    this.preferences = preferences;
    this.agent = agent;
    this.users = users;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(UserUpdated e) {
    users.findById(e.userId()).ifPresent(u -> registry.broadcast("user.updated", Map.of("user", u), e.originClient()));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(MessageCreated e) {
    var recipients = chat.memberStates(e.conversationId());
    // 只给在线的人组装数据；消息本体按收件人算 mentionsMe
    var online = recipients.stream().filter(r -> registry.isOnline(r.userId())).toList();
    if (online.isEmpty()) return;
    var mentioned = chat.mentionedUsers(e.messageId());
    var message = chat.findMessage(null, e.messageId()).orElse(null);
    if (message == null) return;
    for (var r : online) {
      var forRecipient = message.withMentionsMe(mentioned.contains(r.userId()));
      registry.send(r.userId(), "message.created", Map.of("message", forRecipient, "unread", r.unread()), e.originClient());
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(ConversationRead e) {
    registry.send(e.userId(), "conversation.read", Map.of("conversationId", e.conversationId()), e.originClient());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(TodosChanged e) {
    if (!registry.isOnline(e.userId())) return;
    registry.send(e.userId(), "todos.changed", Map.of("todos", todos.findAll(e.userId(), e.todoIds())), e.originClient());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(MemoryChanged e) {
    if (registry.isOnline(e.userId())) {
      registry.send(e.userId(), "memory.changed", memory.snapshot(e.userId()), e.originClient());
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(PreferencesChanged e) {
    if (registry.isOnline(e.userId())) {
      registry.send(e.userId(), "preferences.changed", preferences.get(e.userId()), e.originClient());
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(CapabilitiesChanged e) {
    if (registry.isOnline(e.userId())) {
      registry.send(e.userId(), "capabilities.changed", Map.of("capabilities", agent.capabilities(e.userId())),
          e.originClient());
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(AgentItemsChanged e) {
    registry.send(e.userId(), "agent.items", Map.of("items", e.items()), e.originClient());
  }
}
