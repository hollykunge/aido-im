package com.aido.server.realtime;

import java.util.Collection;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * 业务代码发布「发生了什么」，由 {@link RealtimeDispatcher} 在事务提交后决定推给谁、推什么。
 * 业务代码不直接碰 WebSocket；事务回滚时事件也随之丢弃，客户端不会看到没落库的改动。
 */
@Component
public class RealtimeEvents {

  /** 会话里有了新消息。 */
  public record MessageCreated(long messageId, String conversationId, String originClient) {}

  /** 某人把会话标记为已读（同步到他的其他标签页）。 */
  public record ConversationRead(String userId, String conversationId, String originClient) {}

  /** 某人的一批 TODO 变了（新建、改状态、小A整理出新的待确认…）。 */
  public record TodosChanged(String userId, List<Long> todoIds, String originClient) {}

  /** 某人的记忆（画像、具体记忆、学习来源）变了。 */
  public record MemoryChanged(String userId, String originClient) {}

  /** 某人的个人偏好（主题、小A形象、是否从日常工作中学习）变了。 */
  public record PreferencesChanged(String userId, String originClient) {}

  /** 某人的小A能力开关变了。 */
  public record CapabilitiesChanged(String userId, String originClient) {}

  /** 某人改了姓名或头像颜色（所有人都看得到）。 */
  public record UserUpdated(String userId, String originClient) {}

  /** 某人的小A对话流新增或更新了卡片。 */
  public record AgentItemsChanged(String userId, List<ObjectNode> items, String originClient) {}

  private final ApplicationEventPublisher publisher;

  public RealtimeEvents(ApplicationEventPublisher publisher) {
    this.publisher = publisher;
  }

  public void messageCreated(long messageId, String conversationId) {
    publisher.publishEvent(new MessageCreated(messageId, conversationId, ClientIds.current()));
  }

  public void conversationRead(String userId, String conversationId) {
    publisher.publishEvent(new ConversationRead(userId, conversationId, ClientIds.current()));
  }

  public void todosChanged(String userId, Collection<Long> todoIds) {
    if (!todoIds.isEmpty()) publisher.publishEvent(new TodosChanged(userId, List.copyOf(todoIds), ClientIds.current()));
  }

  public void userUpdated(String userId) {
    publisher.publishEvent(new UserUpdated(userId, ClientIds.current()));
  }

  public void memoryChanged(String userId) {
    publisher.publishEvent(new MemoryChanged(userId, ClientIds.current()));
  }

  public void preferencesChanged(String userId) {
    publisher.publishEvent(new PreferencesChanged(userId, ClientIds.current()));
  }

  public void capabilitiesChanged(String userId) {
    publisher.publishEvent(new CapabilitiesChanged(userId, ClientIds.current()));
  }

  public void agentItemsChanged(String userId, List<ObjectNode> items) {
    if (!items.isEmpty()) publisher.publishEvent(new AgentItemsChanged(userId, items, ClientIds.current()));
  }
}
