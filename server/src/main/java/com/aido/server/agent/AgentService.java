package com.aido.server.agent;

import com.aido.server.chat.ChatDtos.MessageDto;
import com.aido.server.chat.ChatService;
import com.aido.server.common.NotFoundException;
import com.aido.server.realtime.RealtimeEvents;
import com.aido.server.todo.TodoDtos.TodoDto;
import com.aido.server.todo.TodoRepository;
import com.aido.server.todo.TodoService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AgentService {

  private final AgentProperties props;
  private final AgentBrain brain;
  private final AgentFeedRepository feed;
  private final TodoService todos;
  private final TodoRepository todoRepo;
  private final ChatService chat;
  private final JsonMapper json;
  private final RealtimeEvents events;

  public AgentService(
      AgentProperties props, AgentBrain brain, AgentFeedRepository feed, TodoService todos, TodoRepository todoRepo,
      ChatService chat, JsonMapper json, RealtimeEvents events) {
    this.props = props;
    this.brain = brain;
    this.feed = feed;
    this.todos = todos;
    this.todoRepo = todoRepo;
    this.chat = chat;
    this.json = json;
    this.events = events;
  }

  /** 小A不可用时，所有 AI 接口返回 503；前端据此隐藏 AI 元素，基础功能照常使用。 */
  public void requireAvailable() {
    if (!props.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, props.name() + "暂时不可用");
  }

  /**
   * @param steps 思考步骤，前端逐条播放后再展示 items
   * @param items 本轮新增到对话流的卡片，第一项是用户这句话
   */
  public record Exchange(List<String> steps, List<ObjectNode> items) {}

  @Transactional
  public Exchange send(String me, String text, AgentBrain.Context ctx) {
    requireAvailable();
    var items = new ArrayList<ObjectNode>();
    var said = json.createObjectNode().put("text", text.strip());
    if (ctx != null && ctx.label() != null) said.put("context", ctx.label());
    items.add(feed.insert(me, "user", said));

    var reply = brain.reply(me, text.strip(), ctx);
    for (var card : reply.cards()) items.add(feed.insert(me, card.type(), card.payload()));
    events.agentItemsChanged(me, items);
    return new Exchange(reply.steps(), items);
  }

  // —— 提议卡片：引用的是 TODO 里「待确认」的项，确认 / 忽略直接改 TODO 状态 ——

  @Transactional
  public List<TodoDto> resolveProposal(String me, long itemId, boolean accept) {
    var item = item(me, itemId, "proposal");
    var ids = new ArrayList<Long>();
    item.path("todoIds").forEach(n -> ids.add(n.asLong()));
    if (ids.isEmpty()) return List.of();
    return todos.setStatus(me, ids, accept ? "open" : "dismissed");
  }

  // —— 草稿卡片 ——

  private static final Set<String> DRAFT_OPEN = Set.of("pending", "inserted");

  /** 「放进输入框」：前端把草稿写进聊天输入框，这里只记录状态。 */
  @Transactional
  public ObjectNode insertDraft(String me, long itemId) {
    openDraft(me, itemId);
    feed.mergePayload(me, itemId, json.createObjectNode().put("state", "inserted"));
    return updated(me, itemId);
  }

  @Transactional
  public ObjectNode dismissDraft(String me, long itemId) {
    openDraft(me, itemId);
    feed.mergePayload(me, itemId, json.createObjectNode().put("state", "dismissed"));
    return updated(me, itemId);
  }

  /**
   * @param completedTodo 发出后顺带勾掉的「需要回复」TODO，没有则为空
   */
  public record DraftSent(ObjectNode item, MessageDto message, TodoDto completedTodo) {}

  /** 直接发送草稿（text 不为空时发送改过的内容），并勾掉该会话里需要回复的 TODO。 */
  @Transactional
  public DraftSent sendDraft(String me, long itemId, String editedText) {
    var draft = openDraft(me, itemId);
    var text = editedText != null && !editedText.isBlank() ? editedText : draft.path("text").asString();
    var conversationId = draft.path("conversationId").asString();
    var message = chat.send(me, conversationId, text);
    var done = todoRepo.completeReplyTodo(me, conversationId).orElse(null);
    if (done != null) events.todosChanged(me, List.of(done.id()));

    var patch = json.createObjectNode().put("state", "sent").put("text", text);
    if (done != null) patch.put("doneTodo", done.title());
    feed.mergePayload(me, itemId, patch);
    return new DraftSent(updated(me, itemId), message, done);
  }

  /** 卡片更新后同步到这个人的其他标签页。 */
  private ObjectNode updated(String me, long itemId) {
    var item = feed.find(me, itemId).orElseThrow();
    events.agentItemsChanged(me, List.of(item));
    return item;
  }

  private JsonNode openDraft(String me, long itemId) {
    requireAvailable();
    var item = item(me, itemId, "draft");
    if (!DRAFT_OPEN.contains(item.path("state").asString())) throw new IllegalStateException("草稿已处理过了");
    return item;
  }

  private ObjectNode item(String me, long itemId, String type) {
    var item = feed.find(me, itemId).orElseThrow(() -> new NotFoundException("对话卡片", itemId));
    if (!type.equals(item.path("type").asString())) throw new IllegalArgumentException("这张卡片不是 " + type);
    return item;
  }
}
